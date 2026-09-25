package dev.mcagent.interfacemod.mixin;

import dev.mcagent.interfacemod.ServerMod;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures a chat message's context on the server thread, before the
 * asynchronous filter.
 *
 * {@code handleChat} itself runs on the Netty thread - {@code
 * Connection.channelRead0} invokes {@code Packet.handle} directly - and only
 * dispatches its continuation to the server thread through {@code tryHandleChat
 * -> server.execute}. That continuation calls {@code getSignedMessage} to build
 * the {@link PlayerChatMessage}, then starts {@code filterTextPacket}. This
 * injects at the return of {@code getSignedMessage}: server thread, in message
 * order, before any filtering, and with the message identity available.
 *
 * The parked key is built from the message link and signed body, which
 * {@code withUnsignedContent} and {@code filter} preserve into the broadcast
 * message. That survives unsigned decoding, where the packet salt becomes 0.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Inject(method = "getSignedMessage", at = @At("RETURN"))
    private void mcagent$captureChatReceipt(ServerboundChatPacket packet, LastSeenMessages lastSeen,
                                            CallbackInfoReturnable<PlayerChatMessage> info) {
        ServerMod.onChatReceipt(((ServerGamePacketListenerImpl) (Object) this).player, info.getReturnValue());
    }
}
