package dev.mcagent.interfacemod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * One entity as JSON. Shared by the client and the server vantage so a bridge
 * tool does not have to care which side it is talking to.
 */
public final class EntityJson {
    private EntityJson() {
    }

    public static JsonObject toJson(Entity entity) {
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
        if (entity instanceof Player player) {
            // Without this the entity list cannot tell one player from another -
            // and a Carpet fake player is just a player as far as a client is concerned.
            object.addProperty("name", player.getName().getString());
            object.addProperty("gameMode", player.isCreative() ? "creative" : "survival");
        }
        if (entity instanceof SulfurCube cube) {
            object.addProperty("fuse", cube.getFuse());
            object.addProperty("primed", cube.isPrimed());
            object.addProperty("hasBody", cube.hasBodyItem());
            object.addProperty("readyForShearing", cube.readyForShearing());
        }
        return object;
    }
}
