package dev.mcagent.interfacemod.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcagent.interfacemod.InterfaceConstants;
import dev.mcagent.interfacemod.SamePortControl;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.network.Connection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The formal, authenticated control transport of mc-agent-interface-mod, on
 * the actual Minecraft game port (issue #8).
 *
 * <p>It shares the issue #7 seam: a connection whose first two bytes are
 * {@code 0x16 0x03} (a TLS ClientHello record header; no Minecraft handshake
 * can start that way, because the second byte of a handshake is packet id 0)
 * is adopted by the control server, every other connection is handed to the
 * vanilla pipeline untouched. After the TLS handshake the server speaks a
 * length-prefixed JSON protocol authenticated with a {@link ControlAuth}
 * credential; the plaintext JSON-lines spike stays a separate, explicitly
 * enabled experiment.
 *
 * <p>Safety budgets are explicit and bounded: a hello/TLS handshake deadline,
 * an idle read timeout, a maximum frame size, a per-session pending request
 * cap, a bounded event replay ring, and a slow-consumer event drop counter.
 * The control listener has no thread of its own - all game work is scheduled
 * on the server thread by {@link ControlOps}.
 */
public final class ControlServer {
    public static final String PROPERTY_ENABLED = "mcagent.control";
    public static final String PROPERTY_DIR = "mcagent.controlDir";
    public static final String PROPERTY_HANDSHAKE_SECONDS = "mcagent.controlHandshakeSeconds";
    public static final String PROPERTY_IDLE_SECONDS = "mcagent.controlIdleSeconds";
    public static final String PROPERTY_MAX_FRAME_BYTES = "mcagent.controlMaxFrameBytes";
    public static final String PROPERTY_EVENT_BUFFER = "mcagent.controlEventBuffer";
    public static final String PROPERTY_MAX_PENDING = "mcagent.controlMaxPending";
    public static final String PROPERTY_REQUEST_TIMEOUT_MILLIS = "mcagent.controlRequestTimeoutMillis";
    public static final String PROPERTY_MAX_DROPPED = "mcagent.controlMaxDroppedEvents";

    public static final int CONTROL_PROTOCOL_VERSION = 1;
    public static final String TRANSPORT = "same-port-tls";

    static final String SNIFFER_NAME = "mcagent-control-sniffer";
    static final String SSL_NAME = "mcagent-control-ssl";
    static final String IDLE_NAME = "mcagent-control-idle";
    static final String SESSION_NAME = "mcagent-control-session";

    private static final Object ATTACH_LOCK = new Object();
    private static volatile ControlServer instance;

    private final Path directory;
    final ControlAuth auth;
    final ControlLeases leases = new ControlLeases();
    private ControlOps ops;
    private SslContext sslContext;
    private String tlsFingerprint;
    private volatile String tlsProblem;
    private final String instanceId;
    private final String runId = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final long runStartedAtMillis = System.currentTimeMillis();

    private final Map<ChannelId, ControlSession> sessions = new ConcurrentHashMap<>();
    private final ArrayDeque<JsonObject> eventBuffer = new ArrayDeque<>();
    private final AtomicLong eventSequence = new AtomicLong();
    private final AtomicLong writeSequence = new AtomicLong();
    private final Map<String, WriteStatus> recentWrites = new LinkedHashMap<>();
    private final Map<String, FailureWindow> authFailures = new ConcurrentHashMap<>();
    private final AtomicLong authFailuresTotal = new AtomicLong();
    private final AtomicLong droppedEventsTotal = new AtomicLong();
    private final AtomicLong requestsStarted = new AtomicLong();
    private final AtomicLong requestsFinished = new AtomicLong();

    ControlServer(Path directory) throws IOException {
        this.directory = directory;
        this.auth = new ControlAuth(directory.resolve("tokens.json"));
        Files.createDirectories(directory);
        this.instanceId = loadOrCreateInstanceId(directory.resolve("instance.json"));
    }

    /** Load TLS material and credentials; split out so tests can prepare directly. */
    void prepare() {
        loadTls();
        loadTokens();
    }

    void setOps(ControlOps value) {
        this.ops = value;
    }

    // ------------------------------------------------------------------ static

    /** The formal control transport is opt-in; the spike keeps its own switch. */
    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY_ENABLED);
    }

    /** Load the TLS identity and credentials and bind the game-facing ops. */
    public static ControlServer attach(ControlOps ops, Path directory) {
        synchronized (ATTACH_LOCK) {
            ControlServer server = instance;
            if (server == null) {
                try {
                    server = new ControlServer(directory);
                } catch (IOException exception) {
                    System.err.println("[mc-agent-interface] cannot create control directory "
                            + directory + ": " + exception);
                    return null;
                }
                instance = server;
            }
            server.ops = ops;
            server.prepare();
            if (server.sslContext != null) {
                log("formal control ENABLED on the game port (TLS, credential-authenticated): controlDir="
                        + directory.toAbsolutePath() + " instanceId=" + server.instanceId
                        + " runId=" + server.runId + " fingerprint=" + server.tlsFingerprint);
            }
            return server;
        }
    }

    public static void detach() {
        synchronized (ATTACH_LOCK) {
            ControlServer server = instance;
            instance = null;
            if (server != null) {
                server.closeAll("control transport detached");
            }
        }
    }

    public static ControlServer current() {
        return instance;
    }

    /**
     * Arm the control sniffer on a real TCP server connection. The spike
     * sniffer (when enabled) is armed first, then this sniffer is put in front
     * of it: TLS is claimed before the spike ever sees a byte, and everything
     * else flows through to the spike or to vanilla.
     */
    public static void armChannel(ChannelPipeline pipeline, Connection connection) {
        SamePortControl.armChannel(pipeline, connection);
        ControlServer server = instance;
        if (!enabled() || server == null || server.sslContext == null) {
            return;
        }
        if (pipeline.context(SNIFFER_NAME) != null) {
            return;
        }
        pipeline.addFirst(SNIFFER_NAME, new ControlSniffer(server, connection));
    }

    /** Push one raw event line produced by the game into the sequenced stream. */
    public static void onEventLine(String line) {
        ControlServer server = instance;
        if (server == null || !enabled()) {
            return;
        }
        JsonObject event;
        try {
            event = JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException exception) {
            return;
        }
        server.publishEvent(event);
    }

    public static void closeAll(String reason) {
        ControlServer server = instance;
        if (server != null) {
            server.closeSessions(reason);
        }
    }

    /** Diagnostics for STATE and for integration evidence. */
    public static JsonObject state() {
        ControlServer server = instance;
        if (server == null) {
            JsonObject object = new JsonObject();
            object.addProperty("enabled", false);
            object.addProperty("transport", TRANSPORT);
            return object;
        }
        return server.stateJson();
    }

    static void log(String message) {
        System.out.println("[mc-agent-interface] " + message);
    }

    // --------------------------------------------------------------- lifecycle

    private void loadTls() {
        sslContext = null;
        tlsFingerprint = null;
        tlsProblem = null;
        ControlTls.Material material;
        try {
            material = ControlTls.prepare(directory);
        } catch (Exception exception) {
            tlsProblem = exception.getMessage();
            log("formal control is DISABLED: cannot prepare the TLS identity: " + exception);
            return;
        }
        try {
            sslContext = material.sslContext();
            tlsFingerprint = material.fingerprint;
            // Expose the public identity only once the listener can present it.
            ControlTls.writePublicIdentity(directory, material);
        } catch (Exception exception) {
            tlsProblem = exception.getMessage();
            log("formal control is DISABLED: cannot build the TLS context: " + exception);
        }
    }

    private void loadTokens() {
        try {
            ControlAuth.LoadReport report = auth.load();
            if (report.missing || report.loaded == 0) {
                // First start with no credentials: issue one bootstrap credential
                // and log it exactly once, like an install-time admin password.
                String secret = auth.issue("bootstrap",
                        java.util.Set.of(ControlAuth.PERMISSION_READ, ControlAuth.PERMISSION_WRITE), null);
                log("formal control: no usable credentials found; issued a bootstrap read+write credential");
                log("formal control: BOOTSTRAP SECRET (shown once, store it now): " + secret);
                log("formal control: revoke it after issuing per-daemon credentials (tokens live in "
                        + auth.file().toAbsolutePath() + ")");
            } else {
                log("formal control: loaded " + report.loaded + " credential(s) from " + auth.file());
            }
        } catch (IOException exception) {
            log("formal control: cannot read credentials: " + exception);
        }
    }

    /** Revoke a credential and close every live session that uses it. */
    public boolean revokeAndClose(String tokenId) throws IOException {
        boolean revoked = auth.revoke(tokenId);
        if (!revoked) {
            return false;
        }
        leases.releaseToken(tokenId);
        closeTokenSessions(java.util.List.of(tokenId), "credential revoked");
        return true;
    }

    /** Reload credentials and close sessions whose credential was revoked. */
    public List<String> reloadTokensAndCloseRevoked() throws IOException {
        List<String> revoked = auth.reloadAndFindRevoked();
        for (String tokenId : revoked) {
            leases.releaseToken(tokenId);
        }
        closeTokenSessions(revoked, "credential revoked or expired");
        return revoked;
    }

    private void closeTokenSessions(List<String> tokenIds, String reason) {
        if (tokenIds.isEmpty()) {
            return;
        }
        for (ControlSession session : new ArrayList<>(sessions.values())) {
            String tokenId = session.tokenId();
            if (tokenId != null && tokenIds.contains(tokenId)) {
                session.close(reason);
            }
        }
    }

    void closeSessions(String reason) {
        for (ControlSession session : new ArrayList<>(sessions.values())) {
            session.close(reason);
        }
        sessions.clear();
        synchronized (recentWrites) {
            for (WriteStatus status : recentWrites.values()) {
                if (status.state == WriteState.PENDING) {
                    status.state = WriteState.UNKNOWN;
                    status.updatedAtMillis = System.currentTimeMillis();
                }
            }
        }
    }

    void sessionOpened(ControlSession session) {
        sessions.put(session.channel().id(), session);
    }

    void sessionClosed(ControlSession session, String reason) {
        sessions.remove(session.channel().id());
    }

    void sessionRequestBegan(ControlSession session) {
        requestsStarted.incrementAndGet();
    }

    void sessionRequestFinished(ControlSession session) {
        requestsFinished.incrementAndGet();
    }

    // ------------------------------------------------------------------ config

    public Path directory() {
        return directory;
    }

    public ControlAuth auth() {
        return auth;
    }

    public ControlOps ops() {
        return ops;
    }

    SslContext sslContextForTest() {
        return sslContext;
    }

    public String instanceId() {
        return instanceId;
    }

    public String runId() {
        return runId;
    }

    public long runStartedAtMillis() {
        return runStartedAtMillis;
    }

    long handshakeSeconds() {
        return Long.getLong(PROPERTY_HANDSHAKE_SECONDS, 10L);
    }

    long idleSeconds() {
        return Long.getLong(PROPERTY_IDLE_SECONDS, 300L);
    }

    int maxFrameBytes() {
        return Integer.getInteger(PROPERTY_MAX_FRAME_BYTES, 16 * 1024 * 1024);
    }

    int eventBufferSize() {
        return Math.max(16, Integer.getInteger(PROPERTY_EVENT_BUFFER, 1024));
    }

    int maxPendingRequests() {
        return Math.max(1, Integer.getInteger(PROPERTY_MAX_PENDING, 32));
    }

    long defaultRequestTimeoutMillis() {
        return Math.max(1_000L, Long.getLong(PROPERTY_REQUEST_TIMEOUT_MILLIS, 30_000L));
    }

    long maxDroppedEvents() {
        return Math.max(16L, Long.getLong(PROPERTY_MAX_DROPPED, 4096L));
    }

    int maxAuthFailuresPerWindow() {
        return 10;
    }

    long authFailureWindowMillis() {
        return 60_000L;
    }

    // ------------------------------------------------------------------ auth

    boolean allowAuthAttempt(String remote) {
        FailureWindow window = authFailures.get(remote);
        if (window == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        synchronized (window) {
            if (window.blockedUntilMillis > now) {
                return false;
            }
            if (now - window.windowStartMillis > authFailureWindowMillis()) {
                window.count = 0;
                window.windowStartMillis = now;
            }
            return window.count < maxAuthFailuresPerWindow();
        }
    }

    void recordAuthFailure(String remote, ControlAuth.Status status) {
        authFailuresTotal.incrementAndGet();
        long now = System.currentTimeMillis();
        FailureWindow window = authFailures.computeIfAbsent(remote, key -> new FailureWindow(now));
        synchronized (window) {
            if (now - window.windowStartMillis > authFailureWindowMillis()) {
                window.count = 0;
                window.windowStartMillis = now;
            }
            window.count++;
            if (window.count >= maxAuthFailuresPerWindow()) {
                window.blockedUntilMillis = now + authFailureWindowMillis();
            }
        }
        log("control authentication rejected from " + remote + " (" + status + "), failure "
                + window.count + " in this window");
        if (authFailures.size() > 1024) {
            authFailures.entrySet().removeIf(entry ->
                    now - entry.getValue().windowStartMillis > 10 * authFailureWindowMillis());
        }
    }

    private static final class FailureWindow {
        long windowStartMillis;
        long blockedUntilMillis;
        int count;

        FailureWindow(long now) {
            this.windowStartMillis = now;
        }
    }

    JsonArray permissions(ControlAuth.Auth auth) {
        JsonArray array = new JsonArray();
        if (auth.canRead()) {
            array.add(ControlAuth.PERMISSION_READ);
        }
        if (auth.canWrite()) {
            array.add(ControlAuth.PERMISSION_WRITE);
        }
        return array;
    }

    JsonArray capabilityTokens() {
        return ops == null ? new JsonArray() : ops.capabilities().deepCopy();
    }

    /** Operations the connected vantage can serve and this credential may use. */
    boolean operationAllowed(String operation, ControlAuth.Auth auth) {
        if (operation == null || operation.isEmpty()) {
            return false;
        }
        if (operation.startsWith("exclusive_")) {
            return true;
        }
        switch (operation) {
            case "ping", "capabilities", "request_status":
                return true;
            default:
                return requiredToken(operation) != null && hasToken(requiredToken(operation));
        }
    }

    private boolean hasToken(String token) {
        if (ops == null) {
            return false;
        }
        for (JsonElement element : ops.capabilities()) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            String value = element.getAsString().toLowerCase(Locale.ROOT);
            if (value.equals(token)) {
                return true;
            }
            if ("player".equals(token) && "player:view".equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String requiredToken(String operation) {
        return switch (operation) {
            case "state" -> "state";
            case "entities" -> "entities";
            case "player", "player:view" -> "player";
            case "context" -> "context";
            case "command" -> "command";
            case "mark" -> "mark";
            case "wait" -> "wait";
            case "snapshot", "snapshots" -> "snapshot";
            default -> null;
        };
    }

    JsonObject capabilitiesReply() {
        JsonObject object = new JsonObject();
        object.addProperty("type", "capabilities");
        object.addProperty("control", CONTROL_PROTOCOL_VERSION);
        object.addProperty("transport", TRANSPORT);
        object.addProperty("mod", "mc-agent-interface");
        object.addProperty("modVersion", InterfaceConstants.VERSION);
        object.addProperty("minecraft", InterfaceConstants.MINECRAFT_VERSION);
        object.addProperty("instance", ops == null ? "server" : ops.instanceKind());
        object.addProperty("instanceId", instanceId);
        object.addProperty("runId", runId);
        object.add("capabilities", capabilityTokens());
        object.add("limits", limitsJson());
        return object;
    }

    JsonObject limitsJson() {
        JsonObject limits = new JsonObject();
        limits.addProperty("maxFrameBytes", maxFrameBytes());
        limits.addProperty("maxPendingRequests", maxPendingRequests());
        limits.addProperty("requestTimeoutMillis", defaultRequestTimeoutMillis());
        limits.addProperty("idleSeconds", idleSeconds());
        limits.addProperty("handshakeSeconds", handshakeSeconds());
        limits.addProperty("eventBuffer", eventBufferSize());
        limits.addProperty("maxDroppedEvents", maxDroppedEvents());
        limits.addProperty("controlProtocol", CONTROL_PROTOCOL_VERSION);
        return limits;
    }

    // ------------------------------------------------------------------ events

    void publishEvent(JsonObject rawEvent) {
        long seq = eventSequence.incrementAndGet();
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", "event");
        envelope.addProperty("seq", seq);
        envelope.addProperty("runId", runId);
        envelope.addProperty("streamId", runId);
        envelope.addProperty("serverTimeMillis", System.currentTimeMillis());
        envelope.add("event", rawEvent);
        synchronized (eventBuffer) {
            eventBuffer.addLast(envelope);
            while (eventBuffer.size() > eventBufferSize()) {
                eventBuffer.removeFirst();
            }
        }
        for (ControlSession session : sessions.values()) {
            session.sendEvent(envelope, false);
        }
    }

    /** What a reconnecting client gets for its last seen sequence. */
    record Replay(List<JsonObject> events, long from, long to, boolean lost) {
    }

    Replay replaySince(long lastSeq) {
        List<JsonObject> events = new ArrayList<>();
        long oldest;
        long newest = eventSequence.get();
        synchronized (eventBuffer) {
            oldest = eventBuffer.isEmpty() ? newest + 1 : eventBuffer.peekFirst().get("seq").getAsLong();
            for (JsonObject envelope : eventBuffer) {
                if (envelope.get("seq").getAsLong() > lastSeq) {
                    events.add(envelope);
                }
            }
        }
        long from = lastSeq + 1;
        boolean lost = newest > 0 && from < oldest;
        if (lost) {
            from = oldest;
        }
        return new Replay(events, from, newest, lost);
    }

    // ------------------------------------------------------------- write audit

    public enum WriteState {
        PENDING,
        OK,
        FAIL,
        UNKNOWN
    }

    /** The server-side record that makes result-unknown recoverable. */
    public static final class WriteStatus {
        public final String tokenId;
        public final String requestId;
        public final String operation;
        public final long beganAtMillis;
        public volatile WriteState state;
        public volatile JsonObject result;
        public volatile JsonObject error;
        public volatile long updatedAtMillis;

        WriteStatus(String tokenId, String requestId, String operation, long now) {
            this.tokenId = tokenId;
            this.requestId = requestId;
            this.operation = operation;
            this.beganAtMillis = now;
            this.updatedAtMillis = now;
        }

        public JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("requestId", requestId);
            object.addProperty("operation", operation);
            object.addProperty("state", switch (state) {
                case PENDING -> "pending";
                case OK -> "completed";
                case FAIL -> "failed";
                case UNKNOWN -> "unknown";
            });
            object.addProperty("beganAtMillis", beganAtMillis);
            object.addProperty("updatedAtMillis", updatedAtMillis);
            if (result != null) {
                object.add("result", result);
            }
            if (error != null) {
                object.add("error", error);
            }
            return object;
        }
    }

    void writeBegan(String tokenId, String requestId, String operation) {
        synchronized (recentWrites) {
            recentWrites.put(key(tokenId, requestId),
                    new WriteStatus(tokenId, requestId, operation, System.currentTimeMillis()));
            evictWrites();
        }
    }

    long writeCompleted(String tokenId, String requestId, String operation, boolean ok,
                        JsonObject result, JsonObject error) {
        long writeSeq = writeSequence.incrementAndGet();
        synchronized (recentWrites) {
            WriteStatus status = recentWrites.get(key(tokenId, requestId));
            if (status == null) {
                status = new WriteStatus(tokenId, requestId, operation, System.currentTimeMillis());
                recentWrites.put(key(tokenId, requestId), status);
            }
            status.state = ok ? WriteState.OK : WriteState.FAIL;
            status.result = result;
            status.error = error;
            status.updatedAtMillis = System.currentTimeMillis();
            evictWrites();
        }
        JsonObject writeEvent = new JsonObject();
        writeEvent.addProperty("type", "write");
        writeEvent.addProperty("writeSeq", writeSeq);
        writeEvent.addProperty("op", operation);
        writeEvent.addProperty("requestId", requestId);
        writeEvent.addProperty("tokenId", tokenId);
        writeEvent.addProperty("ok", ok);
        writeEvent.addProperty("millis", System.currentTimeMillis());
        publishEvent(writeEvent);
        return writeSeq;
    }

    WriteStatus writeStatus(String tokenId, String requestId) {
        synchronized (recentWrites) {
            WriteStatus status = recentWrites.get(key(tokenId, requestId));
            if (status == null) {
                return null;
            }
            if (System.currentTimeMillis() - status.updatedAtMillis > 30 * 60_000L) {
                recentWrites.remove(key(tokenId, requestId));
                return null;
            }
            return status;
        }
    }

    /** A write that timed out before the game thread saw it; a retry re-executes. */
    void writeAbandoned(String tokenId, String requestId) {
        synchronized (recentWrites) {
            recentWrites.remove(key(tokenId, requestId));
        }
    }

    private void evictWrites() {
        if (recentWrites.size() <= 1024) {
            return;
        }
        var iterator = recentWrites.entrySet().iterator();
        while (iterator.hasNext() && recentWrites.size() > 1024) {
            iterator.next();
            iterator.remove();
        }
    }

    private static String key(String tokenId, String requestId) {
        return tokenId + "\u0000" + requestId;
    }

    // ------------------------------------------------------------------- state

    JsonObject stateJson() {
        JsonObject object = new JsonObject();
        object.addProperty("enabled", enabled() && sslContext != null);
        object.addProperty("transport", TRANSPORT);
        object.addProperty("controlProtocol", CONTROL_PROTOCOL_VERSION);
        object.addProperty("instanceId", instanceId);
        object.addProperty("runId", runId);
        object.addProperty("runStartedAtMillis", runStartedAtMillis);
        object.addProperty("tlsFingerprint", sslContext == null ? null : tlsFingerprint);
        object.addProperty("tlsProblem", tlsProblem);
        object.addProperty("controlDir", directory.toAbsolutePath().toString());
        JsonArray open = new JsonArray();
        for (ControlSession session : sessions.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("sessionId", session.sessionId());
            entry.addProperty("tokenId", session.tokenId());
            entry.addProperty("remote", session.remote());
            entry.addProperty("welcomed", session.welcomed());
            entry.addProperty("droppedEvents", session.droppedEvents());
            open.add(entry);
        }
        object.addProperty("sessions", open.size());
        object.add("open", open);
        object.addProperty("eventSeq", eventSequence.get());
        synchronized (eventBuffer) {
            object.addProperty("eventBuffer", eventBuffer.size());
        }
        object.addProperty("tokens", auth.size());
        object.addProperty("authFailures", authFailuresTotal.get());
        object.addProperty("requestsStarted", requestsStarted.get());
        object.addProperty("requestsFinished", requestsFinished.get());
        object.addProperty("writeSeq", writeSequence.get());
        object.addProperty("droppedEvents", droppedEventsTotal.get());
        object.add("leases", leases.toJson(System.currentTimeMillis()));
        return object;
    }

    ControlSession sessionFor(ChannelId id) {
        return sessions.get(id);
    }

    // ------------------------------------------------------------------ sniffer

    /** First handler on a candidate connection; decides TLS or passthrough. */
    static final class ControlSniffer extends ByteToMessageDecoder {
        private final ControlServer server;
        private final Connection connection;
        private boolean adopted;
        private boolean passedThrough;

        ControlSniffer(ControlServer server, Connection connection) {
            this.server = server;
            this.connection = connection;
        }

        @Override
        protected void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out) {
            if (passedThrough || adopted) {
                if (in.isReadable()) {
                    out.add(in.readRetainedSlice(in.readableBytes()));
                }
                return;
            }
            if (in.readableBytes() < 2) {
                return;
            }
            byte first = in.getByte(in.readerIndex());
            byte second = in.getByte(in.readerIndex() + 1);
            if (first == 0x16 && second == 0x03) {
                adopted = true;
                server.adopt(context, connection, in, out);
                return;
            }
            passedThrough = true;
            out.add(in.readRetainedSlice(in.readableBytes()));
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            super.channelInactive(context);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            context.close();
        }
    }

    /** Take over the pipeline: detach the placeholder player, install TLS + session. */
    private void adopt(ChannelHandlerContext context, Connection connection, ByteBuf in, List<Object> out) {
        ChannelPipeline pipeline = context.pipeline();
        SamePortControl.detachPlaceholder(connection);
        List<String> names = new ArrayList<>(pipeline.names());
        for (String name : names) {
            if (SNIFFER_NAME.equals(name) || "head".equals(name) || "tail".equals(name)) {
                continue;
            }
            if (pipeline.context(name) != null) {
                pipeline.remove(name);
            }
        }
        SslHandler ssl = sslContext.newHandler(context.alloc());
        long handshakeSeconds = handshakeSeconds();
        if (handshakeSeconds > 0) {
            ssl.setHandshakeTimeoutMillis(handshakeSeconds * 1000L);
        }
        ControlSession session = new ControlSession(this, context.channel());
        pipeline.addLast(SSL_NAME, ssl);
        long idle = idleSeconds();
        if (idle > 0) {
            pipeline.addLast(IDLE_NAME, new ReadTimeoutHandler(idle, TimeUnit.SECONDS));
        }
        pipeline.addLast(SESSION_NAME, session);
        context.channel().config().setWriteBufferWaterMark(
                new io.netty.channel.WriteBufferWaterMark(64 * 1024, 512 * 1024));
        sessions.put(context.channel().id(), session);
        log("control connection from " + context.channel().remoteAddress()
                + " is TLS; vanilla pipeline replaced, session=" + session.sessionId());
        // The ClientHello bytes were consumed for sniffing; hand them to the
        // SslHandler through the pipeline now that it is installed.
        out.add(in.readRetainedSlice(in.readableBytes()));
    }

    // ------------------------------------------------------------------ storage

    private String loadOrCreateInstanceId(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            try {
                JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                String existing = object.get("instanceId").getAsString();
                if (existing != null && !existing.isBlank()) {
                    return existing;
                }
            } catch (RuntimeException ignored) {
                log("control instance file is unreadable; generating a new instance id");
            }
        }
        String generated = "inst_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        JsonObject object = new JsonObject();
        object.addProperty("instanceId", generated);
        object.addProperty("createdAtMillis", System.currentTimeMillis());
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, object.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return generated;
    }
}
