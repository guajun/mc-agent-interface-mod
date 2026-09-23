package dev.mcagent.interfacemod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public final class InterfaceCore {
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final EventSink sink;
    private final InterfaceServer server;
    private final Path sinkDir;

    private volatile boolean sampling;
    private int sampleTicksRemaining;
    private double sampleRadius;
    private int sampleInterval = 1;
    private int sampleCounter;
    private long sampleIndex;
    private int tickCounter;

    private String autoConnect;
    private int autoConnectAt = 100;
    private int autoConnectAttempts;

    public InterfaceCore(Path dir, int port) {
        this.sinkDir = dir;
        this.sink = new EventSink(dir);
        this.server = new InterfaceServer(port, this);
    }

    public void start() {
        sink.start();
        sink.addListener(server::broadcast);
        if (!server.start()) {
            emitError("bind", "no free bridge port");
            return;
        }
        try {
            Files.createDirectories(sinkDir);
            Files.writeString(sinkDir.resolve("port.txt"), Integer.toString(server.getPort()));
        } catch (IOException exception) {
            emitError("portfile", exception.toString());
        }
        emit("hello", "mc-agent-interface started port=" + server.getPort());
    }

    public void onClientTick(Minecraft client) {
        tickCounter++;
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable throwable) {
                emitError("task", throwable.toString());
            }
        }
        if (sampling && client.level != null) {
            sampleCounter++;
            if (sampleCounter % Math.max(1, sampleInterval) == 0) {
                sink.emitSample(snapshotJson(client, sampleRadius, sampleIndex++));
                if (sampleIndex % 20 == 0) {
                    emit("sample_progress", "sampled=" + sampleIndex);
                }
            }
            if (--sampleTicksRemaining <= 0) {
                sampling = false;
                emit("sample_done", "samples=" + sampleIndex);
            }
        }
        autoConnectTick(client);
    }

    /**
     * Join a server on startup when {@code -Dmcagent.autoConnect=host:port} is set.
     *
     * This is what turns a spare client into a player the agent owns: launch the
     * instance and it walks into the world by itself. It only ever acts from the
     * title screen or with no screen open, so it cannot interrupt somebody who is
     * already using the client, and it gives up after a handful of attempts.
     */
    private void autoConnectTick(Minecraft client) {
        if (autoConnect == null || autoConnect.isBlank() || autoConnectAttempts >= 10) {
            return;
        }
        if (client.level != null || tickCounter < autoConnectAt) {
            return;
        }
        Screen screen = client.gui == null ? null : client.gui.screen();
        if (screen != null && !(screen instanceof TitleScreen)) {
            return;
        }
        autoConnectAt = tickCounter + 200;
        autoConnectAttempts++;
        emit("auto_connect", "attempt=" + autoConnectAttempts + " address=" + autoConnect);
        connect(autoConnect, line -> {
        });
    }

    public void setAutoConnect(String address) {
        this.autoConnect = address;
    }

    public void handleLine(String line, Consumer<String> reply) {
        if (line.isEmpty()) {
            return;
        }
        String upper = line.toUpperCase(Locale.ROOT);
        try {
            if (upper.startsWith("CMD ") || upper.startsWith("COMMAND ")) {
                String command = line.substring(upper.startsWith("CMD ") ? 4 : 8).trim();
                submit(() -> sendCommand(command, reply));
            } else if (upper.startsWith("CHAT ") || upper.startsWith("SAY ")) {
                String message = line.substring(upper.startsWith("CHAT ") ? 5 : 4);
                submit(() -> sendChat(message, reply));
            } else if (upper.equals("STATE") || upper.equals("STATE_GET")) {
                submit(() -> reply.accept(stateJson().toString()));
            } else if (upper.equals("ENTITIES") || upper.equals("ENTITY_LIST")) {
                submit(() -> reply.accept(entitiesJson(0.0D).toString()));
            } else if (upper.startsWith("ENTITIES ") || upper.startsWith("ENTITY_LIST ")) {
                double radius = Double.parseDouble(line.substring(upper.startsWith("ENTITIES ") ? 9 : 12).trim());
                submit(() -> reply.accept(entitiesJson(radius).toString()));
            } else if (upper.equals("SAMPLE_STOP") || upper.equals("RECORD_STOP")) {
                submit(() -> {
                    applySampleStop();
                    reply.accept(ack("sample_stop", "samples=" + sampleIndex).toString());
                });
            } else if (upper.startsWith("SAMPLE_START") || upper.startsWith("RECORD_START")) {
                String[] parts = line.split("\\s+");
                int ticks = parts.length > 1 ? Integer.parseInt(parts[1]) : 200;
                double radius = parts.length > 2 ? Double.parseDouble(parts[2]) : 64.0D;
                int interval = parts.length > 3 ? Integer.parseInt(parts[3]) : 1;
                submit(() -> {
                    applySampleStart(ticks, radius, interval);
                    reply.accept(ack("sample_start", "ticks=" + sampleTicksRemaining).toString());
                });
            } else if (upper.startsWith("WAIT ")) {
                int ticks = Integer.parseInt(line.substring(5).trim());
                submit(() -> scheduleWait(ticks, reply));
            } else if (upper.equals("PING")) {
                reply.accept("{\"type\":\"pong\",\"t\":" + System.currentTimeMillis() + "}");
            } else if (upper.equals("CAPS") || upper.equals("CAPABILITIES") || upper.equals("CAPS_GET")) {
                submit(() -> reply.accept(capabilitiesJson().toString()));
            } else if (upper.equals("SCREEN")) {
                submit(() -> reply.accept(screenJson().toString()));
            } else if (upper.startsWith("CONNECT ")) {
                String address = line.substring(8).trim();
                submit(() -> connect(address, reply));
            } else if (upper.startsWith("WORLD ")) {
                String level = line.substring(6).trim();
                submit(() -> openWorld(level, reply));
            } else if (upper.equals("LAN")) {
                submit(() -> publishLan(0, null, reply));
            } else if (upper.startsWith("LAN ")) {
                String[] parts = line.substring(4).trim().split("\\s+");
                int lanPort = parts.length > 0 && !parts[0].isEmpty() ? Integer.parseInt(parts[0]) : 0;
                Boolean offline = parts.length > 1 ? parseOffline(parts[1]) : null;
                submit(() -> publishLan(lanPort, offline, reply));
            } else if (upper.startsWith("MARK ")) {
                emit("mark", line.substring(5));
                reply.accept(ack("mark", line.substring(5)).toString());
            } else if (upper.startsWith("ECHO ")) {
                reply.accept(ack("echo", line.substring(5)).toString());
            } else {
                reply.accept(errorJson("unknown command: " + line).toString());
            }
        } catch (Throwable throwable) {
            reply.accept(errorJson("bad command: " + throwable).toString());
        }
    }

    public void onGameMessage(String text, boolean overlay) {
        JsonObject object = base("game");
        object.addProperty("text", text);
        object.addProperty("overlay", overlay);
        sink.emit(object);
    }

    public void onChatMessage(String text, String sender) {
        JsonObject object = base("chat");
        object.addProperty("text", text);
        object.addProperty("sender", sender);
        sink.emit(object);
    }

    private void submit(Runnable task) {
        tasks.offer(task);
    }

    // ---------------------------------------------------------------- shared state
    // The socket protocol and the in-game commands drive the same primitives, so
    // both go through these accessors instead of formatting their own answers.

    public int port() {
        return server.getPort();
    }

    public int clientCount() {
        return server.clientCount();
    }

    public int tick() {
        return tickCounter;
    }

    public boolean isSampling() {
        return sampling;
    }

    public long sampleCount() {
        return sampleIndex;
    }

    public void applySampleStart(int ticks, double radius, int interval) {
        sampling = true;
        sampleTicksRemaining = Math.max(1, ticks);
        sampleRadius = radius;
        sampleInterval = Math.max(1, interval);
        sampleCounter = 0;
        sampleIndex = 0;
        emit("sample_start", "ticks=" + sampleTicksRemaining + " radius=" + sampleRadius
                + " interval=" + sampleInterval);
    }

    public void applySampleStop() {
        sampling = false;
    }

    public void emitMark(String text) {
        emit("mark", text);
    }

    private void scheduleWait(int ticks, Consumer<String> reply) {
        if (ticks <= 0) {
            reply.accept(ack("wait", "ticks=0").toString());
            return;
        }
        submit(() -> scheduleWait(ticks - 1, reply));
    }

    private JsonObject capabilitiesJson() {
        JsonObject object = base("capabilities");
        object.addProperty("protocol", InterfaceMod.PROTOCOL_VERSION);
        object.addProperty("mod", InterfaceMod.MOD_ID);
        object.addProperty("version", InterfaceMod.VERSION);
        object.add("capabilities", com.google.gson.JsonParser.parseString(InterfaceMod.CAPABILITIES));
        return object;
    }

    private void sendCommand(String command, Consumer<String> reply) {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            reply.accept(errorJson("no server connection").toString());
            return;
        }
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        try {
            connection.sendCommand(normalized);
            reply.accept(ack("cmd", normalized).toString());
        } catch (Throwable throwable) {
            reply.accept(errorJson("command failed: " + throwable).toString());
        }
    }

    private void sendChat(String message, Consumer<String> reply) {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            reply.accept(errorJson("no server connection").toString());
            return;
        }
        try {
            connection.sendChat(message);
            reply.accept(ack("chat", message).toString());
        } catch (Throwable throwable) {
            reply.accept(errorJson("chat failed: " + throwable).toString());
        }
    }

    private void connect(String address, Consumer<String> reply) {
        Minecraft client = Minecraft.getInstance();
        try {
            ServerAddress serverAddress = ServerAddress.parseString(address);
            ServerData serverData = new ServerData("mc-agent-target", address, ServerData.Type.OTHER);
            Screen parent = client.gui == null ? null : client.gui.screen();
            ConnectScreen.startConnecting(parent, client, serverAddress, serverData, false, null);
            reply.accept(ack("connect", address).toString());
        } catch (Throwable throwable) {
            reply.accept(errorJson("connect failed: " + throwable).toString());
        }
    }

    /**
     * Open a single-player level by its folder name, once the client is idle.
     *
     * This is the counterpart of CONNECT for single player: the agent can start
     * its own world instead of depending on the quick-play command line, which
     * loads the level before other mods finish initialising.
     */
    private void openWorld(String levelId, Consumer<String> reply) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null || client.getConnection() != null) {
            reply.accept(errorJson("already in a world").toString());
            return;
        }
        if (levelId.isEmpty()) {
            reply.accept(errorJson("no level name given").toString());
            return;
        }
        try {
            client.createWorldOpenFlows().openWorld(levelId, () -> emit("world_failed", levelId));
            reply.accept(ack("world", levelId).toString());
        } catch (Throwable throwable) {
            reply.accept(errorJson("world failed: " + throwable).toString());
        }
    }

    /**
     * Publish the integrated server on the LAN - the "Open to LAN" button, without
     * the button - and answer with the port to dial.
     *
     * This is what lets an agent be a player of its own in a single-player world:
     * the owner's client hosts, the agent's client joins under a separate name, and
     * neither of them touches the other's account or inventory.
     *
     * Calling it while already published is a status query, so a caller can poll
     * until the real port is known.
     */
    public void publishLan(int port, Boolean offline, Consumer<String> reply) {
        Minecraft client = Minecraft.getInstance();
        if (!client.hasSingleplayerServer()) {
            reply.accept(errorJson("no single-player world is open").toString());
            return;
        }
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            reply.accept(errorJson("no single-player server").toString());
            return;
        }
        if (offline != null) {
            // A spare client with no Mojang session (the agent's own player) cannot
            // pass the session check, so the host can deliberately accept offline
            // names. Only ever meaningful on a LAN.
            boolean useAuthentication = !offline;
            server.execute(() -> server.setUsesAuthentication(useAuthentication));
        }
        String address = server.getLocalIp() == null ? "localhost" : server.getLocalIp();
        if (server.isPublished()) {
            reply.accept(ack("lan", "port=" + server.getPort() + " address=" + address
                    + " online=" + server.usesAuthentication()).toString());
            return;
        }
        try {
            boolean published = server.publishServer(
                    MinecraftServer.MultiplayerScope.LAN,
                    server.getWorldData().getGameType(),
                    server.getWorldData().isAllowCommands(),
                    Math.max(0, port));
            if (!published) {
                reply.accept(errorJson("publishServer refused").toString());
                return;
            }
            emit("lan", "port=" + server.getPort() + " address=" + address);
            reply.accept(ack("lan", "port=" + server.getPort() + " address=" + address
                    + " gamemode=" + server.getWorldData().getGameType().getName()
                    + " cheats=" + server.getWorldData().isAllowCommands()
                    + " online=" + server.usesAuthentication()).toString());
        } catch (Throwable throwable) {
            reply.accept(errorJson("lan failed: " + throwable).toString());
        }
    }

    private static Boolean parseOffline(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        if (value.equals("offline") || value.equals("false") || value.equals("0")) {
            return Boolean.TRUE;
        }
        if (value.equals("online") || value.equals("true") || value.equals("1")) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("expected online or offline, got " + token);
    }

    public JsonObject screenJson() {
        Minecraft client = Minecraft.getInstance();
        JsonObject object = base("screen");
        object.addProperty("tick", tickCounter);
        Screen screen = client.gui == null ? null : client.gui.screen();
        object.addProperty("screen", screen == null ? "null" : screen.getClass().getName());
        object.addProperty("title", screen == null ? "" : screen.getTitle().getString());
        object.addProperty("narration", screen == null ? "" : screen.getNarrationMessage().getString());
        object.addProperty("inWorld", client.player != null && client.level != null);
        return object;
    }

    public JsonObject stateJson() {
        Minecraft client = Minecraft.getInstance();
        JsonObject object = base("state");
        object.addProperty("protocol", InterfaceMod.PROTOCOL_VERSION);
        object.addProperty("modVersion", InterfaceMod.VERSION);
        object.addProperty("tick", tickCounter);
        object.addProperty("clients", server.clientCount());
        object.addProperty("bridgePort", server.getPort());
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        object.addProperty("inWorld", player != null && level != null);
        if (player != null) {
            object.addProperty("name", player.getName().getString());
            object.addProperty("x", player.getX());
            object.addProperty("y", player.getY());
            object.addProperty("z", player.getZ());
            object.addProperty("vx", player.getDeltaMovement().x);
            object.addProperty("vy", player.getDeltaMovement().y);
            object.addProperty("vz", player.getDeltaMovement().z);
            object.addProperty("yaw", player.getYRot());
            object.addProperty("pitch", player.getXRot());
            object.addProperty("health", player.getHealth());
            object.addProperty("onGround", player.onGround());
        }
        if (level != null) {
            object.addProperty("dimension", level.dimension().toString());
            object.addProperty("entityCount", level.getEntityCount());
        }
        object.addProperty("server", String.valueOf(client.getCurrentServer()));
        return object;
    }

    public JsonObject entitiesJson(double radius) {
        Minecraft client = Minecraft.getInstance();
        JsonObject object = base("entities");
        object.addProperty("tick", tickCounter);
        object.addProperty("radius", radius);
        JsonArray array = new JsonArray();
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player != null && level != null) {
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            double radiusSquared = radius * radius;
            for (Entity entity : level.entitiesForRendering()) {
                double dx = entity.getX() - px;
                double dy = entity.getY() - py;
                double dz = entity.getZ() - pz;
                if (radius > 0.0D && dx * dx + dy * dy + dz * dz > radiusSquared) {
                    continue;
                }
                array.add(entityJson(entity));
            }
        }
        object.add("entities", array);
        return object;
    }

    private JsonObject snapshotJson(Minecraft client, double radius, long index) {
        JsonObject object = base("sample");
        object.addProperty("index", index);
        object.addProperty("tick", tickCounter);
        object.addProperty("millis", System.currentTimeMillis());
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        JsonArray array = new JsonArray();
        if (player != null && level != null) {
            JsonObject playerObject = new JsonObject();
            playerObject.addProperty("x", player.getX());
            playerObject.addProperty("y", player.getY());
            playerObject.addProperty("z", player.getZ());
            playerObject.addProperty("vx", player.getDeltaMovement().x);
            playerObject.addProperty("vy", player.getDeltaMovement().y);
            playerObject.addProperty("vz", player.getDeltaMovement().z);
            object.add("player", playerObject);
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            double radiusSquared = radius * radius;
            for (Entity entity : level.entitiesForRendering()) {
                double dx = entity.getX() - px;
                double dy = entity.getY() - py;
                double dz = entity.getZ() - pz;
                if (radius > 0.0D && dx * dx + dy * dy + dz * dz > radiusSquared) {
                    continue;
                }
                array.add(entityJson(entity));
            }
        }
        object.add("entities", array);
        return object;
    }

    private JsonObject entityJson(Entity entity) {
        JsonObject object = new JsonObject();
        object.addProperty("id", entity.getId());
        object.addProperty("uuid", entity.getStringUUID());
        object.addProperty("type", EntityType.getKey(entity.getType()).toString());
        object.addProperty("class", entity.getClass().getSimpleName());
        object.addProperty("x", entity.getX());
        object.addProperty("y", entity.getY());
        object.addProperty("z", entity.getZ());
        object.addProperty("vx", entity.getDeltaMovement().x);
        object.addProperty("vy", entity.getDeltaMovement().y);
        object.addProperty("vz", entity.getDeltaMovement().z);
        object.addProperty("yaw", entity.getYRot());
        object.addProperty("pitch", entity.getXRot());
        object.addProperty("alive", entity.isAlive());
        object.addProperty("removed", entity.isRemoved());
        object.addProperty("invulnerable", entity.isInvulnerable());
        object.addProperty("onGround", entity.onGround());
        object.addProperty("vehicle", entity.getVehicle() == null ? -1 : entity.getVehicle().getId());
        object.addProperty("isVehicle", entity.isVehicle());
        JsonArray passengers = new JsonArray();
        for (Entity passenger : entity.getPassengers()) {
            passengers.add(passenger.getId());
        }
        object.add("passengers", passengers);
        if (entity instanceof LivingEntity living) {
            object.addProperty("health", living.getHealth());
            object.addProperty("maxHealth", living.getMaxHealth());
            ItemStack body = living.getItemBySlot(EquipmentSlot.BODY);
            object.addProperty("bodyEmpty", body.isEmpty());
            if (!body.isEmpty()) {
                object.addProperty("bodyItem", body.getItem().getDescriptionId());
                object.addProperty("bodyCount", body.getCount());
            }
        }
        if (entity instanceof SulfurCube cube) {
            object.addProperty("fuse", cube.getFuse());
            object.addProperty("primed", cube.isPrimed());
            object.addProperty("hasBody", cube.hasBodyItem());
            object.addProperty("readyForShearing", cube.readyForShearing());
        }
        return object;
    }

    private JsonObject base(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.addProperty("millis", System.currentTimeMillis());
        return object;
    }

    private JsonObject ack(String type, String detail) {
        JsonObject object = base(type + "_ack");
        object.addProperty("detail", detail);
        return object;
    }

    private JsonObject errorJson(String message) {
        JsonObject object = base("error");
        object.addProperty("message", message);
        return object;
    }

    private void emit(String type, String text) {
        JsonObject object = base(type);
        object.addProperty("text", text);
        sink.emit(object);
    }

    private void emitError(String type, String message) {
        JsonObject object = base(type + "_error");
        object.addProperty("message", message);
        sink.emit(object);
    }
}

