package dev.mcagent.interfacemod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * Entrypoint that runs wherever a server exists - a dedicated server, or the
 * integrated server inside a client running single player.
 *
 * It is deliberately separate from {@link InterfaceMod}: that class is
 * client-only and must not be loaded on a dedicated server.
 */
public final class ServerMod implements ModInitializer {
    private static ServerCore core;

    @Override
    public void onInitialize() {
        Path dir = Path.of(System.getProperty("mcagent.serverDir", "mc-agent-server"));
        int port = Integer.getInteger("mcagent.serverPort", InterfaceConstants.DEFAULT_SERVER_PORT);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            core = new ServerCore(server, dir, port);
            core.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (core != null) {
                core.stop();
                core = null;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (core != null) {
                core.onTick();
            }
        });
        // Capture the context when the packet is received, not when the chat
        // is broadcast: Fabric's CHAT_MESSAGE callback runs only after the
        // asynchronous chat filter, by which time the sender may have moved.
        // The network-handler mixin parks the receipt; this listener consumes
        // it and emits the event with the frozen context.
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            ServerCore current = core;
            if (current != null) {
                current.onChatMessage(sender, message);
            }
        });
        System.out.println("[mc-agent-interface] server vantage armed, dir=" + dir + " basePort=" + port);
    }

    /**
     * Called by the network-handler mixin when a chat message has just been
     * decoded on the server thread, before the asynchronous filter and before
     * any socket client or agent can delay it. Captures the sender's context
     * now and parks the bundle so the later broadcast can pick it up.
     */
    public static void onChatReceipt(ServerPlayer sender, PlayerChatMessage message) {
        ServerCore current = core;
        if (current != null) {
            current.onChatReceipt(sender, message);
        }
    }
}
