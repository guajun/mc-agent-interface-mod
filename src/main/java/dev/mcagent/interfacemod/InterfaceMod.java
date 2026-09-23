package dev.mcagent.interfacemod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class InterfaceMod implements ClientModInitializer {
    public static final String MOD_ID = "mc-agent-interface";
    public static final String VERSION = "0.1.0";
    public static final int PROTOCOL_VERSION = 1;
    public static final String CAPABILITIES =
            "[\"state\",\"entities\",\"command\",\"chat\",\"record\",\"wait\",\"screen\",\"mark\",\"connect\","
                    + "\"events:chat\",\"events:game\"]";

    private static InterfaceCore core;

    @Override
    public void onInitializeClient() {
        String dirProperty = System.getProperty("mcagent.dir");
        Path dir = dirProperty == null || dirProperty.isBlank()
                ? FabricLoader.getInstance().getGameDir().resolve("mc-agent")
                : Path.of(dirProperty);
        int port = Integer.getInteger("mcagent.port", 25580);
        core = new InterfaceCore(dir, port);
        ClientTickEvents.END_CLIENT_TICK.register(core::onClientTick);
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> core.onGameMessage(message.getString(), overlay));
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, timestamp) ->
                        core.onChatMessage(message.getString(), sender == null ? "" : String.valueOf(sender)));
        core.start();
        System.out.println("[mc-agent-interface] initialized, dir=" + dir + " basePort=" + port);
    }
}

