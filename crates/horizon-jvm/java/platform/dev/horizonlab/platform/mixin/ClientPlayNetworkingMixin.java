package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import dev.horizonlab.platform.HorizonLoop;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mods' client code in the headless client has no connection: what it sends goes to their
 * server code in this JVM (HorizonLoop), and the server's channels are the server code's own.
 */
@Mixin(value = ClientPlayNetworking.class, remap = false)
public abstract class ClientPlayNetworkingMixin {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private static void horizon$send(CustomPacketPayload payload, CallbackInfo ci) {
        if (!HorizonClient.headless()) return;
        ci.cancel();
        HorizonLoop.toServer(payload);
    }

    @Inject(method = "canSend(Lnet/minecraft/resources/Identifier;)Z", at = @At("HEAD"), cancellable = true)
    private static void horizon$canSend(Identifier channel, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(HorizonLoop.serverChannels().contains(channel));
    }

    @Inject(method = "canSend(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;)Z", at = @At("HEAD"), cancellable = true)
    private static void horizon$canSend(CustomPacketPayload.Type<?> type, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(HorizonLoop.serverChannels().contains(type.id()));
    }

    @Inject(method = "getSendable", at = @At("HEAD"), cancellable = true)
    private static void horizon$sendable(CallbackInfoReturnable<Set<Identifier>> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(HorizonLoop.serverChannels());
    }

    @Inject(method = "getReceived", at = @At("HEAD"), cancellable = true)
    private static void horizon$received(CallbackInfoReturnable<Set<Identifier>> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(HorizonLoop.clientChannels());
    }

    @Inject(method = "getSender", at = @At("HEAD"), cancellable = true)
    private static void horizon$sender(CallbackInfoReturnable<PacketSender> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(HorizonLoop.clientSender());
    }
}
