package dev.mcagent.interfacemod;

import com.google.gson.JsonObject;

/**
 * One player's server-known context, frozen at capture time.
 *
 * A chat bundle is built the moment the server receives a chat message and is
 * never recomputed afterwards: position, rotation and the view ray describe
 * the sender when that message arrived, even if the player keeps moving while
 * an agent handles the event.
 *
 * The bundle is deliberately small - identity, transform, one ray result - and
 * carries no entity snapshot or world data. Anything larger stays behind the
 * regular server-vantage requests.
 */
public final class PlayerContext {
    /** Monotonic chat event sequence, null for an ad-hoc capture. */
    public final Long seq;
    /** Opaque correlation id; never derived from a player name. */
    public final String contextId;
    public final long capturedAtMillis;
    public final int tick;
    public final String schema;
    public final String uuid;
    public final String name;
    public final String dimension;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final float pitch;
    public final ViewTarget view;

    public PlayerContext(Long seq, String contextId, long capturedAtMillis, int tick, String schema,
                         String uuid, String name, String dimension, double x, double y, double z,
                         float yaw, float pitch, ViewTarget view) {
        this.seq = seq;
        this.contextId = contextId;
        this.capturedAtMillis = capturedAtMillis;
        this.tick = tick;
        this.schema = schema;
        this.uuid = uuid;
        this.name = name;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.view = view;
    }

    /** The full stored bundle, as returned by a successful CONTEXT fetch. */
    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("schema", schema);
        object.addProperty("context_id", contextId);
        if (seq != null) {
            object.addProperty("seq", seq);
        }
        object.addProperty("capturedAt", capturedAtMillis);
        object.addProperty("tick", tick);
        object.addProperty("uuid", uuid);
        object.addProperty("name", name);
        object.addProperty("dimension", dimension);
        object.addProperty("x", x);
        object.addProperty("y", y);
        object.addProperty("z", z);
        object.addProperty("yaw", yaw);
        object.addProperty("pitch", pitch);
        object.add("view", view.toJson());
        return object;
    }

    /**
     * The compact inline summary the chat event carries. It is the same data
     * the event describes, minus the ray detail and cache bookkeeping, so the
     * event stays readable without a fetch.
     */
    public JsonObject summaryJson() {
        JsonObject object = new JsonObject();
        object.addProperty("schema", schema);
        object.addProperty("uuid", uuid);
        object.addProperty("name", name);
        object.addProperty("tick", tick);
        object.addProperty("dimension", dimension);
        object.addProperty("x", x);
        object.addProperty("y", y);
        object.addProperty("z", z);
        object.addProperty("yaw", yaw);
        object.addProperty("pitch", pitch);
        JsonObject viewSummary = new JsonObject();
        viewSummary.addProperty("type", view.type);
        object.add("view", viewSummary);
        return object;
    }
}
