package dev.mcagent.interfacemod;

/**
 * Values shared by the client and the server entrypoint.
 *
 * They live here, not in {@link InterfaceMod}, because that class imports client
 * classes and must never be loaded by a dedicated server.
 */
public final class InterfaceConstants {
    public static final String MOD_ID = "mc-agent-interface";
    public static final String VERSION = "0.6.0";
    public static final int PROTOCOL_VERSION = 1;

    public static final int DEFAULT_CLIENT_PORT = 25580;
    public static final int DEFAULT_SERVER_PORT = 25581;

    /** Schema string carried by every captured player context bundle. */
    public static final String CONTEXT_SCHEMA = "player-context/1";
    /** Chat context bundles kept in the server-vantage cache before eviction. */
    public static final int DEFAULT_CONTEXT_CACHE_SIZE = 256;
    /** How long a chat context bundle stays fetchable after capture. */
    public static final int DEFAULT_CONTEXT_CACHE_TTL_SECONDS = 300;
    /** How long an unmatched receipt-time capture waits for its broadcast. */
    public static final int DEFAULT_RECEIPT_TTL_SECONDS = 60;

    public static final String CLIENT_CAPABILITIES =
            "[\"state\",\"entities\",\"command\",\"chat\",\"record\",\"wait\",\"screen\",\"mark\",\"connect\","
                    + "\"world\",\"lan\",\"events:chat\",\"events:game\"]";

    public static final String SERVER_CAPABILITIES =
            "[\"state\",\"entities\",\"command\",\"context\",\"wait\",\"mark\",\"snapshot\","
                    + "\"events:game\",\"events:chat\"]";

    private InterfaceConstants() {
    }
}
