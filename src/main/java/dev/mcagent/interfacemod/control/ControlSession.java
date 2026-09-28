package dev.mcagent.interfacemod.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p>Requests are answered off the game thread by {@link ControlOps}. The
 * session owns the request state machine: an unstarted request can still be
 * cancelled, a running request reports result-unknown on timeout, and its
 * terminal outcome is recorded even if the socket is already gone.
 */
public final class ControlSession extends ByteToMessageDecoder {
    private static final int LENGTH_BYTES = 4;

    /** Operations that only reveal state and therefore need a read permission. */
    private static final java.util.Set<String> READ_OPERATIONS = java.util.Set.of(
            "state", "entities", "player", "context", "wait", "snapshot", "snapshots",
            "exclusive_status");

    private final ControlServer server;
    private final Channel channel;
    private final String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String remote;
    private final String remoteIp;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong outboundBytes = new AtomicLong();
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
        this.remoteIp = addressOf(channel.remoteAddress());
    }

    private static String addressOf(java.net.SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return String.valueOf(address);
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

    /** Bytes reserved for frames that are queued but not yet written. */
    long outboundBytes() {
        return outboundBytes.get();
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
        if (!server.allowAuthAttempt(remoteIp)) {
            fatal("rate_limited", "too many failed authentication attempts; retry later");
            return;
        }
        ControlAuth.Result result = server.auth.authenticate(token, System.currentTimeMillis());
        if (result.status != ControlAuth.Status.OK) {
            server.recordAuthFailure(remoteIp, result.status);
            // One generic answer: never disclose which part of the credential was wrong.
            fatal("unauthorized", "the access credential was rejected");
            return;
        }
        this.auth = result.auth;
        long requestedSeq = frame.has("lastSeq") && frame.get("lastSeq").isJsonPrimitive()
                ? Math.max(0L, frame.get("lastSeq").getAsLong()) : 0L;
        boolean runMatches = !frame.has("runId") || !frame.get("runId").isJsonPrimitive()
                || frame.get("runId").getAsString().equals(server.runId());
        this.lastSeenSeq = runMatches ? requestedSeq : 0L;
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
        hello.add("limits", server.limitsJson());
        if (frame.has("client")) {
            hello.add("client", frame.get("client"));
        }

        // Replay and the live hand-off are one atomic step under the event
        // lock: an event published while the welcome is being written is
        // either included in the replay or delivered live, never lost and
        // never reordered.
        long replayed = 0;
        ControlServer.Replay replay;
        synchronized (server.eventLock()) {
            replay = !runMatches
                    ? server.replaySince(0, true)
                    : server.replaySince(lastSeenSeq, false);
            JsonObject replayJson = new JsonObject();
            replayJson.addProperty("requestedSince", lastSeenSeq);
            replayJson.addProperty("from", replay.from());
            replayJson.addProperty("to", replay.to());
            replayJson.addProperty("lost", replay.lost());
            replayJson.addProperty("bufferedEvents", replay.events().size());
            replayJson.addProperty("persistedAcrossRuns", false);
            replayJson.addProperty("crossRun", !runMatches);
            hello.add("replay", replayJson);
            if (sendFrame(hello) && server.tokenValid(auth.tokenId, ControlAuth.PERMISSION_READ)) {
                for (JsonObject event : replay.events()) {
                    JsonObject envelope = event.deepCopy();
                    envelope.addProperty("replay", true);
                    if (!sendFrame(envelope)) {
                        break;
                    }
                    replayed++;
                }
            }
            this.welcomed = true;
        }
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
        if (!authorizedNow(operation, current)) {
            sendFrame(replyError(id, "forbidden",
                    permissionErrorMessage(operation, current), false, false, null));
            return;
        }

        long timeoutMillis = server.defaultRequestTimeoutMillis();
        if (frame.has("timeoutMillis") && frame.get("timeoutMillis").isJsonPrimitive()) {
            timeoutMillis = Math.max(server.minRequestTimeoutMillis(),
                    Math.min(300_000L, frame.get("timeoutMillis").getAsLong()));
        }
        Pending request = new Pending(id, operation, params, write, timeoutMillis);
        // A duplicate id never overwrites a live request, read or write, and
        // it is rejected before any ledger reservation so a colliding write
        // cannot claim or mutate another request's record.
        Pending live = pending.putIfAbsent(id, request);
        if (live != null) {
            sendFrame(replyError(id, "request_in_flight",
                    "request id " + id + " is already in flight", true, false, null));
            return;
        }

        if (write) {
            String payloadHash = ControlServer.payloadHash(operation, params);
            ControlServer.Reservation reservation = server.reserveWrite(current.tokenId, id, operation, payloadHash);
            switch (reservation.kind) {
                case CONFLICT -> {
                    pending.remove(id, request);
                    sendFrame(replyError(id, "conflict",
                            "request id " + id + " was used before with a different payload", false, false, null));
                    return;
                }
                case IN_FLIGHT -> {
                    pending.remove(id, request);
                    sendFrame(replyError(id, "request_in_flight",
                            "request " + id + " is still running; use request_status", true, false, null));
                    return;
                }
                case UNKNOWN_STATE -> {
                    pending.remove(id, request);
                    sendFrame(replyError(id, "result_unknown",
                            "request " + id + " has an unknown outcome; query request_status", false, true, null));
                    return;
                }
                case BUSY -> {
                    pending.remove(id, request);
                    sendFrame(replyError(id, "server_busy",
                            "the write ledger is full of in-flight requests; retry shortly", true, false, null));
                    return;
                }
                case DUPLICATE -> {
                    pending.remove(id, request);
                    ControlServer.WriteStatus prior = reservation.status;
                    if (prior.state == ControlServer.WriteState.OK) {
                        JsonObject result = prior.result == null ? new JsonObject() : prior.result.deepCopy();
                        result.addProperty("duplicate", true);
                        result.addProperty("requestId", id);
                        sendFrame(okFrame(id, result));
                    } else {
                        JsonObject priorError = prior.error == null ? new JsonObject() : prior.error.deepCopy();
                        priorError.addProperty("duplicate", true);
                        sendFrame(errorFrame(id, priorError));
                    }
                    return;
                }
                case RESERVED -> {
                    // fall through to dispatch below
                }
                default -> throw new IllegalStateException("unknown reservation " + reservation.kind);
            }
        }

        if (pending.size() > server.maxPendingRequests()) {
            pending.remove(id, request);
            if (write) {
                server.writeAbandoned(current.tokenId, id);
            }
            sendFrame(replyError(id, "too_many_pending",
                    "this session already has " + pending.size() + " pending requests", true, false, null));
            return;
        }
        request.timeout = channel.eventLoop().schedule(request::onTimeout, timeoutMillis, TimeUnit.MILLISECONDS);
        server.sessionRequestBegan(this);
        if ("ping".equals(operation)) {
            request.ok(pongJson());
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

    /** True when the live credential still authorizes this operation. */
    private boolean authorizedNow(String operation, ControlAuth.Auth current) {
        ControlAuth.Entry entry = server.auth.entry(current.tokenId);
        if (entry == null || entry.revoked) {
            return false;
        }
        if (entry.expiresAtMillis != null && System.currentTimeMillis() > entry.expiresAtMillis) {
            return false;
        }
        boolean write = ControlOps.WRITE_OPERATIONS.contains(operation)
                || (operation.startsWith("exclusive_") && !"exclusive_status".equals(operation));
        if (write) {
            return entry.has(ControlAuth.PERMISSION_WRITE);
        }
        if ("request_status".equals(operation)) {
            return entry.has(ControlAuth.PERMISSION_READ) || entry.has(ControlAuth.PERMISSION_WRITE);
        }
        if (READ_OPERATIONS.contains(operation)) {
            return entry.has(ControlAuth.PERMISSION_READ);
        }
        return entry.has(ControlAuth.PERMISSION_READ) || entry.has(ControlAuth.PERMISSION_WRITE);
    }

    private static String permissionErrorMessage(String operation, ControlAuth.Auth current) {
        if (READ_OPERATIONS.contains(operation)) {
            return "this credential has no read permission";
        }
        return "this credential has no permission for " + operation;
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
                if (!server.tokenValid(auth.tokenId, ControlAuth.PERMISSION_WRITE)) {
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
                if (!server.tokenValid(auth.tokenId, ControlAuth.PERMISSION_WRITE)) {
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
                if (!server.tokenValid(auth.tokenId, ControlAuth.PERMISSION_WRITE)) {
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

    /**
     * Push one live event envelope (already sequenced by {@link ControlServer}).
     * A session whose reader cannot keep up is closed instead of silently
     * dropping events: the client then reconnects and the server's replay
     * report makes the gap machine-readable.
     */
    boolean sendEvent(JsonObject envelope, boolean replay) {
        if (closed.get() || !welcomed) {
            return false;
        }
        ControlAuth.Auth current = auth;
        if (current == null || !server.tokenValid(current.tokenId, ControlAuth.PERMISSION_READ)) {
            // Events are read data; a write-only or revoked credential must
            // not receive them.
            return false;
        }
        if (!channel.isWritable()) {
            close("slow consumer: outbound buffer full");
            return false;
        }
        JsonObject copy = envelope.deepCopy();
        if (replay) {
            copy.addProperty("replay", true);
        }
        return sendFrame(copy);
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

    /**
     * Queue one frame under an explicit byte budget that covers frames not yet
     * run by the event loop. Returns false and closes the session when the
     * budget or the channel water mark is exceeded; a reply larger than the
     * protocol frame limit is replaced by a structured error instead of being
     * written or silently dropped.
     */
    private boolean sendFrame(JsonObject frame) {
        if (closed.get()) {
            return false;
        }
        JsonObject effective = frame;
        byte[] payload = effective.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > server.maxFrameBytes()) {
            if (effective.has("id") && effective.get("id").isJsonPrimitive()) {
                effective = replyError(effective.get("id").getAsString(), "response_too_large",
                        "the operation result exceeded the " + server.maxFrameBytes()
                                + " byte frame limit", false, false, null);
                payload = effective.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                close("outbound frame exceeds the frame limit");
                return false;
            }
        }
        long cost = payload.length + (long) LENGTH_BYTES;
        long queued = outboundBytes.addAndGet(cost);
        if (queued > server.maxOutboundBytes() || !channel.isWritable()) {
            outboundBytes.addAndGet(-cost);
            close(queued > server.maxOutboundBytes()
                    ? "outbound budget exceeded" : "slow consumer: outbound buffer full");
            return false;
        }
        ByteBuf buffer = channel.alloc().buffer(LENGTH_BYTES + payload.length);
        buffer.writeInt(payload.length);
        buffer.writeBytes(payload);
        io.netty.channel.ChannelFuture future;
        if (channel.eventLoop().inEventLoop()) {
            future = channel.writeAndFlush(buffer);
            future.addListener(written -> {
                outboundBytes.addAndGet(-cost);
                if (!written.isSuccess()) {
                    close("write failed: " + written.cause());
                }
            });
            return true;
        }
        try {
            channel.eventLoop().execute(() -> {
                io.netty.channel.ChannelFuture queuedWrite = channel.writeAndFlush(buffer);
                queuedWrite.addListener(written -> {
                    outboundBytes.addAndGet(-cost);
                    if (!written.isSuccess()) {
                        close("write failed: " + written.cause());
                    }
                });
            });
        } catch (RuntimeException exception) {
            buffer.release();
            outboundBytes.addAndGet(-cost);
            close("cannot queue outbound frame: " + exception);
            return false;
        }
        return true;
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

    /**
     * One in-flight request with a single atomic state machine: a queued
     * request can become RUNNING (claimed by the game thread) or CANCELLED
     * (timeout before start, disconnect, or a failed authorization). Only the
     * owner of that transition may act, so a timeout can never report "safe
     * retry" while the game thread is already executing, and a cancelled task
     * can never run.
     */
    private final class Pending implements ControlOps.Reply {
        private final String id;
        private final String operation;
        private final JsonObject params;
        private final boolean write;
        private final long timeoutMillis;
        private RequestState state = RequestState.QUEUED;
        private boolean timedOut;
        private ScheduledFuture<?> timeout;

        Pending(String id, String operation, JsonObject params, boolean write, long timeoutMillis) {
            this.id = id;
            this.operation = operation;
            this.params = params;
            this.write = write;
            this.timeoutMillis = timeoutMillis;
        }

        /**
         * Claim the operation on the game thread. Returns false when the
         * request is no longer QUEUED, so the queued game task is skipped
         * instead of running after a cancel/advertised retry.
         */
        @Override
        public boolean started() {
            synchronized (this) {
                if (state != RequestState.QUEUED) {
                    return false;
                }
                state = RequestState.RUNNING;
            }
            if (!authorizedNow(operation, auth)) {
                fail("unauthorized", "the credential was revoked or no longer permits " + operation,
                        false, false);
                return false;
            }
            return true;
        }

        @Override
        public void ok(JsonElement result) {
            if (!finish()) {
                return;
            }
            cancelTimeout();
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
            if (!timedOut && !closed.get()) {
                sendFrame(okFrame(id, payload));
            }
            gone();
        }

        @Override
        public void fail(String code, String message, boolean retryable, boolean resultUnknown) {
            fail(code, message, retryable, resultUnknown, null);
        }

        @Override
        public void fail(String code, String message, boolean retryable, boolean resultUnknown,
                         JsonObject details) {
            if (!finish()) {
                return;
            }
            cancelTimeout();
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
            if (!timedOut && !closed.get()) {
                sendFrame(errorFrame(id, error));
            }
            gone();
        }

        /** The client gave up waiting before the game thread claimed it. */
        private void onTimeout() {
            boolean cancelNow;
            synchronized (this) {
                if (state == RequestState.QUEUED) {
                    state = RequestState.CANCELLED;
                    cancelNow = true;
                } else if (state == RequestState.RUNNING) {
                    timedOut = true;
                    cancelNow = false;
                } else {
                    return; // DONE or CANCELLED: nothing to report
                }
            }
            if (cancelNow) {
                if (write) {
                    server.writeAbandoned(auth.tokenId, id);
                }
                gone();
                JsonObject error = new JsonObject();
                error.addProperty("code", "timeout");
                error.addProperty("retryable", true);
                error.addProperty("resultUnknown", false);
                error.addProperty("message", "operation " + operation + " did not start in "
                        + timeoutMillis + "ms; it is safe to retry");
                if (!closed.get()) {
                    sendFrame(errorFrame(id, error));
                }
                return;
            }
            // Already running: the ledger keeps the pending record until the
            // game thread reports the terminal outcome, which stays queryable
            // through request_status.
            JsonObject error = new JsonObject();
            error.addProperty("code", "timeout");
            error.addProperty("retryable", false);
            error.addProperty("resultUnknown", true);
            error.addProperty("message", "operation " + operation
                    + " is still running; its result is unknown, query request_status");
            if (!closed.get()) {
                sendFrame(errorFrame(id, error));
            }
        }

        /**
         * The socket is gone. An unclaimed request is cancelled and becomes
         * retryable; a running request keeps running and still records its
         * terminal outcome for request_status.
         */
        private void onSessionClosed() {
            boolean cancelNow;
            synchronized (this) {
                cancelNow = state == RequestState.QUEUED;
                if (cancelNow) {
                    state = RequestState.CANCELLED;
                }
            }
            if (cancelNow) {
                if (write) {
                    server.writeAbandoned(auth.tokenId, id);
                }
                gone();
            }
            cancelTimeout();
        }

        private boolean finish() {
            synchronized (this) {
                if (state == RequestState.DONE || state == RequestState.CANCELLED) {
                    return false;
                }
                state = RequestState.DONE;
                return true;
            }
        }

        private void gone() {
            // Conditional remove: never delete a different request that
            // somehow shares this id.
            pending.remove(id, this);
            server.sessionRequestFinished(ControlSession.this);
        }

        private void cancelTimeout() {
            ScheduledFuture<?> current = timeout;
            if (current != null) {
                current.cancel(false);
            }
        }
    }

    private enum RequestState {
        QUEUED,
        RUNNING,
        DONE,
        CANCELLED
    }
}
