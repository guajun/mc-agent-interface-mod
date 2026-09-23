package dev.mcagent.interfacemod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Optional in-game surface for the same primitives the socket exposes.
 *
 * Nothing here is agent specific: it exists so a human can check the interface
 * and drive recording from inside the game, without a bridge or a model.
 */
public final class McAgentCommands {
    private static final int ENTITY_LIMIT = 12;
    private static final double DEFAULT_RADIUS = 32.0D;

    private McAgentCommands() {
    }

    public static void register(InterfaceCore core) {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> dispatcher.register(build(core)));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build(InterfaceCore core) {
        return ClientCommands.literal("mcagent")
                .executes(context -> status(core, context.getSource()))
                .then(ClientCommands.literal("status")
                        .executes(context -> status(core, context.getSource())))
                .then(ClientCommands.literal("state")
                        .executes(context -> state(core, context.getSource())))
                .then(ClientCommands.literal("entities")
                        .executes(context -> entities(core, context.getSource(), DEFAULT_RADIUS))
                        .then(ClientCommands.argument("radius", DoubleArgumentType.doubleArg(0.0D))
                                .executes(context -> entities(core, context.getSource(),
                                        DoubleArgumentType.getDouble(context, "radius")))))
                .then(ClientCommands.literal("port")
                        .executes(context -> port(core, context.getSource())))
                .then(ClientCommands.literal("lan")
                        .executes(context -> lan(core, context.getSource(), 0, null))
                        .then(ClientCommands.argument("port", IntegerArgumentType.integer(1024, 65535))
                                .executes(context -> lan(core, context.getSource(),
                                        IntegerArgumentType.getInteger(context, "port"), null))
                                .then(ClientCommands.literal("offline")
                                        .executes(context -> lan(core, context.getSource(),
                                                IntegerArgumentType.getInteger(context, "port"), true)))))
                .then(ClientCommands.literal("caps")
                        .executes(context -> caps(context.getSource())))
                .then(ClientCommands.literal("mark")
                        .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> mark(core, context.getSource(),
                                        StringArgumentType.getString(context, "text")))))
                .then(ClientCommands.literal("record")
                        .then(ClientCommands.literal("start")
                                .then(ClientCommands.argument("ticks", IntegerArgumentType.integer(1))
                                        .executes(context -> recordStart(core, context.getSource(),
                                                IntegerArgumentType.getInteger(context, "ticks"),
                                                DEFAULT_RADIUS, 1))
                                        .then(ClientCommands.argument("radius", DoubleArgumentType.doubleArg(0.0D))
                                                .executes(context -> recordStart(core, context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "ticks"),
                                                        DoubleArgumentType.getDouble(context, "radius"), 1))
                                                .then(ClientCommands.argument("interval", IntegerArgumentType.integer(1))
                                                        .executes(context -> recordStart(core, context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "ticks"),
                                                                DoubleArgumentType.getDouble(context, "radius"),
                                                                IntegerArgumentType.getInteger(context, "interval")))))))
                        .then(ClientCommands.literal("stop")
                                .executes(context -> recordStop(core, context.getSource()))))
                .then(ClientCommands.literal("help")
                        .executes(context -> help(context.getSource())));
    }

    private static int status(InterfaceCore core, FabricClientCommandSource source) {
        JsonObject screen = core.screenJson();
        feedback(source, String.format(Locale.ROOT,
                "mc-agent-interface %s (protocol %d)", InterfaceConstants.VERSION, InterfaceConstants.PROTOCOL_VERSION));
        feedback(source, String.format(Locale.ROOT,
                "port=%d  bridgeClients=%d  tick=%d  inWorld=%s  recording=%s",
                core.port(), core.clientCount(), core.tick(), screen.get("inWorld"), core.isSampling()));
        return 1;
    }

    private static int state(InterfaceCore core, FabricClientCommandSource source) {
        JsonObject state = core.stateJson();
        if (!state.has("x")) {
            feedback(source, "mc-agent: not in a world");
            return 0;
        }
        feedback(source, String.format(Locale.ROOT, "pos %.2f %.2f %.2f",
                state.get("x").getAsDouble(), state.get("y").getAsDouble(), state.get("z").getAsDouble()));
        feedback(source, String.format(Locale.ROOT, "vel %.3f %.3f %.3f   onGround=%s",
                state.get("vx").getAsDouble(), state.get("vy").getAsDouble(), state.get("vz").getAsDouble(),
                state.get("onGround")));
        feedback(source, String.format(Locale.ROOT, "health %.1f   entities %d   %s",
                state.get("health").getAsDouble(), state.get("entityCount").getAsInt(),
                state.get("dimension").getAsString()));
        return 1;
    }

    private static int entities(InterfaceCore core, FabricClientCommandSource source, double radius) {
        JsonArray entities = core.entitiesJson(radius).getAsJsonArray("entities");
        feedback(source, String.format(Locale.ROOT, "%d entities within %.1f blocks", entities.size(), radius));
        int shown = 0;
        for (JsonElement element : entities) {
            if (shown >= ENTITY_LIMIT) {
                break;
            }
            feedback(source, describe(element.getAsJsonObject()));
            shown++;
        }
        if (entities.size() > shown) {
            feedback(source, "... and " + (entities.size() - shown) + " more");
        }
        return 1;
    }

    private static String describe(JsonObject entity) {
        StringBuilder line = new StringBuilder();
        line.append('#').append(entity.get("id").getAsInt()).append(' ');
        line.append(entity.get("type").getAsString());
        line.append(String.format(Locale.ROOT, " @ %.2f %.2f %.2f",
                entity.get("x").getAsDouble(), entity.get("y").getAsDouble(), entity.get("z").getAsDouble()));
        line.append(String.format(Locale.ROOT, "  v %.3f %.3f %.3f",
                entity.get("vx").getAsDouble(), entity.get("vy").getAsDouble(), entity.get("vz").getAsDouble()));
        if (!entity.get("alive").getAsBoolean()) {
            line.append(" [dead]");
        }
        if (entity.has("health")) {
            line.append(String.format(Locale.ROOT, " hp=%.1f", entity.get("health").getAsDouble()));
        }
        if (entity.has("vehicle") && entity.get("vehicle").getAsInt() >= 0) {
            line.append(" vehicle=#").append(entity.get("vehicle").getAsInt());
        }
        JsonArray passengers = entity.getAsJsonArray("passengers");
        if (passengers != null && !passengers.isEmpty()) {
            line.append(" passengers=").append(passengers.size());
        }
        return line.toString();
    }

    private static int port(InterfaceCore core, FabricClientCommandSource source) {
        feedback(source, "mc-agent-interface port: " + core.port());
        feedback(source, "point a bridge at it with --mod-port " + core.port());
        return 1;
    }

    /** Open the world to the LAN so a second client (the agent) can join. */
    private static int lan(InterfaceCore core, FabricClientCommandSource source, int port, Boolean offline) {
        core.publishLan(port, offline, line -> {
            JsonObject answer = JsonParser.parseString(line).getAsJsonObject();
            if (answer.has("message")) {
                feedback(source, "mc-agent: " + answer.get("message").getAsString());
                return;
            }
            feedback(source, "LAN: " + answer.get("detail").getAsString());
        });
        return 1;
    }

    private static int caps(FabricClientCommandSource source) {
        feedback(source, "capabilities: " + InterfaceConstants.CLIENT_CAPABILITIES
                .replace("[", "").replace("]", "").replace("\"", ""));
        return 1;
    }

    private static int mark(InterfaceCore core, FabricClientCommandSource source, String text) {
        core.emitMark(text);
        feedback(source, "marked: " + text);
        return 1;
    }

    private static int recordStart(InterfaceCore core, FabricClientCommandSource source,
                                   int ticks, double radius, int interval) {
        core.applySampleStart(ticks, radius, interval);
        feedback(source, String.format(Locale.ROOT,
                "recording %d ticks, radius %.1f, interval %d -> samples.jsonl", ticks, radius, interval));
        return 1;
    }

    private static int recordStop(InterfaceCore core, FabricClientCommandSource source) {
        core.applySampleStop();
        feedback(source, "recording stopped after " + core.sampleCount() + " samples");
        return 1;
    }

    private static int help(FabricClientCommandSource source) {
        feedback(source, "/mcagent status - mod version, port, bridge clients");
        feedback(source, "/mcagent state - position, velocity, health, dimension");
        feedback(source, "/mcagent entities [radius] - nearby entities");
        feedback(source, "/mcagent port - the port a bridge should dial");
        feedback(source, "/mcagent lan [port] [offline] - open this world to the LAN");
        feedback(source, "/mcagent caps - protocol capabilities");
        feedback(source, "/mcagent mark <text> - annotate the event stream");
        feedback(source, "/mcagent record start <ticks> [radius] [interval] | record stop");
        return 1;
    }

    private static void feedback(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
    }
}
