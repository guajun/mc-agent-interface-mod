package dev.mcagent.interfacemod;

import dev.mcagent.interfacemod.mixin.SamePortListenerAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConnectionListener;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Issue #7 spike: accept non-player control connections on the Minecraft game
 * port itself, without a second listening socket, a sidecar daemon or a client
 * relay.
 *
 * <p>The vanilla TCP listener builds every child pipeline in {@code
 * ServerConnectionListener$1.initChannel} and calls {@code
 * Connection.configurePacketHandler} once per TCP player connection. A mixin
 * inserts one {@link Sniffer} at the head of that pipeline. The sniffer waits
 * for {@link InterfaceConstants#SAME_PORT_MAGIC} before vanilla sees the first
 * byte:
 *
 * <ul>
 *   <li>marker present: the connection becomes a control session. The sniffer
 *       removes the vanilla handlers, detaches the never-started
 *       {@link Connection} from the server's connection list, and speaks the
 *       existing JSON-lines protocol through the same {@link LineHandler} the
 *       loopback server vantage uses. No player, no entity, no join.</li>
 *   <li>marker absent: the sniffer removes itself and hands the buffered bytes
 *       to the untouched vanilla pipeline. A normal player is byte-for-byte
 *       unaffected.</li>
 * </ul>
 *
 * <p>This is an experiment, not the #8 protocol. It is off unless
 * {@code -Dmcagent.samePortSpike=true} is set explicitly, and it accepts
 * unauthenticated control connections on the game port, so it must only be
 * enabled in an isolated test environment. By default only loopback control
 * clients are accepted; {@code -Dmcagent.samePortSpikeAllowRemote=true}
 * additionally allows remote ones, which is only meaningful on a private LAN.
 *
 * <p>Reads are bounded: a control connection that never sends the marker is
 * closed after {@code mcagent.samePortSpikeHandshakeSeconds} (default 10), an
 * idle session is closed after {@code mcagent.samePortSpikeIdleSeconds}
 * (default 300), and a line longer than
 * {@code mcagent.samePortSpikeMaxLineBytes} (default 65536) closes the
 * session. Replies are dropped and the session is closed if the socket is not
 * writable, so a slow consumer cannot build an unbounded queue.
 */
public final class SamePortControl {
    public static final String PROPERTY_ENABLED = "mcagent.samePortSpike";
    public static final String PROPERTY_ALLOW_REMOTE = "mcagent.samePortSpikeAllowRemote";
    public static final String PROPERTY_HANDSHAKE_SECONDS = "mcagent.samePortSpikeHandshakeSeconds";
    public static final String PROPERTY_IDLE_SECONDS = "mcagent.samePortSpikeIdleSeconds";
    public static final String PROPERTY_MAX_LINE_BYTES = "mcagent.samePortSpikeMaxLineBytes";

    private static final String HANDLER_NAME = "mcagent-same-port-sniffer";
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Map<ChannelId, Session> SESSIONS = new ConcurrentHashMap<>();

    private static volatile LineHandler handler;
    private static volatile ServerConnectionListener listener;

    private SamePortControl() {
    }

    /** Explicit opt-in; never on by default. */
    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY_ENABLED);
    }

    /** Bind the server vantage whose {@link LineHandler} serves control sessions. */
    public static void attach(LineHandler core) {
        handler = core;
    }

    public static void detach() {
        handler = null;
    }

    /** Remember the listener whose accept path created a connection. */
    public static void captureListener(ServerConnectionListener connectionListener) {
        listener = connectionListener;
    }

    public static void clearListener() {
        listener = null;
    }

    public static int sessionCount() {
        return SESSIONS.size();
    }

    /** One loud line so an operator cannot enable this without noticing it. */
    public static void logConfiguration() {
        if (enabled()) {
            log("same-port control spike ENABLED (experimental, unauthenticated):"
                    + " control sessions are accepted on the game TCP port."
                    + " Loopback only unless -D" + PROPERTY_ALLOW_REMOTE + "=true."
                    + " Use only in isolated tests; authentication is #8.");
        } else {
            log("same-port control spike disabled (enable with -D" + PROPERTY_ENABLED + "=true)");
        }
    }

    /**
     * Insert the sniffer at the head of a serverbound, non-memory connection
     * pipeline. Called from the {@code Connection.configurePacketHandler} mixin
     * before any netty handler has seen a byte.
     */
    public static void armChannel(ChannelPipeline pipeline, Connection connection) {
        if (!enabled()) {
            return;
        }
        if (pipeline.context(HANDLER_NAME) != null) {
            return;
        }
        pipeline.addFirst(HANDLER_NAME, new Sniffer(connection));
    }

    /** Close every live control session; used when the listener or server stops. */
    public static void closeAll(String reason) {
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            session.close(reason);
        }
        SESSIONS.clear();
    }

    /** Observable state for {@code STATE} and for test evidence. */
    public static JsonObject state() {
        JsonObject object = new JsonObject();
        object.addProperty("enabled", enabled());
        object.addProperty("transport", InterfaceConstants.SAME_PORT_TRANSPORT);
        object.addProperty("allowRemote", Boolean.getBoolean(PROPERTY_ALLOW_REMOTE));
        object.addProperty("sessions", SESSIONS.size());
        JsonArray open = new JsonArray();
        for (Session session : SESSIONS.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", session.id);
            entry.addProperty("remote", session.remote);
            entry.addProperty("openedAtMillis", session.openedAtMillis);
            open.add(entry);
        }
        object.add("open", open);
        return object;
    }

    /** True when {@code candidate}'s first bytes are the control marker. */
    public static boolean matchesMagic(byte[] candidate) {
        byte[] magic = InterfaceConstants.SAME_PORT_MAGIC.getBytes(StandardCharsets.US_ASCII);
        if (candidate == null || candidate.length < magic.length) {
            return false;
        }
        for (int index = 0; index < magic.length; index++) {
            if (candidate[index] != magic[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesMagic(ByteBuf in) {
        String magic = InterfaceConstants.SAME_PORT_MAGIC;
        for (int index = 0; index < magic.length(); index++) {
            if (in.getByte(in.readerIndex() + index) != (byte) magic.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static void detachVanilla(Connection connection) {
        ServerConnectionListener current = listener;
        if (current == null || connection == null) {
            log("same-port control: no listener reference, vanilla Connection left in place");
            return;
        }
        try {
            List<Connection> connections = ((SamePortListenerAccessor) current).mcagent$connections();
            if (connections.remove(connection)) {
                log("same-port control: detached the placeholder Connection from the server list");
            }
        } catch (Throwable throwable) {
            log("same-port control: could not detach the placeholder Connection (" + throwable + ")");
        }
    }

    private static boolean remoteAllowed(Channel channel) {
        if (Boolean.getBoolean(PROPERTY_ALLOW_REMOTE)) {
            return true;
        }
        SocketAddress address = channel.remoteAddress();
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().isLoopbackAddress();
        }
        return false;
    }

    private static String helloJson(Session session) {
        int port = -1;
        if (session.channel.localAddress() instanceof InetSocketAddress local) {
            port = local.getPort();
        }
        return "{\"type\":\"hello\",\"mod\":\"" + InterfaceConstants.MOD_ID
                + "\",\"version\":\"" + InterfaceConstants.VERSION
                + "\",\"protocol\":" + InterfaceConstants.PROTOCOL_VERSION
                + ",\"minecraft\":\"26.2\",\"port\":" + port
                + ",\"instance\":\"server\",\"transport\":\"" + InterfaceConstants.SAME_PORT_TRANSPORT
                + "\",\"session\":\"" + session.id + "\""
                + ",\"capabilities\":" + InterfaceConstants.SERVER_CAPABILITIES + "}";
    }

    private static void log(String message) {
        System.out.println("[mc-agent-interface] " + message);
    }

    /**
     * First handler in the pipeline. Sniffs the marker, then either forwards to
     * vanilla (mismatch) or turns into the control transport (match).
     */
    private static final class Sniffer extends ByteToMessageDecoder {
        private final Connection connection;
        private boolean sniffed;
        private ScheduledFuture<?> deadline;
        private Session session;

        private Sniffer(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext context) throws Exception {
            long seconds = Long.getLong(PROPERTY_HANDSHAKE_SECONDS, 10L);
            if (seconds > 0) {
                deadline = context.executor().schedule(() -> {
                    if (!sniffed && context.channel().isOpen()) {
                        log("same-port control: handshake marker timeout from "
                                + context.channel().remoteAddress());
                        context.close();
                    }
                }, seconds, TimeUnit.SECONDS);
            }
            super.handlerAdded(context);
        }

        @Override
        protected void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out) {
            if (!sniffed) {
                if (in.readableBytes() < InterfaceConstants.SAME_PORT_MAGIC.length()) {
                    return;
                }
                sniffed = true;
                cancelDeadline();
                if (!matchesMagic(in)) {
                    // Not a control connection: remove ourselves and let the
                    // buffered vanilla handshake bytes flow downstream.
                    context.pipeline().remove(this);
                    return;
                }
                in.skipBytes(InterfaceConstants.SAME_PORT_MAGIC.length());
                adopt(context);
            }
            if (session != null) {
                drainLines(in);
            }
        }

        private void adopt(ChannelHandlerContext context) {
            Channel channel = context.channel();
            Session created = new Session(channel);
            session = created;
            log("same-port control: adopting " + channel.remoteAddress()
                    + (channel.localAddress() == null ? "" : " on " + channel.localAddress()));

            if (!remoteAllowed(channel)) {
                log("same-port control: refused non-loopback " + channel.remoteAddress()
                        + " (set -D" + PROPERTY_ALLOW_REMOTE + "=true only on a private LAN)");
                created.send("{\"type\":\"refused\",\"message\":\"remote control sessions disabled\"}");
                created.close("remote control disabled");
                return;
            }

            detachVanilla(connection);
            // Drop the untouched vanilla pipeline, tail first. Netty's hidden
            // Head/Tail contexts have generated names that are not removable,
            // so only names with a real context are touched.
            List<String> names = new ArrayList<>(context.pipeline().names());
            for (int index = names.size() - 1; index >= 0; index--) {
                String name = names.get(index);
                if (HANDLER_NAME.equals(name)) {
                    continue;
                }
                if (context.pipeline().context(name) != null) {
                    context.pipeline().remove(name);
                }
            }
            SESSIONS.put(channel.id(), created);
            created.open();
        }

        private void drainLines(ByteBuf in) {
            int maxLine = Integer.getInteger(PROPERTY_MAX_LINE_BYTES, 65536);
            while (!session.closed) {
                int newline = in.indexOf(in.readerIndex(), in.writerIndex(), (byte) '\n');
                if (newline < 0) {
                    if (in.readableBytes() > maxLine) {
                        session.close("line longer than " + maxLine + " bytes");
                    }
                    return;
                }
                int length = newline - in.readerIndex();
                if (length > maxLine) {
                    session.close("line longer than " + maxLine + " bytes");
                    return;
                }
                ByteBuf lineBuffer = in.readRetainedSlice(length);
                in.skipBytes(1);
                String line = lineBuffer.toString(StandardCharsets.UTF_8).trim();
                lineBuffer.release();
                session.receive(line);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            cancelDeadline();
            if (session != null) {
                session.close("channel closed by peer");
            } else if (sniffed) {
                log("same-port control: session channel closed before adoption");
            }
            super.channelInactive(context);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            log("same-port control: sniffer failed for " + context.channel().remoteAddress()
                    + ": " + cause);
            context.close();
        }

        private void cancelDeadline() {
            if (deadline != null) {
                deadline.cancel(false);
                deadline = null;
            }
        }
    }

    /** One adopted control connection on the game port. */
    private static final class Session {
        private final Channel channel;
        private final String id;
        private final String remote;
        private final long openedAtMillis = System.currentTimeMillis();
        private volatile boolean closed;
        private ScheduledFuture<?> idle;

        private Session(Channel channel) {
            this.channel = channel;
            this.id = "spike-" + SEQUENCE.incrementAndGet();
            this.remote = String.valueOf(channel.remoteAddress());
        }

        private void open() {
            send(helloJson(this));
            touch();
            log("same-port control session " + id + " accepted from " + remote
                    + " (transport=" + InterfaceConstants.SAME_PORT_TRANSPORT
                    + ", sessions=" + SESSIONS.size() + ")");
        }

        private void receive(String line) {
            if (closed) {
                return;
            }
            touch();
            if (line.isEmpty()) {
                return;
            }
            LineHandler target = handler;
            if (target == null) {
                send("{\"type\":\"error\",\"message\":\"server vantage is not ready\"}");
                return;
            }
            try {
                target.handleLine(line, this::send);
            } catch (Throwable throwable) {
                send("{\"type\":\"error\",\"message\":\"bad command: " + throwable + "\"}");
            }
        }

        private void send(String line) {
            if (closed || !channel.isActive()) {
                close("channel inactive");
                return;
            }
            if (!channel.isWritable()) {
                close("slow consumer: outbound buffer full");
                return;
            }
            String payload = (line == null ? "" : line) + "\n";
            channel.writeAndFlush(Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8))
                    .addListener(future -> {
                        if (!future.isSuccess()) {
                            close("write failed: " + future.cause());
                        }
                    });
        }

        private void touch() {
            if (closed) {
                return;
            }
            if (idle != null) {
                idle.cancel(false);
            }
            long seconds = Long.getLong(PROPERTY_IDLE_SECONDS, 300L);
            if (seconds > 0) {
                idle = channel.eventLoop().schedule(
                        () -> close("idle timeout after " + seconds + "s"), seconds, TimeUnit.SECONDS);
            }
        }

        private void close(String reason) {
            if (closed) {
                return;
            }
            closed = true;
            SESSIONS.remove(channel.id());
            if (idle != null) {
                idle.cancel(false);
                idle = null;
            }
            try {
                channel.close();
            } catch (Throwable ignored) {
                // closing anyway
            }
            log("same-port control session " + id + " (" + remote + ") closed: " + reason
                    + "; sessions=" + SESSIONS.size());
        }
    }
}
