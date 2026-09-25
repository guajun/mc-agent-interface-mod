package dev.mcagent.interfacemod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The server-side view ray at capture time: what the player's eye line hit,
 * computed from server-known position and rotation only. The client crosshair,
 * camera and screen are never consulted.
 *
 * One of {@link #MISS}, {@link #BLOCK} or {@link #ENTITY}; the extra fields
 * that do not apply to the type stay null.
 */
public final class ViewTarget {
    public static final String MISS = "miss";
    public static final String BLOCK = "block";
    public static final String ENTITY = "entity";

    public final String type;
    public final double distance;
    public final String block;
    public final int[] blockPos;
    public final String face;
    public final double[] hitPos;
    public final String entityUuid;
    public final String entityType;
    public final String entityName;

    private ViewTarget(String type, double distance, String block, int[] blockPos, String face,
                       double[] hitPos, String entityUuid, String entityType, String entityName) {
        this.type = type;
        this.distance = distance;
        this.block = block;
        this.blockPos = blockPos == null ? null : blockPos.clone();
        this.face = face;
        this.hitPos = hitPos == null ? null : hitPos.clone();
        this.entityUuid = entityUuid;
        this.entityType = entityType;
        this.entityName = entityName;
    }

    public static ViewTarget miss(double distance) {
        return new ViewTarget(MISS, distance, null, null, null, null, null, null, null);
    }

    public static ViewTarget block(String block, int[] blockPos, String face, double distance, double[] hitPos) {
        return new ViewTarget(BLOCK, distance, block, blockPos, face, hitPos, null, null, null);
    }

    public static ViewTarget entity(String uuid, String type, String name, double distance, double[] hitPos) {
        return new ViewTarget(ENTITY, distance, null, null, null, hitPos, uuid, type, name);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.addProperty("distance", distance);
        if (BLOCK.equals(type)) {
            object.addProperty("block", block);
            JsonArray position = new JsonArray();
            position.add(blockPos[0]);
            position.add(blockPos[1]);
            position.add(blockPos[2]);
            object.add("pos", position);
            object.addProperty("face", face);
        } else if (ENTITY.equals(type)) {
            JsonObject target = new JsonObject();
            target.addProperty("uuid", entityUuid);
            target.addProperty("type", entityType);
            target.addProperty("name", entityName);
            object.add("entity", target);
        }
        if (hitPos != null) {
            JsonArray hit = new JsonArray();
            hit.add(hitPos[0]);
            hit.add(hitPos[1]);
            hit.add(hitPos[2]);
            object.add("hit", hit);
        }
        return object;
    }
}
