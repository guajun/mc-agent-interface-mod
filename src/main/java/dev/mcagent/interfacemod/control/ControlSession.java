package dev.mcagent.interfacemod.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One authenticated TLS control session on the game port.
 *
 * <p>Framing is a 4-byte big-endian length followed by one UTF-8 JSON object.
 * The first client frame must be a {@code hello} carrying the access credential
 * and the last event sequence the client saw. Every request names an operation
 * and a client-chosen request id; every reply echoes the id, and writes are
 * deduplicated by that id so a reconnect can ask {@code request_status} instead
 * of replaying a non-idempotent operation.
 *
 * <p>Requests are answered off the game thread by {@link ControlOps}; a request
 * that times out before it started is safe to retry, one that timed out while
 * running reports {@code resultUnknown}.
 */
public final class ControlSession extends ByteToMessageDecoder {
    private static final int LENGTH_BYTES = 4;

    private final ControlServer server;
    private final Channel channel;
    private final String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String remote;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long openedAtMillis = System.currentTimeMillis();

    private volatile ControlAuth.Auth auth;
    private volatile boolean welcomed;
    private volatile boolean fatal;
    private volatile long lastSeenSeq;
    private volatile long droppedEvents;
    private ScheduledFuture<?> helloDeadline;

    ControlSession(ControlServer server, Channel channel) {
        this.server = server;
        this.channel = channel;
        this.remote = String.valueOf(channel.remoteAddress());
    }

    public String sessionId() {
        return sessionId;
    }

    Channel channel() {
        return channel;
    }

    public boolean welcomed() {
        return welcomed;
    }

    public String tokenId() {
        ControlAuth.Auth current = auth;
        return current == null ? null : current.tokenId;
    }

    public long droppedEvents() {
        return droppedEvents;
    }

    public String remote() {
        return remote;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        long seconds = server.handshakeSeconds();
        if (seconds > 0) {
            helloDeadline = context.executor().schedule(() -> {
                if (!welcomed && channel.isOpen()) {
                    ControlServer.log("control session " + sessionId + " (" + remote
                            + ") closed: no authenticated hello within " + seconds + "s");
                    close("hello timeout");
                }
            }, seconds, TimeUnit.SECONDS);
        }
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out) {
        if (fatal) {
            return;
        }
        int maxFrame = server.maxFrameBytes();
        while (in.readableBytes() >= LENGTH_BYTES) {
            int length = in.getInt(in.readerIndex());
            if (length <= 0 || length > maxFrame) {
                fatal("frame_too_large", "frame length " + length + " is outside 1.." + maxFrame);
                return;
            }
            if (in.readableBytes() < LENGTH_BYTES + length) {
                return;
            }
            in.skipBytes(LENGTH_BYTES);
            ByteBuf payload = in.readRetainedSlice(length);
            String text = payload.toString(StandardCharsets.UTF_8);
            payload.release();
            JsonObject frame;
            try {
                frame = JsonParser.parseString(text).getAsJsonObject();
            } catch (RuntimeException exception) {
                fatal("bad_frame", "frame is not a JSON object");
                return;
            }
            try {
                handleFrame(context, frame);
            } catch (RuntimeException exception) {
                sendFrame(error("internal", "control request failed: " + exception, null));
                ControlServer.log("control session " + sessionId + " frame failed: " + exception);
            }
            if (fatal) {
                return;
            }
        }
    }

    private void handleFrame(ChannelHandlerContext context, JsonObject frame) {
        String type = frame.has("type") ? frame.get("type").getAsString() : "";
        if (!welcomed) {
            if (!"hello".equals(type)) {
                fatal("protocol_error", "the first frame must be a hello");
                return;
            }
            handleHello(frame);
            return;
        }
        switch (type) {
            case "request" -> handleRequest(frame);
            case "ping" -> sendFrame(okFrame(frameId(frame), pongJson()));
            default -> sendFrame(error("protocol_error",
                    "unknown frame type: " + type, null));
        }
    }

    private void handleHello(JsonObject frame) {
        int protocol = frame.has("protocol") ? frame.get("protocol").getAsInt() : -1;
        if (protocol != ControlServer.CONTROL_PROTOCOL_VERSION) {
            fatal("protocol_version", "control protocol " + protocol + " is not supported; this server speaks "
                    + ControlServer.CONTROL_PROTOCOL_VERSION);
            return;
        }
        String token = frame.has("token") && frame.get("token").isJsonPrimitive()
                ? frame.get("token").getAsString() : "";
        if (!server.allowAuthAttempt(remote)) {
            fatal("rate_limited", "too many failed authentication attempts; retry later");
            return;
        }
        ControlAuth.Result result = server.auth.authenticate(token, System.currentTimeMillis());
        if (result.status != ControlAuth.Status.OK) {
            server.recordAuthFailure(remote, result.status);
            // One generic answer: never disclose which part of the credential was wrong.
            fatal("unauthorized", "the access credential was rejected");
            return;
        }
        this.auth = result.auth;
        this.lastSeenSeq = frame.has("lastSeq") && frame.get("lastSeq").isJsonPrimitive()
                ? Math.max(0L, frame.get("lastSeq").getAsLong()) : 0L;
        server.sessionOpened(this);

        JsonObject hello = new JsonObject();
        hello.addProperty("type", "welcome");
        hello.addProperty("protocol", ControlServer.CONTROL_PROTOCOL_VERSION);
        hello.addProperty("mod", "mc-agent-interface");
        hello.addProperty("modVersion", dev.mcagent.interfacemod.InterfaceConstants.VERSION);
        hello.addProperty("minecraft", dev.mcagent.interfacemod.InterfaceConstants.MINECRAFT_VERSION);
        hello.addProperty("instanceId", server.instanceId());
        hello.addProperty("runId", server.runId());
        hello.addProperty("runStartedAtMillis", server.runStartedAtMillis());
        hello.addProperty("sessionId", sessionId);
        hello.addProperty("transport", "same-port-tls");
        hello.addProperty("serverTimeMillis", System.currentTimeMillis());
        hello.add("permissions", server.permissions(auth));
        hello.add("capabilities", server.capabilityTokens());
        ControlServer.Replay replay = server.replaySince(lastSeenSeq);
        JsonObject replayJson = new JsonObject();
        replayJson.addProperty("requestedSince", lastSeenSeq);
        replayJson.addProperty("from", replay.from());
        replayJson.addProperty("to", replay.to());
        replayJson.addProperty("lost", replay.lost());
        replayJson.addProperty("bufferedEvents", replay.events().size());
        replayJson.addProperty("persistedAcrossRuns", false);
        hello.add("replay", replayJson);
        hello.add("limits", server.limitsJson());
        if (frame.has("client")) {
            hello.add("client", frame.get("client"));
        }
        sendFrame(hello);
        long replayed = 0;
        for (JsonObject event : replay.events()) {
            JsonObject envelope = event.deepCopy();
            envelope.addProperty("replay", true);
            sendFrame(envelope);
            replayed++;
        }
        this.welcomed = true;
        if (helloDeadline != null) {
            helloDeadline.cancel(false);
            helloDeadline = null;
        }
        ControlServer.log("control session " + sessionId + " (" + remote + ") authenticated as token "
                + auth.tokenId + (auth.label().isEmpty() ? "" : " (" + auth.label() + ")")
                + " permissions=" + auth.permissions + " replay=" + replayed
                + (replay.lost() ? " (gap: events before seq " + replay.from() + " are not available)" : ""));
    }

    private void handleRequest(JsonObject frame) {
        String id = frameId(frame);
        if (id.isEmpty() || id.length() > 128) {
            sendFrame(error("bad_request", "request id must be 1..128 characters", null));
            return;
        }
        String operation = frame.has("op") ? frame.get("op").getAsString() : "";
        JsonObject params = frame.has("params") && frame.get("params").isJsonObject()
                ? frame.getAsJsonObject("params") : new JsonObject();
        boolean write = ControlOps.WRITE_OPERATIONS.contains(operation);

        ControlAuth.Auth current = auth;
        if (current == null) {
            sendFrame(replyError(id, "unauthorized", "session is not authenticated", false, false, null));
            return;
        }
        if (!server.operationAllowed(operation, current)) {
            sendFrame(replyError(id, "capability_not_supported",
                    "operation " + operation + " is not available on this server vantage", false, false, null));
            return;
        }
        if (write && !current.canWrite()) {
            sendFrame(replyError(id, "forbidden", "this credential has no write permission", false, false, null));
            return;
        }
        if (write) {
            ControlServer.WriteStatus prior = server.writeStatus(current.tokenId, id);
            if (prior != null && prior.state == ControlServer.WriteState.OK) {
                JsonObject result = prior.result == null ? new JsonObject() : prior.result.deepCopy();
                result.addProperty("duplicate", true);
                result.addProperty("requestId", id);
                sendFrame(okFrame(id, result));
                return;
            }
            if (prior != null && prior.state == ControlServer.WriteState.FAIL) {
                JsonObject priorError = prior.error == null ? new JsonObject() : prior.error.deepCopy();
                priorError.addProperty("duplicate", true);
                sendFrame(errorFrame(id, priorError));
                return;
            }
            if (prior != null && prior.state == ControlServer.WriteState.PENDING) {
                sendFrame(replyError(id, "request_in_flight",
                        "request " + id + " is still running; use request_status", true, false, null));
                return;
            }
        }

        if (pending.size() >= server.maxPendingRequests()) {
            sendFrame(replyError(id, "too_many_pending",
                    "this session already has " + pending.size() + " pending requests", true, false, null));
            return;
        }
        long timeoutMillis = server.defaultRequestTimeoutMillis();
        if (frame.has("timeoutMillis") && frame.get("timeoutMillis").isJsonPrimitive()) {
            timeoutMillis = Math.max(1_000L, Math.min(300_000L, frame.get("timeoutMillis").getAsLong()));
        }
        Pending request = new Pending(id, operation, params, write, timeoutMillis);
        pending.put(id, request);
        if (write) {
            server.writeBegan(current.tokenId, id, operation);
        }
        request.timeout = channel.eventLoop().schedule(request::onTimeout, timeoutMillis, TimeUnit.MILLISECONDS);
        server.sessionRequestBegan(this);
        if ("ping".equals(operation)) {
            JsonObject pong = new JsonObject();
            pong.addProperty("pong", true);
            pong.addProperty("runId", server.runId());
            pong.addProperty("sessionId", sessionId);
            request.ok(pong);
            return;
        }
        if ("capabilities".equals(operation)) {
            request.ok(server.capabilitiesReply());
            return;
        }
        if ("request_status".equals(operation)) {
            handleRequestStatus(request);
            return;
        }
        if (operation.startsWith("exclusive_")) {
            handleExclusive(request);
            return;
        }
        try {
            server.ops().execute(operation, params, request);
        } catch (RuntimeException exception) {
            request.fail("internal", "operation failed to start: " + exception, true, false);
        }
    }

    private void handleRequestStatus(Pending request) {
        String requestId = stringParam(request.params, "requestId");
        if (requestId.isEmpty()) {
            request.fail("bad_request", "request_status needs params.requestId", false, false);
            return;
        }
        ControlServer.WriteStatus status = server.writeStatus(auth.tokenId, requestId);
        if (status == null) {
            JsonObject result = new JsonObject();
            result.addProperty("requestId", requestId);
            result.addProperty("state", "unknown");
            result.addProperty("note", "this credential has no record of the request in this run");
            request.ok(result);
            return;
        }
        request.ok(status.toJson());
    }

    private void handleExclusive(Pending request) {
        String key = stringParam(request.params, "key");
        long now = System.currentTimeMillis();
        switch (request.operation) {
            case "exclusive_acquire" -> {
                if (!auth.canWrite()) {
                    request.fail("forbidden", "exclusive operations need write permission", false, false);
                    return;
                }
                long ttlSeconds = numberParam(request.params, "ttlSeconds", 300L);
                ttlSeconds = Math.max(10L, Math.min(3_600L, ttlSeconds));
                String label = stringParam(request.params, "label");
                try {
                    ControlLeases.Acquire acquire = server.leases.acquire(key, auth.tokenId, sessionId,
                            label, ttlSeconds * 1000L, now);
                    if (!acquire.acquired) {
                        JsonObject details = new JsonObject();
                        details.add("holder", acquire.holder.toJson(now));
                        request.fail("conflict", "exclusive key '" + key + "' is held by another credential",
                                true, false, details);
                        return;
                    }
                    JsonObject result = acquire.lease.toJson(now);
                    result.addProperty("acquired", true);
                    request.ok(result);
                } catch (IllegalArgumentException exception) {
                    request.fail("bad_request", exception.getMessage(), false, false);
                }
            }
            case "exclusive_renew" -> {
                if (!auth.canWrite()) {
                    request.fail("forbidden", "exclusive operations need write permission", false, false);
                    return;
                }
                long ttlSeconds = numberParam(request.params, "ttlSeconds", 300L);
                ttlSeconds = Math.max(10L, Math.min(3_600L, ttlSeconds));
                try {
                    ControlLeases.Lease holder = server.leases.holderIfForeign(key, auth.tokenId, now);
                    if (holder != null) {
                        JsonObject details = new JsonObject();
                        details.add("holder", holder.toJson(now));
                        request.fail("conflict", "exclusive key '" + key + "' is held by another credential",
                                true, false, details);
                        return;
                    }
                    ControlLeases.Lease lease = server.leases.renew(key, auth.tokenId,
                            ttlSeconds * 1000L, now);
                    if (lease == null) {
                        request.fail("not_found", "no exclusive lease for '" + key + "'", false, false);
                        return;
                    }
                    request.ok(lease.toJson(now));
                } catch (IllegalArgumentException exception) {
                    request.fail("bad_request", exception.getMessage(), false, false);
                }
            }
            case "exclusive_release" -> {
                if (!auth.canWrite()) {
                    request.fail("forbidden", "exclusive operations need write permission", false, false);
                    return;
                }
                try {
                    ControlLeases.ReleaseStatus status = server.leases.release(key, auth.tokenId, now);
                    JsonObject result = new JsonObject();
                    result.addProperty("key", key);
                    switch (status) {
                        case RELEASED -> {
                            result.addProperty("released", true);
                            request.ok(result);
                        }
                        case NOT_FOUND -> request.fail("not_found",
                                "no exclusive lease for '" + key + "'", false, false);
                        case NOT_HOLDER -> request.fail("conflict",
                                "exclusive key '" + key + "' is held by another credential", true, false);
                    }
                } catch (IllegalArgumentException exception) {
                    request.fail("bad_request", exception.getMessage(), false, false);
                }
            }
            case "exclusive_status" -> {
                JsonObject result = new JsonObject();
                if (key.isEmpty()) {
                    result.add("leases", server.leases.toJson(now));
                } else {
                    ControlLeases.Lookup lookup = server.leases.lookup(key, auth.tokenId, now);
                    ControlLeases.Lease holder = lookup.lease != null && !lookup.mine ? lookup.lease : null;
                    ControlLeases.Lease mine = lookup.mine ? lookup.lease : null;
                    result.addProperty("key", key);
                    if (holder != null) {
                        result.addProperty("held", true);
                        result.addProperty("mine", false);
                        result.add("holder", holder.toJson(now));
                    } else if (mine != null) {
                        result.addProperty("held", true);
                        result.addProperty("mine", true);
                        result.add("lease", mine.toJson(now));
                    } else {
                        result.addProperty("held", false);
                    }
                }
                request.ok(result);
            }
            default -> request.fail("capability_not_supported",
                    "unknown exclusive operation: " + request.operation, false, false);
        }
    }

    /** Push one live event envelope (already sequenced by {@link ControlServer}). */
    void sendEvent(JsonObject envelope, boolean replay) {
        if (closed.get() || !welcomed) {
            return;
        }
        if (!channel.isWritable()) {
            droppedEvents++;
            if (droppedEvents == 1 || droppedEvents % 256 == 0) {
                ControlServer.log("control session " + sessionId + " is not writable; dropped "
                        + droppedEvents + " events (replay buffer still holds them)");
            }
            if (droppedEvents > server.maxDroppedEvents()) {
                close("slow consumer: dropped " + droppedEvents + " events");
            }
            return;
        }
        JsonObject copy = envelope.deepCopy();
        if (replay) {
            copy.addProperty("replay", true);
        }
        sendFrame(copy);
    }

    /** Send a fatal protocol error and close after the write flushes. */
    private void fatal(String code, String message) {
        sendFrameAndClose(error(code, message, null));
    }

    private void sendFrameAndClose(JsonObject frame) {
        if (closed.get()) {
            return;
        }
        fatal = true;
        channel.writeAndFlush(frameBuffer(frame)).addListener(future -> close("protocol error"));
    }

    private JsonObject replyError(String id, String code, String message, boolean retryable,
                                  boolean resultUnknown, JsonObject details) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        error.addProperty("retryable", retryable);
        error.addProperty("resultUnknown", resultUnknown);
        if (details != null) {
            error.add("details", details);
        }
        return errorFrame(id, error);
    }

    private JsonObject pongJson() {
        JsonObject pong = new JsonObject();
        pong.addProperty("pong", true);
        pong.addProperty("runId", server.runId());
        pong.addProperty("sessionId", sessionId);
        pong.addProperty("serverTimeMillis", System.currentTimeMillis());
        return pong;
    }

    private void sendFrame(JsonObject frame) {
        if (closed.get()) {
            return;
        }
        ByteBuf buffer = frameBuffer(frame);
        if (channel.eventLoop().inEventLoop()) {
            channel.writeAndFlush(buffer);
        } else {
            channel.eventLoop().execute(() -> channel.writeAndFlush(buffer));
        }
    }

    private ByteBuf frameBuffer(JsonObject frame) {
        byte[] payload = frame.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuf buffer = channel.alloc().buffer(LENGTH_BYTES + payload.length);
        buffer.writeInt(payload.length);
        buffer.writeBytes(payload);
        return buffer;
    }

    /** Close the session; idempotent, safe from any thread. */
    void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Pending request : pending.values()) {
            request.onSessionClosed();
        }
        pending.clear();
        server.sessionClosed(this, reason);
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // closing anyway
        }
        ControlServer.log("control session " + sessionId + " (" + remote + ") closed: " + reason);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        close("channel closed by peer");
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        String reason = cause.getClass().getSimpleName();
        if (cause instanceof io.netty.handler.timeout.ReadTimeoutException) {
            reason = "idle timeout";
        }
        ControlServer.log("control session " + sessionId + " (" + remote + ") failed: " + cause);
        close(reason);
    }

    private JsonObject error(String code, String message, String id) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("code", code);
        error.addProperty("message", message);
        return error;
    }

    private JsonObject errorFrame(String id, JsonObject prior) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "reply");
        frame.addProperty("ok", false);
        frame.addProperty("id", id);
        frame.add("error", prior);
        return frame;
    }

    private JsonObject okFrame(String id, JsonElement result) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "reply");
        frame.addProperty("ok", true);
        frame.addProperty("id", id);
        frame.add("result", result);
        frame.addProperty("serverTimeMillis", System.currentTimeMillis());
        return frame;
    }

    private static String frameId(JsonObject frame) {
        return frame.has("id") && frame.get("id").isJsonPrimitive()
                ? frame.get("id").getAsString() : "";
    }

    static String stringParam(JsonObject params, String name) {
        if (params != null && params.has(name) && params.get(name).isJsonPrimitive()) {
            return params.get(name).getAsString().trim();
        }
        return "";
    }

    static long numberParam(JsonObject params, String name, long fallback) {
        if (params != null && params.has(name) && params.get(name).isJsonPrimitive()) {
            try {
                return params.get(name).getAsLong();
            } catch (NumberFormatException | UnsupportedOperationException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** One in-flight request. */
    private final class Pending implements ControlOps.Reply {
        private final String id;
        private final String operation;
        private final JsonObject params;
        private final boolean write;
        private final long timeoutMillis;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile boolean started;
        private volatile boolean timedOut;
        private ScheduledFuture<?> timeout;

        Pending(String id, String operation, JsonObject params, boolean write, long timeoutMillis) {
            this.id = id;
            this.operation = operation;
            this.params = params;
            this.write = write;
            this.timeoutMillis = timeoutMillis;
        }

        @Override
        public void started() {
            started = true;
        }

        @Override
        public void ok(JsonElement result) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            cancelTimeout();
            gone();
            JsonElement payload = result == null ? new JsonObject() : result;
            if (write) {
                JsonObject object = payload.isJsonObject() ? payload.getAsJsonObject().deepCopy() : new JsonObject();
                if (!object.has("requestId")) {
                    object.addProperty("requestId", id);
                }
                long writeSeq = server.writeCompleted(auth.tokenId, id, operation, true, object, null);
                object.addProperty("writeSeq", writeSeq);
                payload = object;
            }
            if (!timedOut) {
                sendFrame(okFrame(id, payload));
            }
        }

        @Override
        public void fail(String code, String message, boolean retryable, boolean resultUnknown) {
            fail(code, message, retryable, resultUnknown, null);
        }

        @Override
        public void fail(String code, String message, boolean retryable, boolean resultUnknown,
                         JsonObject details) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            cancelTimeout();
            gone();
            JsonObject error = new JsonObject();
            error.addProperty("code", code);
            error.addProperty("message", message);
            error.addProperty("retryable", retryable);
            error.addProperty("resultUnknown", resultUnknown);
            if (details != null) {
                error.add("details", details);
            }
            if (write) {
                server.writeCompleted(auth.tokenId, id, operation, false, null, error);
            }
            if (!timedOut) {
                sendFrame(errorFrame(id, error));
            }
        }

        /** The client gave up waiting. Keep the operation; report what we know. */
        private void onTimeout() {
            if (finished.get()) {
                return;
            }
            timedOut = true;
            gone();
            JsonObject error = new JsonObject();
            error.addProperty("code", "timeout");
            error.addProperty("retryable", !started);
            error.addProperty("resultUnknown", started);
            error.addProperty("message", started
                    ? "operation " + operation + " is still running; its result is unknown, query request_status"
                    : "operation " + operation + " did not start in " + timeoutMillis + "ms; it is safe to retry");
            if (write && !started) {
                server.writeAbandoned(auth.tokenId, id);
            }
            sendFrame(errorFrame(id, error));
        }

        private void onSessionClosed() {
            if (finished.get()) {
                return;
            }
            finished.set(true);
            cancelTimeout();
            gone();
            // A write that was already handed to the game thread keeps running and
            // its result is stored for request_status; a queued one can still run
            // too, because it is already scheduled. Neither is reported as failed.
        }

        private void gone() {
            pending.remove(id);
            server.sessionRequestFinished(ControlSession.this);
        }

        private void cancelTimeout() {
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
    }
}
