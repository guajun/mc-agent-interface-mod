package dev.mcagent.interfacemod.mixin;

import dev.mcagent.interfacemod.SamePortControl;
import dev.mcagent.interfacemod.control.ControlServer;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;

/**
 * Keeps the same-port spike pointed at the listener that currently owns the
 * game TCP port, and closes its sessions when that listener stops.
 *
 * <p>Both a dedicated server and an integrated server published to the LAN
 * call {@code startTcpServerListener} exactly when the game port is bound, so
 * the reference is never stale. {@code stopTcpServerListener} is what
 * "Close LAN" (or world close) calls, which is where acceptance item 4 wants
 * the control connections to end.
 *
 * <p>Version coupling: Minecraft 26.2 {@code ServerConnectionListener}.
 */
@Mixin(ServerConnectionListener.class)
public abstract class SamePortListenerMixin {
    @Inject(method = "startTcpServerListener", at = @At("HEAD"))
    private void mcagent$captureSamePortListener(InetAddress address, int port, CallbackInfo callback) {
        SamePortControl.captureListener((ServerConnectionListener) (Object) this);
    }

    @Inject(method = "stopTcpServerListener", at = @At("TAIL"))
    private void mcagent$closeSamePortSessions(CallbackInfo callback) {
        SamePortControl.closeAll("game port listener stopped");
        SamePortControl.clearListener();
        ControlServer.closeAll("game port listener stopped");
    }

    @Inject(method = "stop", at = @At("TAIL"))
    private void mcagent$closeSamePortSessionsOnStop(CallbackInfo callback) {
        SamePortControl.closeAll("server listener stopped");
        SamePortControl.clearListener();
        ControlServer.closeAll("server listener stopped");
    }
}
