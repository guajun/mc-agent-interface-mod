package dev.mcagent.interfacemod.mixin;

import dev.mcagent.interfacemod.ServerMod;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures a chat message's context at packet receipt, not at broadcast.
 *
 * Fabric's {@code ServerMessageEvents.CHAT_MESSAGE} is fired from
 * {@code PlayerList.broadcastChatMessage}, which in 26.2 runs only after the
 * asynchronous chat filter completes. If filtering is slow, the sender may
 * have moved or turned by then, so capturing there would violate the
 * receipt-time guarantee. This injects at the head of the network handler's
 * {@code handleChat}, before any filtering or chaining, and parks the capture
 * under the packet's salt. The broadcast listener later consumes that receipt
 * and emits the event with the frozen context.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Inject(method = "handleChat", at = @At("HEAD"))
    private void mcagent$captureChatReceipt(ServerboundChatPacket packet, CallbackInfo info) {
        ServerPlayer sender = ((ServerGamePacketListenerImpl) (Object) this).player;
        ServerMod.onChatReceipt(sender, packet.salt());
    }
}
