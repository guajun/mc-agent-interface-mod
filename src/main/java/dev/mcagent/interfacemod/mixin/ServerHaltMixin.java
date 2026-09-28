package dev.mcagent.interfacemod.mixin;

import dev.mcagent.interfacemod.SamePortControl;
import dev.mcagent.interfacemod.control.ControlServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * End the control transport when a server halts.
 *
 * <p>A dedicated server's graceful stop goes through
 * {@code ServerConnectionListener.stop}/{@code stopTcpServerListener}, but an
 * integrated server closed from the client calls {@code halt(false)} directly:
 * that path never fires the listener-stop hooks, so a same-port control socket
 * would stay open against a dead world. Closing the sessions here also rotates
 * the run identity, so a reconnecting daemon is told it is a new run rather
 * than silently continuing from a stale cursor.
 *
 * <p>Version coupling: Minecraft 26.2 {@code MinecraftServer#halt(boolean)}.
 */
@Mixin(MinecraftServer.class)
public abstract class ServerHaltMixin {
    @Inject(method = "halt", at = @At("HEAD"))
    private void mcagent$closeControlOnHalt(boolean waitForServer, CallbackInfo callback) {
        SamePortControl.closeAll("server halted");
        ControlServer.halted();
    }
}
