package dev.mcagent.interfacemod.mixin;

import dev.mcagent.interfacemod.SamePortControl;
import dev.mcagent.interfacemod.control.ControlServer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.local.LocalChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arms the same-port control transports on real TCP server connections: the
 * formal TLS sniffer from issue #8 (when enabled) in front of the issue #7
 * spike sniffer (when enabled).
 *
 * <p>{@code Connection.configurePacketHandler} is the single point where a
 * serverbound connection gets its {@code packet_handler}, and the mixin runs
 * before the pipeline holds any byte. Only a non-memory {@link PacketFlow#SERVERBOUND}
 * connection is armed:
 *
 * <ul>
 *   <li>a dedicated server's TCP child channels ({@code ServerConnectionListener$1});</li>
 *   <li>the integrated server's LAN TCP child channels, published through
 *       {@code IntegratedServer.publishServer -> startTcpServerListener};</li>
 *   <li>not the single-player in-memory client/server {@link LocalChannel},
 *       which has no real game port at all.</li>
 * </ul>
 *
 * <p>Version coupling: Minecraft 26.2 (unobfuscated), {@code
 * net.minecraft.network.Connection#configurePacketHandler(ChannelPipeline)} and
 * its {@code receiving} field.
 */
@Mixin(Connection.class)
public abstract class SamePortConnectionMixin {
    @Shadow
    @Final
    private PacketFlow receiving;

    @Inject(method = "configurePacketHandler", at = @At("HEAD"))
    private void mcagent$armSamePortControl(ChannelPipeline pipeline, CallbackInfo callback) {
        if (receiving != PacketFlow.SERVERBOUND) {
            return;
        }
        if (pipeline.channel() instanceof LocalChannel) {
            return;
        }
        ControlServer.armChannel(pipeline, (Connection) (Object) this);
    }
}
