package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import dev.horizonlab.platform.HorizonLoop;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The headless client's connection has no channel: a packet a mod sends on it
 * (minecraft.getConnection().send, a mod's custom payload as a raw packet) goes to the mods'
 * server code in this JVM (HorizonLoop) when it is a custom payload; vanilla's own are the
 * player's game's to send, and are dropped.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void horizon$send(Packet<?> packet, CallbackInfo ci) {
        if (!HorizonClient.headless()) return;
        ci.cancel();
        if (packet instanceof ServerboundCustomPayloadPacket(var payload)) HorizonLoop.toServer(payload);
    }
}
