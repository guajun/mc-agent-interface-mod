package dev.mcagent.interfacemod.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline tests for the issue #8 control protocol: credentials, leases, the
 * TLS identity, the sniff decision, and a real TLS loopback session that
 * exercises welcome/replay, request routing, write de-duplication,
 * request_status, permissions, revocation, and frame limits.
 *
 * <p>No Minecraft process is involved: the game-facing side is a stub
 * {@link ControlOps}, exactly like the daemon sees it on the wire.
 */
public final class ControlProtocolTests {
    private int checks;

    public void run() throws Exception {
        authLifecycle();
        leasesLifecycle();
        tlsIdentity();
        snifferDecision();
        endToEndTls();
        System.out.println("control protocol: " + checks + " checks passed");
    }

    // ------------------------------------------------------------------- auth

    private void authLifecycle() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-auth-");
        ControlAuth auth = new ControlAuth(dir.resolve("tokens.json"));
        check(auth.load().missing, "a missing token file is an empty store");
        String secret = auth.issue("laptop", Set.of("read", "write"), null);
        check(secret.startsWith("mca1.tk_"), "issued secret uses the documented prefix");
        check(!Files.readString(auth.file()).contains(secret), "the secret is never stored");

        ControlAuth.Result ok = auth.authenticate(secret, System.currentTimeMillis());
        check(ok.status == ControlAuth.Status.OK, "issued secret authenticates");
        check(ok.auth.canRead() && ok.auth.canWrite(), "permissions survive authentication");
        check(ok.auth.label.equals("laptop"), "label survives authentication");

        check(auth.authenticate(secret + "x", System.currentTimeMillis()).status
                == ControlAuth.Status.BAD_SECRET, "a modified secret is rejected");
        check(auth.authenticate("not-a-token", System.currentTimeMillis()).status
                == ControlAuth.Status.MALFORMED, "a malformed token is rejected");
        check(auth.authenticate("mca1.tk_zzz.AAAA", System.currentTimeMillis()).status
                == ControlAuth.Status.UNKNOWN_TOKEN, "an unknown id is rejected");

        // Persistence: a second store sees the same credential.
        ControlAuth reloaded = new ControlAuth(auth.file());
        reloaded.load();
        check(reloaded.size() == 1, "credential persisted");
        check(reloaded.authenticate(secret, System.currentTimeMillis()).status == ControlAuth.Status.OK,
                "persisted credential authenticates");

        // Expiry.
        String expiring = auth.issue("expiring", Set.of("read"), 1L);
        Thread.sleep(1_100L);
        check(auth.authenticate(expiring, System.currentTimeMillis()).status
                == ControlAuth.Status.EXPIRED, "expired credential is rejected");

        // Revocation and reload detection.
        String victimId = ok.auth.tokenId;
        check(auth.revoke(victimId), "revoke finds the credential");
        check(auth.authenticate(secret, System.currentTimeMillis()).status
                == ControlAuth.Status.REVOKED, "revoked credential is rejected");
        boolean rejectedEmpty = false;
        try {
            auth.issue("bad", Set.of(), null);
        } catch (IllegalArgumentException expected) {
            rejectedEmpty = true;
        }
        check(rejectedEmpty, "a credential without permissions is rejected at issue time");
    }

    // ----------------------------------------------------------------- leases

    private void leasesLifecycle() {
        ControlLeases leases = new ControlLeases();
        long now = 1_000_000L;
        ControlLeases.Acquire first = leases.acquire("freeze", "token-a", "sess-a", "daemon-a", 60_000L, now);
        check(first.acquired, "first acquisition succeeds");
        check(first.lease.renewals == 0, "fresh lease has no renewals");

        ControlLeases.Acquire conflict = leases.acquire("freeze", "token-b", "sess-b", "daemon-b",
                60_000L, now + 1_000L);
        check(!conflict.acquired, "second credential conflicts");
        check(conflict.holder != null && conflict.holder.tokenId.equals("token-a"), "conflict names the holder");

        ControlLeases.Acquire again = leases.acquire("freeze", "token-a", "sess-a2", "daemon-a", 60_000L,
                now + 2_000L);
        check(again.acquired && again.lease.renewals == 1, "the holder may renew through acquire");
        check(leases.lookup("freeze", "token-b", now + 2_000L).mine == false,
                "another token never owns the lease");
        check(leases.lookup("freeze", "token-a", now + 2_000L).mine, "the holder owns the lease");

        check(leases.release("freeze", "token-b", now + 3_000L) == ControlLeases.ReleaseStatus.NOT_HOLDER,
                "release by a foreign token is refused");
        check(leases.release("freeze", "token-a", now + 3_000L) == ControlLeases.ReleaseStatus.RELEASED,
                "the holder can release");
        check(leases.renew("freeze", "token-a", 60_000L, now + 3_000L) == null,
                "renew after release reports not found");

        leases.acquire("frozen", "token-a", "sess-a", "", 1_000L, now);
        check(leases.list(now + 2_000L).isEmpty(), "an expired lease disappears");
        leases.acquire("frozen", "token-a", "sess-a", "", 60_000L, now);
        leases.acquire("other", "token-a", "sess-a", "", 60_000L, now);
        check(leases.releaseToken("token-a") == 2, "releaseToken drops every lease of a revoked credential");
        check(leases.list(now).isEmpty(), "no leases remain after token release");
    }

    // -------------------------------------------------------------------- TLS

    private void tlsIdentity() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-tls-");
        ControlServer server = new ControlServer(dir);
        server.prepare();
        Path certificate = dir.resolve("server.crt");
        Path fingerprint = dir.resolve("fingerprint.txt");
        check(Files.isRegularFile(certificate), "server certificate written");
        check(Files.isRegularFile(fingerprint), "fingerprint written");
        check(Files.isRegularFile(dir.resolve("control.p12")), "generated keystore exists");
        String recorded = Files.readString(fingerprint).trim();
        check(recorded.startsWith("sha256:"), "fingerprint uses the sha256: form");
        check(server.sslContextForTest() != null, "a TLS context was built");
        check(server.stateJson().get("tlsFingerprint").getAsString().equals(recorded),
                "state reports the pinned fingerprint");
        String pem = Files.readString(certificate);
        check(pem.contains("BEGIN CERTIFICATE"), "server.crt is a PEM certificate");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate parsed = (X509Certificate) factory.generateCertificate(
                new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        check(parsed.getSubjectX500Principal().getName().contains("mc-agent-control"),
                "certificate subject is the control identity");
        check(ControlTls.fingerprint(parsed).equals(recorded), "fingerprint matches the certificate");
        check(!parsed.getSubjectAlternativeNames().isEmpty(), "certificate carries SAN entries");
        // A second prepare reuses the same identity instead of regenerating it.
        ControlServer again = new ControlServer(dir);
        again.prepare();
        check(again.stateJson().get("tlsFingerprint").getAsString().equals(recorded),
                "the identity is stable across restarts");
    }

    private void snifferDecision() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-sniffer-");
        ControlServer server = new ControlServer(dir);
        server.prepare();

        // Vanilla bytes pass through untouched, in the same order.
        EmbeddedChannel vanilla = new EmbeddedChannel(
                new ControlServer.ControlSniffer(server, null));
        byte[] handshake = new byte[] {0x10, 0x00, 0x2f, 0x0a, 'h', 'o', 's', 't'};
        vanilla.writeInbound(Unpooled.copiedBuffer(handshake));
        ByteBuf passed = vanilla.readInbound();
        check(passed != null && passed.readableBytes() == handshake.length,
                "vanilla bytes are forwarded in full");
        byte[] replayed = new byte[handshake.length];
        passed.readBytes(replayed);
        passed.release();
        check(java.util.Arrays.equals(handshake, replayed), "vanilla bytes are forwarded byte for byte");
        vanilla.finishAndReleaseAll();

        // A TLS record header installs the SSL handler and the session handler.
        EmbeddedChannel tls = new EmbeddedChannel(
                new ControlServer.ControlSniffer(server, null));
        tls.writeInbound(Unpooled.copiedBuffer(new byte[] {0x16, 0x03, 0x01, 0x00, 0x40}));
        ChannelPipeline pipeline = tls.pipeline();
        check(pipeline.get(ControlServer.SSL_NAME) != null, "TLS sniffer installs an SslHandler");
        check(pipeline.get(ControlServer.SESSION_NAME) != null, "TLS sniffer installs a control session");
        tls.finishAndReleaseAll();
    }

    // ------------------------------------------------------------------- e2e

    private void endToEndTls() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-e2e-");
        System.setProperty(ControlServer.PROPERTY_HANDSHAKE_SECONDS, "5");
        ControlServer server = new ControlServer(dir);
        StubOps ops = new StubOps();
        server.setOps(ops);
        server.prepare();
        check(server.sslContextForTest() != null, "e2e TLS context available");

        SSLContext clientContext = clientContext(dir.resolve("server.crt"));
        SSLSocketFactory factory = clientContext.getSocketFactory();

        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel listener = null;
        try {
            listener = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(ControlServer.SSL_NAME,
                                    server.sslContextForTest().newHandler(channel.alloc()));
                            channel.pipeline().addLast(ControlServer.SESSION_NAME,
                                    new ControlSession(server, channel));
                        }
                    })
                    .bind("127.0.0.1", 0).sync().channel();
            int port = ((InetSocketAddress) listener.localAddress()).getPort();

            String writeToken = server.auth().issue("e2e-write", Set.of("read", "write"), null);
            String readToken = server.auth().issue("e2e-read", Set.of("read"), null);

            // Wrong credential: a generic fatal error, then close.
            try (FrameClient client = new FrameClient(factory, port)) {
                client.send(hello("mca1.tk_nope.AAAA", 0));
                JsonObject error = client.readNext(5000);
                check(error.get("type").getAsString().equals("error"), "bad credential gets a fatal error frame");
                check(error.get("code").getAsString().equals("unauthorized"), "bad credential is unauthorized");
                check(client.isClosed(5000), "bad credential connection is closed");
            }

            try (FrameClient client = new FrameClient(factory, port)) {
                client.send(hello(writeToken, 0));
                JsonObject welcome = client.readNext(5000);
                check(welcome.get("type").getAsString().equals("welcome"), "valid credential gets a welcome");
                check(welcome.get("protocol").getAsInt() == ControlServer.CONTROL_PROTOCOL_VERSION,
                        "welcome carries the protocol version");
                check(welcome.get("instanceId").getAsString().equals(server.instanceId()),
                        "welcome carries the instance id");
                check(welcome.get("runId").getAsString().equals(server.runId()), "welcome carries the run id");
                check(welcome.get("sessionId").getAsString().startsWith("sess_"), "welcome carries a session id");
                check(welcome.getAsJsonArray("permissions").toString().contains("write"),
                        "welcome carries permissions");
                check(welcome.getAsJsonObject("limits").get("maxFrameBytes").getAsInt() > 0,
                        "welcome carries limits");
                check(!welcome.getAsJsonObject("replay").get("lost").getAsBoolean(), "a fresh client has no gap");

                JsonObject pong = request(client, "p1", "ping", new JsonObject(), 5000);
                check(pong.getAsJsonObject("result").get("pong").getAsBoolean(), "ping replies with pong");

                // Events are sequenced and pushed to the live session.
                JsonObject event = new JsonObject();
                event.addProperty("type", "mark");
                event.addProperty("text", "e2e");
                server.publishEvent(event);
                JsonObject pushed = client.readNext(5000);
                check("event".equals(pushed.get("type").getAsString()), "live event is pushed");
                check(pushed.get("seq").getAsLong() == 1L, "the first event has seq 1");
                check(pushed.getAsJsonObject("event").get("text").getAsString().equals("e2e"),
                        "event payload survives");

                // A write op: writeSeq, de-duplication, and request_status.
                JsonObject params = new JsonObject();
                params.addProperty("command", "say hello");
                JsonObject first = request(client, "c1", "command", params, 5000);
                check(first.getAsJsonObject("result").has("writeSeq"), "write result has a writeSeq");
                long writeSeq = first.getAsJsonObject("result").get("writeSeq").getAsLong();
                JsonObject duplicate = request(client, "c1", "command", params, 5000);
                check(duplicate.getAsJsonObject("result").get("duplicate").getAsBoolean(),
                        "a repeated request id is de-duplicated");
                JsonObject statusParams = new JsonObject();
                statusParams.addProperty("requestId", "c1");
                JsonObject status = request(client, "s1", "request_status", statusParams, 5000);
                check(status.getAsJsonObject("result").get("state").getAsString().equals("completed"),
                        "request_status reports the completed write");
                check(status.getAsJsonObject("result").get("result").getAsJsonObject()
                        .get("writeSeq").getAsLong() == writeSeq, "request_status returns the stored result");

                JsonObject writeEvent = waitForEvent(client, "write", 5000);
                check(writeEvent.getAsJsonObject("event").get("writeSeq").getAsLong() == writeSeq,
                        "write completion is an ordered event");

                // The unsupported composite operations are refused by capability,
                // not silently attempted.
                JsonObject unsupported = request(client, "u1", "fork", new JsonObject(), 5000);
                check(unsupported.get("ok").getAsBoolean() == false, "fork is refused");
                check(unsupported.getAsJsonObject("error").get("code").getAsString()
                        .equals("capability_not_supported"), "fork is refused as an unsupported capability");

                // An exclusive lease held by this token.
                JsonObject leaseParams = new JsonObject();
                leaseParams.addProperty("key", "freeze");
                JsonObject lease = request(client, "l1", "exclusive_acquire", leaseParams, 5000);
                check(lease.getAsJsonObject("result").get("acquired").getAsBoolean(), "lease acquired");
                JsonObject held = request(client, "l2", "exclusive_status", leaseParams, 5000);
                check(held.getAsJsonObject("result").get("mine").getAsBoolean(), "status shows our lease");
            }

            // Read-only credential: reads work, writes are forbidden.
            String readTokenId = server.auth().entries().stream()
                    .filter(entry -> entry.label.equals("e2e-read"))
                    .findFirst().orElseThrow().id;
            try (FrameClient reader = new FrameClient(factory, port)) {
                reader.send(hello(readToken, server.stateJson().get("eventSeq").getAsLong()));
                reader.readNext(5000);
                JsonObject state = request(reader, "r1", "state", new JsonObject(), 5000);
                check(state.get("ok").getAsBoolean(), "read-only credential can read");
                JsonObject denied = request(reader, "r2", "command", new JsonObject(), 5000);
                check(!denied.get("ok").getAsBoolean(), "read-only credential cannot write");
                check(denied.getAsJsonObject("error").get("code").getAsString().equals("forbidden"),
                        "write refusal is an explicit forbidden error");
                check(server.auth().entries().stream().anyMatch(entry -> entry.id.equals(readTokenId)),
                        "read-only token exists for later revocation");
            }

            // Revocation closes live sessions that use the credential.
            String writeTokenId = server.auth().entries().stream()
                    .filter(entry -> entry.label.equals("e2e-write"))
                    .findFirst().orElseThrow().id;
            try (FrameClient victim = new FrameClient(factory, port)) {
                victim.send(hello(writeToken, server.stateJson().get("eventSeq").getAsLong()));
                victim.readNext(5000);
                server.revokeAndClose(writeTokenId);
                check(victim.isClosed(5000), "revocation closes the live session");
                try (FrameClient rejected = new FrameClient(factory, port)) {
                    rejected.send(hello(writeToken, 0));
                    JsonObject error = rejected.readNext(5000);
                    check("error".equals(error.get("type").getAsString()), "revoked credential is refused");
                }
            }

            // Replay: a reconnect with lastSeq gets the gap metadata and the
            // buffered events it missed.
            long newest = server.stateJson().get("eventSeq").getAsLong();
            try (FrameClient replay = new FrameClient(factory, port)) {
                replay.send(hello(readToken, 1));
                JsonObject welcome = replay.readNext(5000);
                check(!welcome.getAsJsonObject("replay").get("lost").getAsBoolean(),
                        "a sequence inside the buffer is not a gap");
                check(welcome.getAsJsonObject("replay").get("from").getAsLong() == 2L,
                        "replay starts after the last seen sequence");
                boolean sawReplay = false;
                while (true) {
                    JsonObject frame = replay.readNext(5000);
                    if ("event".equals(frame.get("type").getAsString()) && frame.has("replay")) {
                        sawReplay = true;
                        break;
                    }
                }
                check(sawReplay, "buffered events are replayed with a replay marker");
            }
            check(newest >= 2, "the write completion was sequenced");

            // Frame limits: an oversized declared length is fatal and closed.
            try (FrameClient oversized = new FrameClient(factory, port)) {
                oversized.send(hello(readToken, server.stateJson().get("eventSeq").getAsLong()));
                oversized.readNext(5000);
                oversized.sendDeclaredLength(Integer.MAX_VALUE);
                JsonObject error = oversized.readNext(5000);
                check("error".equals(error.get("type").getAsString()), "oversized frame gets a fatal error");
                check(error.get("code").getAsString().equals("frame_too_large"), "oversized frame error code");
                check(oversized.isClosed(5000), "oversized frame closes the session");
            }
        } finally {
            if (listener != null) {
                listener.close().syncUninterruptibly();
            }
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static SSLContext clientContext(Path certificate) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate parsed;
        try (var input = Files.newInputStream(certificate)) {
            parsed = (X509Certificate) factory.generateCertificate(input);
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("control", parsed);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private static JsonObject hello(String token, long lastSeq) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "hello");
        frame.addProperty("protocol", ControlServer.CONTROL_PROTOCOL_VERSION);
        frame.addProperty("token", token);
        frame.addProperty("lastSeq", lastSeq);
        JsonObject client = new JsonObject();
        client.addProperty("name", "control-protocol-tests");
        client.addProperty("version", "1");
        frame.add("client", client);
        return frame;
    }

    private static JsonObject request(FrameClient client, String id, String op, JsonObject params,
                                      long timeoutMillis) throws IOException {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "request");
        frame.addProperty("id", id);
        frame.addProperty("op", op);
        frame.add("params", params);
        client.send(frame);
        while (true) {
            JsonObject reply = client.readNext(timeoutMillis);
            if ("reply".equals(reply.get("type").getAsString())
                    && id.equals(reply.get("id").getAsString())) {
                return reply;
            }
            client.collected.add(reply);
        }
    }

    private static JsonObject waitForEvent(FrameClient client, String eventType, long timeoutMillis)
            throws IOException {
        for (java.util.Iterator<JsonObject> iterator = client.collected.iterator(); iterator.hasNext(); ) {
            JsonObject frame = iterator.next();
            if ("event".equals(frame.get("type").getAsString())
                    && eventType.equals(frame.getAsJsonObject("event").get("type").getAsString())) {
                iterator.remove();
                return frame;
            }
        }
        while (true) {
            JsonObject frame = client.readNext(timeoutMillis);
            if ("event".equals(frame.get("type").getAsString())
                    && eventType.equals(frame.getAsJsonObject("event").get("type").getAsString())) {
                return frame;
            }
        }
    }

    /** A minimal framed TLS client for the tests. */
    private static final class FrameClient implements AutoCloseable {
        private final SSLSocket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final java.util.List<JsonObject> collected = new java.util.ArrayList<>();

        FrameClient(SSLSocketFactory factory, int port) throws IOException {
            socket = (SSLSocket) factory.createSocket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            socket.startHandshake();
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        }

        void send(JsonObject frame) throws IOException {
            byte[] payload = frame.toString().getBytes(StandardCharsets.UTF_8);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        }

        void sendDeclaredLength(int length) throws IOException {
            out.writeInt(length);
            out.flush();
        }

        JsonObject readNext(long timeoutMillis) throws IOException {
            socket.setSoTimeout((int) timeoutMillis);
            int length = in.readInt();
            if (length <= 0 || length > 1024 * 1024) {
                throw new IOException("unexpected frame length " + length);
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            return JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        boolean isClosed(long timeoutMillis) {
            try {
                socket.setSoTimeout((int) timeoutMillis);
                int value = in.read();
                return value < 0;
            } catch (EOFException exception) {
                return true;
            } catch (IOException exception) {
                return true;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** The game-facing stub: command completes asynchronously, everything else immediately. */
    private static final class StubOps implements ControlOps {
        private final CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
        private final AtomicReference<JsonObject> lastParams = new AtomicReference<>();

        @Override
        public void execute(String operation, JsonObject params, Reply reply) {
            seen.add(operation);
            lastParams.set(params);
            if ("command".equals(operation)) {
                Thread worker = new Thread(() -> {
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    reply.started();
                    JsonObject result = new JsonObject();
                    result.addProperty("type", "cmd_ack");
                    JsonArray output = new JsonArray();
                    output.add("hello");
                    result.add("output", output);
                    reply.ok(result);
                }, "control-test-command");
                worker.setDaemon(true);
                worker.start();
                return;
            }
            reply.started();
            JsonObject result = new JsonObject();
            result.addProperty("type", operation);
            result.addProperty("stub", true);
            reply.ok(result);
        }

        @Override
        public JsonArray capabilities() {
            return JsonParser.parseString(
                    "[\"state\",\"entities\",\"player\",\"player:view\",\"command\",\"context\","
                            + "\"wait\",\"mark\",\"snapshot\",\"events:game\",\"events:chat\"]")
                    .getAsJsonArray();
        }

        @Override
        public String instanceKind() {
            return "server";
        }
    }

    private void check(boolean condition, String description) {
        checks++;
        if (!condition) {
            throw new AssertionError("control protocol check failed: " + description);
        }
    }
}
