package dev.mcagent.interfacemod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

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
        System.out.println("[mc-agent-interface] server vantage armed, dir=" + dir + " basePort=" + port);
    }
}
