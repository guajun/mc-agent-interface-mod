package dev.mcagent.interfacemod.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Lets the same-port spike remove the placeholder {@link Connection} that the
 * vanilla pipeline created for a control socket. It never performed a
 * handshake, but it sits in {@code ServerConnectionListener.connections} and
 * would otherwise be ticked and log a player-shaped disconnection when the
 * control socket closes.
 */
@Mixin(ServerConnectionListener.class)
public interface SamePortListenerAccessor {
    @Accessor("connections")
    List<Connection> mcagent$connections();
}
