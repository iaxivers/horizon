package dev.horizonlab.platform.mixin;

import dev.horizonlab.bridge.Natives;
import dev.horizonlab.platform.HorizonServer;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mirrored player has no connection: Horizon talks to the real one. Packets mods send it go
 * to Horizon when Horizon knows what to do with them (a system message), and are dropped
 * otherwise (counted, see HorizonServer.dropped).
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PacketListenerMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void horizon$send(Packet<?> packet, CallbackInfo ci) {
        horizon$route(packet, ci);
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void horizon$send(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        horizon$route(packet, ci);
    }

    private void horizon$route(Packet<?> packet, CallbackInfo ci) {
        Object self = this;
        if (!HorizonServer.isMirror(self)) return;
        ci.cancel();
        if (packet instanceof ClientboundSystemChatPacket chat && self instanceof ServerGamePacketListenerImpl game
                && game.player != null && !chat.overlay()) {
            Natives.tell(game.player.getStringUUID(), chat.content().getString());
            return;
        }
        HorizonServer.dropped(packet);
    }
}
