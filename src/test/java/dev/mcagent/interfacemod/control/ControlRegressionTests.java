package dev.mcagent.interfacemod.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Review-R1 regressions for the control transport: atomic write reservation,
 * queued-vs-inflight cancellation, crash-safe status, live credential checks,
 * read/permission gating, bounded outbound queues, event ordering and the
 * snapshot path rules.
 */
public final class ControlRegressionTests {
    private int checks;

    public void run() throws Exception {
        snapshotPathRules();
        bootstrapSecretIsNotLogged();
        atomicWriteReservation();
        queuedTimeoutCancelsExecution();
        inflightDisconnectStillRecordsOutcome();
        writeOnlyCredentialCannotRead();
        liveExpiryClosesSession();
        reloadDowngradeClosesSessionAndBadFileKeepsStore();
        queuedWriteDoesNotRunAfterRevoke();
        slowConsumerClosesSession();
        eventOrderingIsAtomic();
        crossRunReplayIsReported();
        pendingStateMachineRace();
        duplicatePendingIds();
        outboundBudgetCapsScheduledFrames();
        oversizedReplyBecomesStructuredError();
        System.out.println("control regressions: " + checks + " checks passed");
    }

    private void check(boolean condition, String description) {
        checks++;
        if (!condition) {
            throw new AssertionError("control regression failed: " + description);
        }
    }

    // ------------------------------------------------------------------ paths

    private void snapshotPathRules() throws Exception {
        for (String bad : new String[] {"..", ".", "a/b", "a\\b", "C:\\abs", "/abs", "con", "a..", ""}) {
            boolean rejected = false;
            try {
                ControlPaths.requireSafeSnapshotName(bad);
            } catch (IllegalArgumentException exception) {
                rejected = true;
            }
            check(rejected, "snapshot name " + bad + " must be rejected");
        }
        check(ControlPaths.requireSafeSnapshotName("snap-1").equals("snap-1"), "a normal name is kept");
        Path serverDir = Files.createTempDirectory("mcagent-snap-");
        Path root = serverDir.resolve("snapshots");
        Files.createDirectories(root);
        Path candidate = ControlPaths.snapshotDirectory(serverDir, "good");
        check(candidate.getParent().equals(root.toAbsolutePath().normalize()), "good name stays in root");
        Path outside = Files.createTempDirectory("mcagent-outside-");
        Path link = root.resolve("evil");
        boolean symlinkCreated = false;
        try {
            Files.createSymbolicLink(link, outside);
            symlinkCreated = true;
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows without developer mode; the lexical rules still apply.
        }
        if (symlinkCreated) {
            boolean refused = false;
            try {
                ControlPaths.snapshotDirectory(serverDir, "evil");
            } catch (IOException | IllegalArgumentException exception) {
                refused = true;
            }
            check(refused, "a symlinked snapshot directory is refused");
        } else {
            check(ControlPaths.requireSafeSnapshotName("evil").equals("evil"),
                    "symlink test skipped where unsupported");
        }
    }

    private void bootstrapSecretIsNotLogged() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-bootstrap-");
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        String secret;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            ControlServer server = new ControlServer(dir);
            server.prepare();
            secret = Files.readString(dir.resolve("bootstrap-token.txt"), StandardCharsets.UTF_8).trim();
        } finally {
            System.setOut(original);
        }
        check(secret.startsWith("mca1."), "bootstrap credential written to the owner-only file");
        check(!captured.toString(StandardCharsets.UTF_8).contains(secret),
                "the bootstrap secret must never appear in normal logs");
        check(captured.toString(StandardCharsets.UTF_8).contains("bootstrap-token.txt"),
                "the log points at the credential file instead of printing the secret");
    }

    // ------------------------------------------------------------- test server

    private static final class TestServer implements AutoCloseable {
        final ControlServer server;
        final CountDownLatch commandGate;
        final CountDownLatch inflightGate;
        final CountDownLatch commandStarted = new CountDownLatch(1);
        final AtomicInteger executed = new AtomicInteger();
        final long largeResultBytes;
        // Optional barriers for deterministic claim/cancel races.
        volatile CountDownLatch claimReady;
        volatile CountDownLatch claimGo;
        volatile CountDownLatch preClaimGate;
        final java.util.concurrent.ExecutorService gameThread =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        EventLoopGroup group;
        Channel listener;
        int port;

        TestServer(Path dir, CountDownLatch commandGate, CountDownLatch inflightGate,
                   long largeResultBytes) throws Exception {
            this.commandGate = commandGate;
            this.inflightGate = inflightGate;
            this.largeResultBytes = largeResultBytes;
            this.server = new ControlServer(dir);
            this.server.setOps(new CountingOps());
            this.server.prepare();
            this.group = new NioEventLoopGroup(1);
            this.listener = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(512, 2048));
                            channel.pipeline().addLast(ControlServer.SSL_NAME,
                                    server.sslContextForTest().newHandler(channel.alloc()));
                            channel.pipeline().addLast(ControlServer.SESSION_NAME,
                                    new ControlSession(server, channel));
                        }
                    })
                    .bind("127.0.0.1", 0).sync().channel();
            this.port = ((InetSocketAddress) listener.localAddress()).getPort();
        }

        private final class CountingOps implements ControlOps {
            @Override
            public void execute(String operation, JsonObject params, Reply reply) {
                // Emulate MinecraftServer.execute: the network thread queues,
                // a single game thread runs operations in order.
                gameThread.submit(() -> runOnGameThread(operation, params, reply));
            }

            private void runOnGameThread(String operation, JsonObject params, Reply reply) {
                CountDownLatch readyBarrier = claimReady;
                if (readyBarrier != null) {
                    readyBarrier.countDown();
                }
                CountDownLatch goBarrier = claimGo;
                if (goBarrier != null) {
                    try {
                        goBarrier.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                CountDownLatch preGate = preClaimGate;
                if (preGate != null) {
                    try {
                        preGate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                if ("command".equals(operation)) {
                    try {
                        if (commandGate != null) {
                            commandGate.await(10, TimeUnit.SECONDS);
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    // The game-thread claim decides whether the queued task runs.
                    if (!reply.started()) {
                        return;
                    }
                    commandStarted.countDown();
                    try {
                        if (inflightGate != null) {
                                                        inflightGate.await(10, TimeUnit.SECONDS);
                                                    }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    executed.incrementAndGet();
                    JsonObject result = new JsonObject();
                    result.addProperty("type", "cmd_ack");
                    result.addProperty("detail", params.get("command").getAsString());
                    reply.ok(result);
                    return;
                }
                if (!reply.started()) {
                    return;
                }
                JsonObject result = new JsonObject();
                if (largeResultBytes > 0) {
                    StringBuilder filler = new StringBuilder();
                    while (filler.length() < largeResultBytes) {
                        filler.append("0123456789abcdef");
                    }
                    result.addProperty("blob", filler.toString());
                }
                result.addProperty("type", operation);
                reply.ok(result);
            }

            @Override
            public JsonArray capabilities() {
                return JsonParser.parseString("[\"state\",\"entities\",\"player\",\"command\",\"context\","
                        + "\"wait\",\"mark\",\"snapshot\",\"events:game\"]").getAsJsonArray();
            }

            @Override
            public String instanceKind() {
                return "server";
            }
        }

        @Override
        public void close() {
            if (listener != null) {
                listener.close().syncUninterruptibly();
            }
            if (group != null) {
                group.shutdownGracefully().syncUninterruptibly();
            }
            gameThread.shutdownNow();
        }
    }

    private SSLContext clientContext(Path dir) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate parsed;
        try (var input = Files.newInputStream(dir.resolve("server.crt"))) {
            parsed = (X509Certificate) factory.generateCertificate(input);
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("control", parsed);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private static JsonObject hello(String token, long lastSeq, String runId) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "hello");
        frame.addProperty("protocol", ControlServer.CONTROL_PROTOCOL_VERSION);
        frame.addProperty("token", token);
        frame.addProperty("lastSeq", lastSeq);
        if (runId != null) {
            frame.addProperty("runId", runId);
        }
        return frame;
    }

    private static JsonObject request(String id, String op, JsonObject params, long timeoutMillis) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "request");
        frame.addProperty("id", id);
        frame.addProperty("op", op);
        frame.add("params", params);
        if (timeoutMillis > 0) {
            frame.addProperty("timeoutMillis", timeoutMillis);
        }
        return frame;
    }

    private static void handshake(FrameClient client, String token) throws IOException {
        client.send(hello(token, 0, null));
        client.readWelcome(5000);
    }

    private static JsonObject requestReply(FrameClient client, String id, String op, JsonObject params,
                                           long timeoutMillis) throws IOException {
        client.send(request(id, op, params, timeoutMillis));
        return client.readReply(id, 15000);
    }

    // ------------------------------------------------------------- regression

    private void atomicWriteReservation() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-reserve-");
        CountDownLatch inflight = new CountDownLatch(1);
        try (TestServer server = new TestServer(dir, null, inflight, 0)) {
            String token = server.server.auth().issue("reserve", Set.of("read", "write"), null);
            SSLContext context = clientContext(dir);
            try (FrameClient a = new FrameClient(context.getSocketFactory(), server.port);
                 FrameClient b = new FrameClient(context.getSocketFactory(), server.port)) {
                handshake(a, token);
                handshake(b, token);

                JsonObject payloadA = new JsonObject();
                payloadA.addProperty("command", "say one");
                JsonObject payloadB = new JsonObject();
                payloadB.addProperty("command", "say two");

                a.send(request("same-id", "command", payloadA, 0));
                check(server.commandStarted.await(5, TimeUnit.SECONDS), "the first write started");

                // A different payload under the same running id is a conflict.
                b.send(request("same-id", "command", payloadB, 0));
                JsonObject conflict = b.readReply("same-id", 5000);
                check(conflict.has("error") && "conflict".equals(
                                conflict.getAsJsonObject("error").get("code").getAsString()),
                        "a different payload under the same id is a conflict: " + conflict);
                check(server.executed.get() == 0, "the conflicting write did not execute");

                // Same payload again while running is in-flight, not a second run.
                b.send(request("same-id", "command", payloadA, 0));
                JsonObject inFlight = b.readReply("same-id", 5000);
                check(inFlight.has("error") && "request_in_flight".equals(
                                inFlight.getAsJsonObject("error").get("code").getAsString()),
                        "same payload while running reports in-flight: " + inFlight);
                check(server.executed.get() == 0, "in-flight repeat did not execute");

                inflight.countDown();
                JsonObject first = a.readReply("same-id", 10000);
                check(first.get("ok").getAsBoolean(), "the first write succeeds: " + first);
                check(server.executed.get() == 1, "only one write executed");

                a.send(request("same-id", "command", payloadA, 0));
                JsonObject duplicate = a.readReply("same-id", 10000);
                check(duplicate.get("ok").getAsBoolean()
                                && duplicate.getAsJsonObject("result").get("duplicate").getAsBoolean(),
                        "a terminal repeat with the same payload is de-duplicated: " + duplicate);
                check(server.executed.get() == 1, "duplicate did not execute");
            }
        }
    }

    private void queuedTimeoutCancelsExecution() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-queued-");
        CountDownLatch gate = new CountDownLatch(1);
        try (TestServer server = new TestServer(dir, gate, null, 0)) {
            String token = server.server.auth().issue("queued", Set.of("read", "write"), null);
            try (FrameClient client = new FrameClient(clientContext(dir).getSocketFactory(), server.port)) {
                handshake(client, token);
                JsonObject params = new JsonObject();
                params.addProperty("command", "say queued");
                client.send(request("q1", "command", params, 1000));
                JsonObject timeout = client.readReply("q1", 5000);
                check("timeout".equals(timeout.getAsJsonObject("error").get("code").getAsString()),
                        "the queued request times out");
                check(!timeout.getAsJsonObject("error").get("resultUnknown").getAsBoolean(),
                        "an unstarted timeout is retryable, not result-unknown");
                gate.countDown();
                Thread.sleep(400);
                check(server.executed.get() == 0,
                        "a request cancelled while queued must not execute on the game thread");
                JsonObject statusParams = new JsonObject();
                statusParams.addProperty("requestId", "q1");
                JsonObject status = requestReply(client, "q1s", "request_status", statusParams, 0);
                check("unknown".equals(status.getAsJsonObject("result").get("state").getAsString()),
                        "the cancelled request has no pending ledger entry");
            }
        }
    }

    private void inflightDisconnectStillRecordsOutcome() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-inflight-");
        CountDownLatch inflight = new CountDownLatch(1);
        System.out.println("DEBUG test latch count=" + inflight.getCount());
        try (TestServer server = new TestServer(dir, null, inflight, 0)) {
            String token = server.server.auth().issue("inflight", Set.of("read", "write"), null);
            SSLContext context = clientContext(dir);
            FrameClient victim = new FrameClient(context.getSocketFactory(), server.port);
            handshake(victim, token);
            JsonObject params = new JsonObject();
            params.addProperty("command", "say inflight");
            victim.send(request("f1", "command", params, 30000));
            check(server.commandStarted.await(5, TimeUnit.SECONDS), "the command started on the game thread");
            victim.close(); // socket gone while the operation is running
            inflight.countDown();
            Thread.sleep(400);

            try (FrameClient reader = new FrameClient(context.getSocketFactory(), server.port)) {
                handshake(reader, token);
                JsonObject statusParams = new JsonObject();
                statusParams.addProperty("requestId", "f1");
                JsonObject status = requestReply(reader, "f1s", "request_status", statusParams, 0);
                check("completed".equals(status.getAsJsonObject("result").get("state").getAsString()),
                        "a running write whose socket died still records its final outcome");
            }
        }
    }

    private void writeOnlyCredentialCannotRead() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-readgate-");
        try (TestServer server = new TestServer(dir, null, null, 0)) {
            String token = server.server.auth().issue("writer", Set.of("write"), null);
            try (FrameClient client = new FrameClient(clientContext(dir).getSocketFactory(), server.port)) {
                handshake(client, token);
                JsonObject state = requestReply(client, "r1", "state", new JsonObject(), 0);
                check("forbidden".equals(state.getAsJsonObject("error").get("code").getAsString()),
                        "a write-only credential cannot read state");
                JsonObject context = new JsonObject();
                context.addProperty("contextId", "ctx");
                JsonObject refused = requestReply(client, "r2", "context", context, 0);
                check("forbidden".equals(refused.getAsJsonObject("error").get("code").getAsString()),
                        "a write-only credential cannot read context");
                // Events are read data too: publish one and require no delivery.
                JsonObject event = new JsonObject();
                event.addProperty("type", "mark");
                event.addProperty("text", "secret");
                server.server.publishEvent(event);
                boolean delivered = false;
                try {
                    client.readNext(500);
                    delivered = true;
                } catch (SocketTimeoutException expected) {
                    // nothing arrived
                }
                check(!delivered, "a write-only credential receives no events");
            }
        }
    }

    private void liveExpiryClosesSession() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-ttl-");
        try (TestServer server = new TestServer(dir, null, null, 0)) {
            String token = server.server.auth().issue("short", Set.of("read"), 1L);
            try (FrameClient client = new FrameClient(clientContext(dir).getSocketFactory(), server.port)) {
                handshake(client, token);
                Thread.sleep(1200);
                JsonObject expired = requestReply(client, "e1", "state", new JsonObject(), 0);
                check("forbidden".equals(expired.getAsJsonObject("error").get("code").getAsString()),
                        "an expired credential is refused before execution");
                server.server.expireSessions();
                check(client.isClosed(5000), "the expiry sweep closes the live session");
            }
        }
    }

    private void reloadDowngradeClosesSessionAndBadFileKeepsStore() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-reload-");
        try (TestServer server = new TestServer(dir, null, null, 0)) {
            String secret = server.server.auth().issue("downgrade", Set.of("read", "write"), null);
            SSLContext context = clientContext(dir);
            FrameClient client = new FrameClient(context.getSocketFactory(), server.port);
            handshake(client, secret);
            // Malformed reload: the live store must survive.
            String original = Files.readString(server.server.auth().file(), StandardCharsets.UTF_8);
            Files.writeString(server.server.auth().file(), "{ not json", StandardCharsets.UTF_8);
            boolean threw = false;
            try {
                server.server.reloadTokensAndCloseRevoked();
            } catch (IOException exception) {
                threw = true;
            }
            check(threw, "a malformed reload fails loudly");
            check(server.server.auth().authenticate(secret, System.currentTimeMillis()).status
                            == ControlAuth.Status.OK,
                    "a malformed reload keeps the previous credentials");
            check(!client.isClosed(300), "a malformed reload does not drop sessions");
            // Permission downgrade: the session is closed so it re-authenticates.
            Files.writeString(server.server.auth().file(), original, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(original).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("tokens")) {
                JsonObject entry = element.getAsJsonObject();
                JsonArray permissions = new JsonArray();
                permissions.add("read");
                entry.add("permissions", permissions);
            }
            Files.writeString(server.server.auth().file(), root.toString(), StandardCharsets.UTF_8);
            server.server.reloadTokensAndCloseRevoked();
            check(client.isClosed(5000), "a permission downgrade closes the live session");
            client.close();
        }
    }

    private void queuedWriteDoesNotRunAfterRevoke() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-revoke-");
        CountDownLatch gate = new CountDownLatch(1);
        try (TestServer server = new TestServer(dir, null, gate, 0)) {
            String token = server.server.auth().issue("revoked", Set.of("read", "write"), null);
            String tokenId = server.server.auth().entries().stream()
                    .filter(entry -> entry.label.equals("revoked")).findFirst().orElseThrow().id;
            try (FrameClient client = new FrameClient(clientContext(dir).getSocketFactory(), server.port)) {
                handshake(client, token);
                // Occupy the single game thread so the next request stays queued.
                JsonObject warmup = new JsonObject();
                warmup.addProperty("command", "say warmup");
                client.send(request("warm", "command", warmup, 20000));
                check(server.commandStarted.await(5, TimeUnit.SECONDS), "the warmup command started");
                JsonObject params = new JsonObject();
                params.addProperty("command", "say revoked");
                client.send(request("rv1", "command", params, 20000));
                Thread.sleep(150); // queued behind the warmup, not claimed yet
                server.server.revokeAndClose(tokenId);
                gate.countDown();
                Thread.sleep(400);
                check(server.executed.get() == 1,
                        "a write revoked while queued must not execute (only the warmup ran)");
            }
        }
    }

    private void slowConsumerClosesSession() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-slow-");
        try (TestServer server = new TestServer(dir, null, null, 64 * 1024)) {
            String token = server.server.auth().issue("slow", Set.of("read", "write"), null);
            try (FrameClient client = new FrameClient(clientContext(dir).getSocketFactory(), server.port)) {
                handshake(client, token);
                for (int index = 0; index < 20; index++) {
                    client.send(request("slow-" + index, "state", new JsonObject(), 5000));
                }
                check(client.isClosed(10000),
                        "a reader that never drains is closed instead of queueing without bound");
            }
        }
    }

    private void eventOrderingIsAtomic() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-events-");
        try (TestServer server = new TestServer(dir, null, null, 0)) {
            String token = server.server.auth().issue("events", Set.of("read"), null);
            int publishers = 4;
            int perPublisher = 50;
            List<Thread> threads = new ArrayList<>();
            for (int publisher = 0; publisher < publishers; publisher++) {
                final int id = publisher;
                Thread thread = new Thread(() -> {
                    for (int index = 0; index < perPublisher; index++) {
                        JsonObject event = new JsonObject();
                        event.addProperty("type", "mark");
                        event.addProperty("text", "p" + id + "-" + index);
                        server.server.publishEvent(event);
                    }
                });
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(5000);
            }
            try (FrameClient client = new FrameClient(clientContext(dir).getSocketFactory(), server.port)) {
                handshake(client, token);
                long expected = 1;
                long total = publishers * perPublisher;
                for (long index = 0; index < total; index++) {
                    JsonObject event = client.readNext(5000);
                    long seq = event.get("seq").getAsLong();
                    check(seq == expected, "event sequence is contiguous and ordered at " + expected);
                    expected++;
                }
            }
        }
    }

    private void crossRunReplayIsReported() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-crossrun-");
        try (TestServer server = new TestServer(dir, null, null, 0)) {
            String token = server.server.auth().issue("crossrun", Set.of("read"), null);
            JsonObject event = new JsonObject();
            event.addProperty("type", "mark");
            server.server.publishEvent(event);
            try (FrameClient client = new FrameClient(clientContext(dir).getSocketFactory(), server.port)) {
                client.send(hello(token, 999, "run_from_another_server"));
                JsonObject welcome = client.readWelcome(5000);
                JsonObject replay = welcome.getAsJsonObject("replay");
                check(replay.get("lost").getAsBoolean(), "a cross-run cursor is reported as lost");
                check(replay.get("crossRun").getAsBoolean(), "the welcome marks the run mismatch");
                check(replay.get("from").getAsLong() == 1, "a cross-run replay starts at the new run's first event");
            }
        }
    }

    /**
     * The queued/running/cancelled transition must be a single atomic step:
     * whichever side wins, the other observes it and never executes a task the
     * client was told is safe to retry.
     */
    private void pendingStateMachineRace() throws Exception {
        String property = "mcagent.controlMinRequestTimeoutMillis";
        String previous = System.getProperty(property);
        System.setProperty(property, "200");
        try {
            // Cancel wins: timeout fires while the task waits at the claim barrier.
            Path dir = Files.createTempDirectory("mcagent-race-cancel-");
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch go = new CountDownLatch(1);
            try (TestServer server = new TestServer(dir, null, null, 0)) {
                server.claimReady = ready;
                server.claimGo = go;
                String token = server.server.auth().issue("race", Set.of("read", "write"), null);
                try (FrameClient client = new FrameClient(clientContext(dir).getSocketFactory(), server.port)) {
                    handshake(client, token);
                    JsonObject params = new JsonObject();
                    params.addProperty("command", "say race-cancel");
                    client.send(request("race-cancel", "command", params, 300));
                    check(ready.await(5, TimeUnit.SECONDS), "the task reached the claim barrier");
                    JsonObject timeout = client.readReply("race-cancel", 5000);
                    check("timeout".equals(timeout.getAsJsonObject("error").get("code").getAsString()),
                            "the queued request timed out: " + timeout);
                    check(timeout.getAsJsonObject("error").get("retryable").getAsBoolean()
                                    && !timeout.getAsJsonObject("error").get("resultUnknown").getAsBoolean(),
                            "a cancelled queued request is safely retryable: " + timeout);
                    go.countDown();
                    Thread.sleep(300);
                    check(server.executed.get() == 0, "the cancelled task never executed");
                }
            }

            // Claim wins: the task is running when the timeout fires, so the
            // client sees result-unknown and the ledger still records the end.
            Path dir2 = Files.createTempDirectory("mcagent-race-run-");
            CountDownLatch ready2 = new CountDownLatch(1);
            CountDownLatch go2 = new CountDownLatch(1);
            CountDownLatch inflight = new CountDownLatch(1);
            try (TestServer server = new TestServer(dir2, null, inflight, 0)) {
                server.claimReady = ready2;
                server.claimGo = go2;
                String token = server.server.auth().issue("race2", Set.of("read", "write"), null);
                SSLContext context = clientContext(dir2);
                try (FrameClient client = new FrameClient(context.getSocketFactory(), server.port)) {
                    handshake(client, token);
                    JsonObject params = new JsonObject();
                    params.addProperty("command", "say race-run");
                    client.send(request("race-run", "command", params, 300));
                    check(ready2.await(5, TimeUnit.SECONDS), "the task reached the claim barrier");
                    go2.countDown();
                    check(server.commandStarted.await(5, TimeUnit.SECONDS), "the task claimed the request");
                    JsonObject unknown = client.readReply("race-run", 5000);
                    check("timeout".equals(unknown.getAsJsonObject("error").get("code").getAsString())
                                    && unknown.getAsJsonObject("error").get("resultUnknown").getAsBoolean(),
                            "a running request reports result-unknown: " + unknown);
                    inflight.countDown();
                    Thread.sleep(300);
                    try (FrameClient reader = new FrameClient(context.getSocketFactory(), server.port)) {
                        handshake(reader, token);
                        JsonObject statusParams = new JsonObject();
                        statusParams.addProperty("requestId", "race-run");
                        JsonObject status = requestReply(reader, "race-run-s", "request_status", statusParams, 0);
                        check("completed".equals(status.getAsJsonObject("result").get("state").getAsString()),
                                "the running request still records its terminal outcome: " + status);
                    }
                }
            }
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    /** Duplicate in-flight ids are rejected for reads and writes alike. */
    private void duplicatePendingIds() throws Exception {
        Path dir = Files.createTempDirectory("mcagent-dup-id-");
        try (TestServer server = new TestServer(dir, null, null, 0)) {
            String token = server.server.auth().issue("dup", Set.of("read", "write"), null);
            try (FrameClient client = new FrameClient(clientContext(dir).getSocketFactory(), server.port)) {
                handshake(client, token);

                // read/read collision
                CountDownLatch gate = new CountDownLatch(1);
                server.preClaimGate = gate;
                client.send(request("dup-read", "state", new JsonObject(), 5000));
                Thread.sleep(100);
                client.send(request("dup-read", "state", new JsonObject(), 5000));
                JsonObject collision = client.readReply("dup-read", 5000);
                check(collision.has("error") && "request_in_flight".equals(
                                collision.getAsJsonObject("error").get("code").getAsString()),
                        "a duplicate read id is refused: " + collision);
                gate.countDown();
                JsonObject firstRead = client.readReply("dup-read", 5000);
                check(firstRead.get("ok").getAsBoolean(), "the original read completes: " + firstRead);

                // read/write collision: the command must not claim the ledger.
                CountDownLatch gate2 = new CountDownLatch(1);
                server.preClaimGate = gate2;
                client.send(request("dup-mixed", "state", new JsonObject(), 5000));
                Thread.sleep(100);
                JsonObject blocked = new JsonObject();
                blocked.addProperty("command", "say blocked");
                client.send(request("dup-mixed", "command", blocked, 5000));
                JsonObject mixed = client.readReply("dup-mixed", 5000);
                check(mixed.has("error") && "request_in_flight".equals(
                                mixed.getAsJsonObject("error").get("code").getAsString()),
                        "a write cannot take a live read id: " + mixed);
                check(server.executed.get() == 0, "the colliding write did not execute");
                gate2.countDown();
                client.readReply("dup-mixed", 5000);
                // The id is free again after completion; the write then executes.
                JsonObject retried = requestReply(client, "dup-mixed", "command", blocked, 0);
                check(retried.get("ok").getAsBoolean() && server.executed.get() == 1,
                        "the id is usable after the read finished: " + retried);

                // write/read collision in the other direction.
                CountDownLatch gate3 = new CountDownLatch(1);
                server.preClaimGate = gate3;
                JsonObject writeParams = new JsonObject();
                writeParams.addProperty("command", "say holds");
                client.send(request("dup-write", "command", writeParams, 5000));
                Thread.sleep(100);
                client.send(request("dup-write", "state", new JsonObject(), 5000));
                JsonObject reverse = client.readReply("dup-write", 5000);
                check(reverse.has("error") && "request_in_flight".equals(
                                reverse.getAsJsonObject("error").get("code").getAsString()),
                        "a read cannot take a live write id: " + reverse);
                gate3.countDown();
                JsonObject writeReply = client.readReply("dup-write", 5000);
                check(writeReply.get("ok").getAsBoolean(), "the original write completes: " + writeReply);
            }
        }
    }

    /**
     * The outbound budget must cover frames that are scheduled on the event
     * loop but not yet written, so a stalled loop cannot accumulate them.
     */
    private void outboundBudgetCapsScheduledFrames() throws Exception {
        String property = "mcagent.controlMaxOutboundBytes";
        String previous = System.getProperty(property);
        System.setProperty(property, "131072");
        try {
            Path dir = Files.createTempDirectory("mcagent-outbound-");
            try (TestServer server = new TestServer(dir, null, null, 0)) {
                String token = server.server.auth().issue("outbound", Set.of("read", "write"), null);
                try (FrameClient client = new FrameClient(clientContext(dir).getSocketFactory(), server.port)) {
                    handshake(client, token);
                    ControlSession session = server.server.sessionsSnapshot().iterator().next();
                    CountDownLatch stall = new CountDownLatch(1);
                    session.channel().eventLoop().execute(() -> {
                        try {
                            stall.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    JsonObject inner = new JsonObject();
                    inner.addProperty("type", "mark");
                    inner.addProperty("text", "x".repeat(16 * 1024));
                    JsonObject envelope = new JsonObject();
                    envelope.addProperty("type", "event");
                    envelope.addProperty("seq", 1);
                    envelope.add("event", inner);
                    int accepted = 0;
                    boolean rejected = false;
                    for (int index = 0; index < 200; index++) {
                        if (session.sendEvent(envelope, false)) {
                            accepted++;
                        } else {
                            rejected = true;
                            break;
                        }
                    }
                    check(rejected, "the outbound budget rejected a frame while the loop was stalled");
                    check(accepted * (16 * 1024) <= 131072 + 64 * 1024,
                            "scheduled frames stayed within the budget (accepted=" + accepted + ")");
                    check(session.outboundBytes() <= 131072 + 64 * 1024,
                            "the budget counter stayed bounded: " + session.outboundBytes());
                    stall.countDown();
                    long deadline = System.currentTimeMillis() + 5000;
                    while (session.channel().isOpen() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50);
                    }
                    check(!session.channel().isOpen(), "the over-budget session was closed");
                }
            }
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    /** A result above the frame limit becomes a structured error, not a drop. */
    private void oversizedReplyBecomesStructuredError() throws Exception {
        String property = "mcagent.controlMaxFrameBytes";
        String previous = System.getProperty(property);
        System.setProperty(property, "4096");
        try {
            Path dir = Files.createTempDirectory("mcagent-framesize-");
            try (TestServer server = new TestServer(dir, null, null, 64 * 1024)) {
                String token = server.server.auth().issue("framesize", Set.of("read"), null);
                try (FrameClient client = new FrameClient(clientContext(dir).getSocketFactory(), server.port)) {
                    handshake(client, token);
                    JsonObject reply = requestReply(client, "big", "state", new JsonObject(), 0);
                    check(reply.has("error") && "response_too_large".equals(
                                    reply.getAsJsonObject("error").get("code").getAsString()),
                            "an oversized result is a structured error: " + reply);
                    JsonObject pong = requestReply(client, "ping-after", "ping", new JsonObject(), 0);
                    check(pong.get("ok").getAsBoolean(), "the session survives a rejected oversized result");
                }
            }
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    /** Minimal framed TLS client for the regression tests. */
    private static final class FrameClient implements AutoCloseable {
        private final SSLSocket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final List<JsonObject> collected = new CopyOnWriteArrayList<>();

        FrameClient(javax.net.ssl.SSLSocketFactory factory, int port) throws IOException {
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

        JsonObject readNext(long timeoutMillis) throws IOException {
            socket.setSoTimeout((int) timeoutMillis);
            return readFrame();
        }

        JsonObject readWelcome(long timeoutMillis) throws IOException {
            socket.setSoTimeout((int) timeoutMillis);
            while (true) {
                JsonObject frame = readFrame();
                if ("welcome".equals(frame.get("type").getAsString())) {
                    return frame;
                }
                collected.add(frame);
            }
        }

        JsonObject readReply(String id, long timeoutMillis) throws IOException {
            for (JsonObject frame : collected) {
                if ("reply".equals(frame.get("type").getAsString())
                        && (id.isEmpty() || id.equals(frame.get("id").getAsString()))) {
                    collected.remove(frame);
                    return frame;
                }
            }
            socket.setSoTimeout((int) timeoutMillis);
            while (true) {
                JsonObject frame = readFrame();
                if ("reply".equals(frame.get("type").getAsString())
                        && (id.isEmpty() || id.equals(frame.get("id").getAsString()))) {
                    return frame;
                }
                collected.add(frame);
            }
        }

        private JsonObject readFrame() throws IOException {
            int length = in.readInt();
            if (length <= 0 || length > 1024 * 1024) {
                throw new IOException("unexpected frame length " + length);
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            return JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        /**
         * Drain until EOF; a read timeout means the connection is still open.
         */
        boolean isClosed(long timeoutMillis) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            try {
                while (System.currentTimeMillis() < deadline) {
                    socket.setSoTimeout((int) Math.max(50, deadline - System.currentTimeMillis()));
                    if (in.read() < 0) {
                        return true;
                    }
                }
                return false;
            } catch (SocketTimeoutException exception) {
                return false;
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
}
