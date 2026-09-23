package dev.mcagent.interfacemod;

/**
 * Values shared by the client and the server entrypoint.
 *
 * They live here, not in {@link InterfaceMod}, because that class imports client
 * classes and must never be loaded by a dedicated server.
 */
public final class InterfaceConstants {
    public static final String MOD_ID = "mc-agent-interface";
    public static final String VERSION = "0.5.0";
    public static final int PROTOCOL_VERSION = 1;

    public static final int DEFAULT_CLIENT_PORT = 25580;
    public static final int DEFAULT_SERVER_PORT = 25581;

    public static final String CLIENT_CAPABILITIES =
            "[\"state\",\"entities\",\"command\",\"chat\",\"record\",\"wait\",\"screen\",\"mark\",\"connect\","
                    + "\"world\",\"lan\",\"events:chat\",\"events:game\"]";

    public static final String SERVER_CAPABILITIES =
            "[\"state\",\"entities\",\"command\",\"wait\",\"mark\",\"snapshot\",\"events:game\"]";

    private InterfaceConstants() {
    }
}
