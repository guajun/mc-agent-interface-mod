package dev.mcagent.interfacemod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The server vantage: the same idea as {@link InterfaceCore}, but running inside
 * the server process instead of the client, so what it reports is authoritative
 * and what it can reach is the whole loaded world.
 *
 * The reason this exists at all is snapshots: a save file carries entity NBT but
 * not the entity tick order, and that order is rebuilt at load time. Only code
 * inside the process can read it, so only this vantage can fork a live world
 * faithfully.
 */
public final class ServerCore implements LineHandler {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String[] PLAYER_OPERATIONS =
            {"PLAYER", "PLAYER_GET", "PLAYER_CONTEXT", "PLAYER_STATE"};
    private static Field tickListField;

    private final MinecraftServer server;
    private final Path dir;
    private final EventSink sink;
    private final InterfaceServer socket;
    private final ConcurrentLinkedQueue<Wait> waits = new ConcurrentLinkedQueue<>();

    public ServerCore(MinecraftServer server, Path dir, int basePort) {
        this.server = server;
        this.dir = dir;
        this.sink = new EventSink(dir);
        this.socket = new InterfaceServer(basePort, this, "server", InterfaceConstants.SERVER_CAPABILITIES);
    }

    public void start() {
        sink.start();
        sink.addListener(socket::broadcast);
        if (!socket.start()) {
            emitError("bind", "no free server port");
            return;
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("port.txt"), Integer.toString(socket.getPort()));
        } catch (IOException exception) {
            emitError("portfile", exception.toString());
        }
        emit("hello", "mc-agent-interface server started port=" + socket.getPort()
                + " dir=" + dir.toAbsolutePath());
    }

    public void stop() {
        socket.stop();
        sink.stop();
    }

    /** Called once per server tick from the lifecycle event. */
    public void onTick() {
        for (Wait wait : waits) {
            if (--wait.remaining <= 0) {
                waits.remove(wait);
                wait.reply.accept(ack("wait", "ticks=0").toString());
            }
        }
    }

    // ------------------------------------------------------------------ protocol

    @Override
    public void handleLine(String line, Consumer<String> reply) {
        if (line.isEmpty()) {
            return;
        }
        String upper = line.toUpperCase(Locale.ROOT);
        try {
            String playerQuery = playerQuery(upper, line);
            if (playerQuery != null) {
                if (playerQuery.isEmpty()) {
                    reply.accept(errorJson("PLAYER needs a player uuid or name").toString());
                } else {
                    submit(reply, () -> reply.accept(playerJson(playerQuery).toString()));
                }
            } else if (upper.equals("PING")) {
                reply.accept("{\"type\":\"pong\",\"t\":" + System.currentTimeMillis() + "}");
            } else if (upper.startsWith("ECHO ")) {
                reply.accept(ack("echo", line.substring(5)).toString());
            } else if (upper.equals("CAPS") || upper.equals("CAPABILITIES") || upper.equals("CAPS_GET")) {
                reply.accept(capabilitiesJson().toString());
            } else if (upper.equals("STATE") || upper.equals("STATE_GET")) {
                submit(reply, () -> reply.accept(stateJson().toString()));
            } else if (upper.equals("ENTITIES") || upper.equals("ENTITY_LIST")) {
                submit(reply, () -> reply.accept(entitiesJson(0.0D).toString()));
            } else if (upper.startsWith("ENTITIES ") || upper.startsWith("ENTITY_LIST ")) {
                double radius = Double.parseDouble(line.substring(upper.startsWith("ENTITIES ") ? 9 : 12).trim());
                submit(reply, () -> reply.accept(entitiesJson(radius).toString()));
            } else if (upper.startsWith("CMD ") || upper.startsWith("COMMAND ")) {
                String command = line.substring(upper.startsWith("CMD ") ? 4 : 8).trim();
                submit(reply, () -> runCommand(command, reply));
            } else if (upper.equals("SNAPSHOTS")) {
                submit(reply, () -> reply.accept(listSnapshots().toString()));
            } else if (upper.equals("SNAPSHOT") || upper.startsWith("SNAPSHOT ")) {
                String rest = upper.equals("SNAPSHOT") ? "" : line.substring(9).trim();
                submit(reply, () -> snapshot(rest, reply));
            } else if (upper.startsWith("WAIT ")) {
                int ticks = Integer.parseInt(line.substring(5).trim());
                if (ticks <= 0) {
                    reply.accept(ack("wait", "ticks=0").toString());
                } else {
                    waits.add(new Wait(ticks, reply));
                }
            } else if (upper.startsWith("MARK ")) {
                String text = line.substring(5);
                emit("mark", text);
                reply.accept(ack("mark", text).toString());
            } else {
                reply.accept(errorJson("unknown command: " + line).toString());
            }
        } catch (Throwable throwable) {
            reply.accept(errorJson("bad command: " + throwable).toString());
        }
    }

    /** Run on the server thread, and never let a task exception kill the tick loop. */
    private void submit(Consumer<String> reply, Runnable task) {
        try {
            server.execute(() -> {
                try {
                    task.run();
                } catch (Throwable throwable) {
                    reply.accept(errorJson("task failed: " + throwable).toString());
                }
            });
        } catch (Throwable throwable) {
            reply.accept(errorJson("server is not accepting work: " + throwable).toString());
        }
    }

    // --------------------------------------------------------------------- state

    private JsonObject stateJson() {
        JsonObject object = base("state");
        object.addProperty("instance", "server");
        object.addProperty("protocol", InterfaceConstants.PROTOCOL_VERSION);
        object.addProperty("modVersion", InterfaceConstants.VERSION);
        object.addProperty("tick", server.getTickCount());
        object.addProperty("inWorld", true);
        object.addProperty("levelName", server.getWorldData().getLevelName());
        object.addProperty("worldDir", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().toString());
        object.addProperty("players", server.getPlayerList().getPlayers().size());
        object.addProperty("serverVersion", server.getServerVersion());
        JsonArray playerList = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", player.getName().getString());
            entry.addProperty("uuid", player.getStringUUID());
            entry.addProperty("x", player.getX());
            entry.addProperty("y", player.getY());
            entry.addProperty("z", player.getZ());
            entry.addProperty("dimension", player.level().dimension().identifier().toString());
            playerList.add(entry);
        }
        object.add("playerList", playerList);
        JsonArray levels = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("dimension", level.dimension().identifier().toString());
            entry.addProperty("entities", tickOrder(level).size());
            levels.add(entry);
        }
        object.add("levels", levels);
        return object;
    }

    private JsonObject capabilitiesJson() {
        JsonObject object = base("capabilities");
        object.addProperty("protocol", InterfaceConstants.PROTOCOL_VERSION);
        object.addProperty("mod", InterfaceConstants.MOD_ID);
        object.addProperty("version", InterfaceConstants.VERSION);
        object.addProperty("instance", "server");
        object.add("capabilities", JsonParser.parseString(InterfaceConstants.SERVER_CAPABILITIES));
        return object;
    }

    // --------------------------------------------------------------------- players

    /**
     * One online player's server-known context, plus where they are looking.
     *
     * The identifier is a stable UUID first (dashed or plain) and a name second,
     * so a caller that has the id never has to trust a display name. The view
     * target is a server-side raycast from the player's own eye and look vector
     * at request time; the server never asks a client's UI what the crosshair is
     * over.
     */
    private JsonObject playerJson(String query) {
        JsonObject object = base("player");
        object.addProperty("instance", "server");
        object.addProperty("protocol", InterfaceConstants.PROTOCOL_VERSION);
        object.addProperty("modVersion", InterfaceConstants.VERSION);
        object.addProperty("tick", server.getTickCount());
        object.addProperty("query", query);

        UUID uuid = parseUuid(query);
        ServerPlayer player = uuid == null ? null : server.getPlayerList().getPlayer(uuid);
        String matchedBy = "uuid";
        if (player == null) {
            player = server.getPlayerList().getPlayerByName(query);
            matchedBy = "name";
            if (player == null) {
                for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                    if (online.getName().getString().equalsIgnoreCase(query)) {
                        player = online;
                        break;
                    }
                }
            }
        }
        if (player == null) {
            object.addProperty("found", false);
            object.addProperty("error", "no online player matches " + query);
            return object;
        }

        ServerLevel level = player.level();
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);

        object.addProperty("found", true);
        object.addProperty("matchedBy", matchedBy);
        JsonObject info = EntityJson.toJson(player);
        info.addProperty("dimension", level.dimension().identifier().toString());
        info.addProperty("gameMode", player.gameMode().getSerializedName());
        info.add("eye", vector(eye));
        object.add("player", info);

        JsonObject view = new JsonObject();
        view.add("eye", vector(eye));
        view.add("direction", vector(look));
        view.addProperty("blockRange", player.blockInteractionRange());
        view.addProperty("entityRange", player.entityInteractionRange());
        view.add("target", viewTargetJson(player, level, eye, look));
        object.add("view", view);
        return object;
    }

    /**
     * The server-side equivalent of the crosshair pick: blocks along the look
     * vector up to the block interaction range, entities up to the entity
     * interaction range, whichever is closer. This mirrors the vanilla client's
     * pick, so the answer is what the player would see - except it is computed
     * from the authoritative server state, not from client UI.
     */
    private JsonObject viewTargetJson(ServerPlayer player, ServerLevel level, Vec3 eye, Vec3 look) {
        double blockRange = player.blockInteractionRange();
        double entityRange = player.entityInteractionRange();
        double maxRange = Math.max(blockRange, entityRange);
        HitResult blockHit = player.pick(maxRange, 1.0F, false);
        double blockDistanceSq = blockHit.getLocation().distanceToSqr(eye);

        double entityLimit = maxRange;
        double entityLimitSq = Mth.square(maxRange);
        if (blockHit.getType() != HitResult.Type.MISS) {
            entityLimit = Math.sqrt(blockDistanceSq);
            entityLimitSq = blockDistanceSq;
        }

        Vec3 end = eye.add(look.scale(entityLimit));
        AABB search = player.getBoundingBox().expandTowards(look.scale(entityLimit))
                .inflate(1.0D, 1.0D, 1.0D);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player, eye, end, search, EntitySelector.CAN_BE_PICKED, entityLimitSq);

        HitResult hit;
        if (entityHit != null && entityHit.getLocation().distanceToSqr(eye) < blockDistanceSq) {
            hit = clampToRange(entityHit, eye, entityRange);
        } else {
            hit = clampToRange(blockHit, eye, blockRange);
        }

        JsonObject target = new JsonObject();
        target.add("hit", vector(hit.getLocation()));
        target.addProperty("distance", Math.sqrt(hit.getLocation().distanceToSqr(eye)));
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult block) {
            BlockPos pos = block.getBlockPos();
            BlockState state = level.getBlockState(pos);
            target.addProperty("type", "block");
            JsonObject info = new JsonObject();
            info.addProperty("x", pos.getX());
            info.addProperty("y", pos.getY());
            info.addProperty("z", pos.getZ());
            info.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            info.addProperty("name", state.getBlock().getName().getString());
            info.addProperty("face", block.getDirection().getName());
            info.addProperty("inside", block.isInside());
            target.add("block", info);
        } else if (hit instanceof EntityHitResult entity) {
            target.addProperty("type", "entity");
            target.add("entity", EntityJson.toJson(entity.getEntity()));
        } else {
            target.addProperty("type", "miss");
        }
        return target;
    }

    /** The client pick turns an out-of-reach hit into a miss; mirror that. */
    private static HitResult clampToRange(HitResult hit, Vec3 eye, double range) {
        Vec3 location = hit.getLocation();
        if (location.closerThan(eye, range)) {
            return hit;
        }
        Vec3 difference = location.subtract(eye);
        return BlockHitResult.miss(location,
                Direction.getApproximateNearest(difference.x, difference.y, difference.z),
                BlockPos.containing(location));
    }

    private static UUID parseUuid(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException ignored) {
            // not a dashed uuid; a plain 32-char form is accepted below
        }
        if (trimmed.matches("(?i)[0-9a-f]{32}")) {
            String dashed = trimmed.substring(0, 8) + "-" + trimmed.substring(8, 12) + "-"
                    + trimmed.substring(12, 16) + "-" + trimmed.substring(16, 20) + "-"
                    + trimmed.substring(20);
            try {
                return UUID.fromString(dashed);
            } catch (IllegalArgumentException ignored) {
                // not a uuid after all
            }
        }
        return null;
    }

    /**
     * The argument of a player operation, or null when the line is not one.
     * Empty means the operation was sent without a player.
     */
    private static String playerQuery(String upper, String line) {
        for (String operation : PLAYER_OPERATIONS) {
            if (upper.equals(operation)) {
                return "";
            }
            if (upper.startsWith(operation + " ")) {
                return line.substring(operation.length()).trim();
            }
        }
        return null;
    }

    private static JsonArray vector(Vec3 vector) {
        JsonArray array = new JsonArray();
        array.add(vector.x);
        array.add(vector.y);
        array.add(vector.z);
        return array;
    }

    private void runCommand(String command, Consumer<String> reply) {
        List<String> lines = new ArrayList<>();
        CommandSource collecting = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                lines.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack source = server.createCommandSourceStack().withSource(collecting);
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        server.getCommands().performPrefixedCommand(source, normalized);
        JsonObject answer = ack("cmd", normalized);
        JsonArray output = new JsonArray();
        for (String text : lines) {
            output.add(text);
            // Also push it as a game event so the bridge's mc_command_output works
            // against a server exactly as it does against a client.
            emit("game", text);
        }
        answer.add("output", output);
        reply.accept(answer.toString());
    }

    // ------------------------------------------------------------------ entities

    private JsonObject entitiesJson(double radius) {
        JsonObject object = base("entities");
        object.addProperty("instance", "server");
        object.addProperty("tick", server.getTickCount());
        object.addProperty("radius", radius);
        JsonArray array = new JsonArray();
        ServerLevel level = primaryLevel();
        if (level != null) {
            double[] origin = radius > 0.0D ? playerOrigin(level) : null;
            if (radius > 0.0D && origin == null) {
                return errorJson("a radius needs at least one player; use ENTITIES for everything");
            }
            for (Entity entity : tickOrder(level)) {
                if (origin != null) {
                    double dx = entity.getX() - origin[0];
                    double dy = entity.getY() - origin[1];
                    double dz = entity.getZ() - origin[2];
                    if (dx * dx + dy * dy + dz * dz > radius * radius) {
                        continue;
                    }
                }
                array.add(EntityJson.toJson(entity));
            }
        }
        object.addProperty("dimension", level == null ? "" : level.dimension().identifier().toString());
        object.add("entities", array);
        return object;
    }

    /**
     * The order the server ticks entities in. EntityTickList keeps its entities in
     * an insertion-ordered map, so this is the load/spawn order - the thing a save
     * file does not record and the reason snapshots exist.
     */
    @SuppressWarnings("unchecked")
    private List<Entity> tickOrder(ServerLevel level) {
        List<Entity> entities = new ArrayList<>();
        try {
            if (tickListField == null) {
                tickListField = ServerLevel.class.getDeclaredField("entityTickList");
                tickListField.setAccessible(true);
            }
            EntityTickList list = (EntityTickList) tickListField.get(level);
            if (list != null) {
                list.forEach(entity -> {
                    // The tick list can still hold entities that are dying or already
                    // removed (it is only cleaned up as the ticking loop runs). A
                    // snapshot is the world that exists, so drop them here rather
                    // than handing a caller ghosts.
                    if (!entity.isRemoved() && entity.isAlive()) {
                        entities.add(entity);
                    }
                });
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("cannot read the entity tick order: " + exception, exception);
        }
        return entities;
    }

    private ServerLevel primaryLevel() {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (!players.isEmpty()) {
            return players.get(0).level();
        }
        return server.overworld();
    }

    private double[] playerOrigin(ServerLevel level) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == level) {
                return new double[] {player.getX(), player.getY(), player.getZ()};
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ snapshots

    private void snapshot(String rest, Consumer<String> reply) {
        double radius = 0.0D;
        String name = null;
        String dimension = null;
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        int index = 0;
        if (index < parts.length && parts[index].matches("[-+]?\\d+(\\.\\d+)?")) {
            radius = Double.parseDouble(parts[index++]);
        }
        if (index < parts.length && !parts[index].contains(":")) {
            name = parts[index++];
        }
        if (index < parts.length) {
            dimension = parts[index];
        }
        if (name == null) {
            name = "snap-" + server.getTickCount();
        }

        ServerLevel level = dimension == null
                ? primaryLevel()
                : server.getLevel(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        Identifier.parse(dimension)));
        if (level == null) {
            reply.accept(errorJson("no such dimension: " + dimension).toString());
            return;
        }

        double[] origin = radius > 0.0D ? playerOrigin(level) : null;
        if (radius > 0.0D && origin == null) {
            reply.accept(errorJson("a radius needs a player in that dimension; use radius 0 for everything").toString());
            return;
        }

        List<Entity> entities = tickOrder(level);
        if (origin != null) {
            double radiusSquared = radius * radius;
            List<Entity> within = new ArrayList<>();
            for (Entity entity : entities) {
                double dx = entity.getX() - origin[0];
                double dy = entity.getY() - origin[1];
                double dz = entity.getZ() - origin[2];
                if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                    within.add(entity);
                }
            }
            entities = within;
        }

        // A snapshot is "the world state that can be put back". A player cannot be
        // recreated with /summon, so players are recorded in meta (name, uuid,
        // position) and kept out of the entity list - otherwise a restore could
        // never reproduce the recorded order hash.
        List<Entity> players = new ArrayList<>();
        List<Entity> restorable = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity instanceof ServerPlayer) {
                players.add(entity);
            } else {
                restorable.add(entity);
            }
        }
        entities = restorable;

        Path snapshotDir = dir.resolve("snapshots").resolve(name);
        Path entitiesFile = snapshotDir.resolve("entities.jsonl");
        boolean replaced = Files.exists(entitiesFile);
        long bytes;
        int order = 0;
        try {
            Files.createDirectories(snapshotDir);
            try (BufferedWriter writer = Files.newBufferedWriter(entitiesFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Entity entity : entities) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("order", order++);
                    entry.addProperty("uuid", entity.getStringUUID());
                    entry.addProperty("type", EntityType.getKey(entity.getType()).toString());
                    entry.addProperty("entityId", entity.getId());
                    JsonArray position = new JsonArray();
                    position.add(entity.getX());
                    position.add(entity.getY());
                    position.add(entity.getZ());
                    entry.add("pos", position);
                    JsonArray velocity = new JsonArray();
                    velocity.add(entity.getDeltaMovement().x);
                    velocity.add(entity.getDeltaMovement().y);
                    velocity.add(entity.getDeltaMovement().z);
                    entry.add("vel", velocity);
                    entry.addProperty("yaw", entity.getYRot());
                    entry.addProperty("pitch", entity.getXRot());
                    entry.addProperty("nbt", snbtOf(entity));
                    JsonArray passengers = new JsonArray();
                    for (Entity passenger : entity.getPassengers()) {
                        passengers.add(passenger.getStringUUID());
                    }
                    entry.add("passengers", passengers);
                    if (entity.getVehicle() == null) {
                        entry.add("vehicle", com.google.gson.JsonNull.INSTANCE);
                    } else {
                        entry.addProperty("vehicle", entity.getVehicle().getStringUUID());
                    }
                    // Passengers come back with their vehicle (their NBT is nested
                    // inside it), so a restore has to skip them rather than summon
                    // them twice. The flag says which ones.
                    entry.addProperty("restorable", entity.getVehicle() == null);
                    writer.write(GSON.toJson(entry));
                    writer.newLine();
                }
            }
            bytes = Files.size(entitiesFile);
        } catch (IOException exception) {
            reply.accept(errorJson("snapshot failed: " + exception).toString());
            return;
        }

        String hash = orderHash(entities);
        JsonArray playerInfo = new JsonArray();
        for (Entity player : players) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", player.getName().getString());
            entry.addProperty("uuid", player.getStringUUID());
            entry.addProperty("x", player.getX());
            entry.addProperty("y", player.getY());
            entry.addProperty("z", player.getZ());
            playerInfo.add(entry);
        }
        JsonObject meta = base("meta");
        meta.addProperty("protocol", InterfaceConstants.PROTOCOL_VERSION);
        meta.addProperty("mod", InterfaceConstants.MOD_ID);
        meta.addProperty("modVersion", InterfaceConstants.VERSION);
        meta.addProperty("minecraft", "26.2");
        meta.addProperty("instance", "server");
        meta.addProperty("tick", server.getTickCount());
        meta.addProperty("dimension", level.dimension().identifier().toString());
        meta.addProperty("radius", radius);
        meta.addProperty("entities", entities.size());
        meta.addProperty("playersSkipped", players.size());
        meta.add("players", playerInfo);
        meta.addProperty("orderHash", hash);
        meta.addProperty("createdAt", System.currentTimeMillis());
        meta.addProperty("worldDir", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().toString());
        meta.addProperty("levelName", server.getWorldData().getLevelName());
        try {
            Files.writeString(snapshotDir.resolve("meta.json"), GSON.toJson(meta), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            reply.accept(errorJson("snapshot meta failed: " + exception).toString());
            return;
        }

        JsonObject answer = ack("snapshot", "name=" + name + " entities=" + entities.size());
        answer.addProperty("id", name);
        answer.addProperty("dir", snapshotDir.toAbsolutePath().toString());
        answer.addProperty("entities", entities.size());
        answer.addProperty("orderHash", hash);
        answer.addProperty("tick", server.getTickCount());
        answer.addProperty("dimension", level.dimension().identifier().toString());
        answer.addProperty("bytes", bytes);
        answer.addProperty("replaced", replaced);
        emit("snapshot", "name=" + name + " entities=" + entities.size() + " orderHash=" + hash);
        reply.accept(answer.toString());
    }

    private JsonObject listSnapshots() {
        JsonObject object = base("snapshots");
        JsonArray array = new JsonArray();
        Path root = dir.resolve("snapshots");
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(Files::isDirectory).sorted().forEach(path -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", path.getFileName().toString());
                    Path meta = path.resolve("meta.json");
                    if (Files.isRegularFile(meta)) {
                        try {
                            JsonObject parsed = JsonParser.parseString(Files.readString(meta))
                                    .getAsJsonObject();
                            for (String key : new String[] {"entities", "tick", "orderHash", "createdAt",
                                    "dimension", "radius"}) {
                                if (parsed.has(key)) {
                                    entry.add(key, parsed.get(key));
                                }
                            }
                        } catch (IOException | RuntimeException ignored) {
                            entry.addProperty("metaError", "unreadable");
                        }
                    }
                    array.add(entry);
                });
            } catch (IOException exception) {
                return errorJson("cannot list snapshots: " + exception);
            }
        }
        object.add("snapshots", array);
        return object;
    }

    /** Entity#saveWithoutId through the value-IO API, as one line of SNBT. */
    private String snbtOf(Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                server.registryAccess());
        entity.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        return Snbt.write(tag);
    }

    private static String orderHash(List<Entity> entities) {
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < entities.size(); index++) {
            if (index > 0) {
                joined.append(':');
            }
            joined.append(entities.get(index).getStringUUID());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                hex.append(String.format("%02x", digest[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }

    // ---------------------------------------------------------------------- sink

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

    private static final class Wait {
        private int remaining;
        private final Consumer<String> reply;

        private Wait(int ticks, Consumer<String> reply) {
            this.remaining = ticks;
            this.reply = reply;
        }
    }
}
