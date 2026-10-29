package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric's networking for a mirrored player: Horizon's client is no Fabric client, so it has
 * none of the mods' channels (a vanilla client's answer), and what mods send it is dropped.
 */
@Mixin(value = ServerPlayNetworking.class, remap = false)
public abstract class PlayNetworkingMixin {
    @Inject(method = "getSendable(Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/Set;", at = @At("HEAD"), cancellable = true)
    private static void horizon$sendable(ServerPlayer player, CallbackInfoReturnable<Set<Identifier>> cir) {
        if (HorizonServer.isMirror(player)) cir.setReturnValue(Set.of());
    }

    @Inject(method = "getSendable(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)Ljava/util/Set;", at = @At("HEAD"), cancellable = true)
    private static void horizon$sendable(ServerGamePacketListenerImpl handler, CallbackInfoReturnable<Set<Identifier>> cir) {
        if (HorizonServer.isMirror(handler)) cir.setReturnValue(Set.of());
    }

    @Inject(method = "getReceived(Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/Set;", at = @At("HEAD"), cancellable = true)
    private static void horizon$received(ServerPlayer player, CallbackInfoReturnable<Set<Identifier>> cir) {
        if (HorizonServer.isMirror(player)) cir.setReturnValue(Set.of());
    }

    @Inject(method = "getReceived(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)Ljava/util/Set;", at = @At("HEAD"), cancellable = true)
    private static void horizon$received(ServerGamePacketListenerImpl handler, CallbackInfoReturnable<Set<Identifier>> cir) {
        if (HorizonServer.isMirror(handler)) cir.setReturnValue(Set.of());
    }

    @Inject(method = "canSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/Identifier;)Z", at = @At("HEAD"), cancellable = true)
    private static void horizon$canSend(ServerPlayer player, Identifier channel, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(player)) cir.setReturnValue(false);
    }

    @Inject(method = "canSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;)Z", at = @At("HEAD"), cancellable = true)
    private static void horizon$canSend(ServerPlayer player, CustomPacketPayload.Type<?> type, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(player)) cir.setReturnValue(false);
    }

    @Inject(method = "canSend(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/resources/Identifier;)Z", at = @At("HEAD"), cancellable = true)
    private static void horizon$canSend(ServerGamePacketListenerImpl handler, Identifier channel, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(handler)) cir.setReturnValue(false);
    }

    @Inject(method = "canSend(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;)Z", at = @At("HEAD"), cancellable = true)
    private static void horizon$canSend(ServerGamePacketListenerImpl handler, CustomPacketPayload.Type<?> type, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(handler)) cir.setReturnValue(false);
    }

    @Inject(method = "getSender(Lnet/minecraft/server/level/ServerPlayer;)Lnet/fabricmc/fabric/api/networking/v1/PacketSender;", at = @At("HEAD"), cancellable = true)
    private static void horizon$sender(ServerPlayer player, CallbackInfoReturnable<PacketSender> cir) {
        if (HorizonServer.isMirror(player)) cir.setReturnValue(HorizonServer.noPackets());
    }

    @Inject(method = "getSender(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)Lnet/fabricmc/fabric/api/networking/v1/PacketSender;", at = @At("HEAD"), cancellable = true)
    private static void horizon$sender(ServerGamePacketListenerImpl handler, CallbackInfoReturnable<PacketSender> cir) {
        if (HorizonServer.isMirror(handler)) cir.setReturnValue(HorizonServer.noPackets());
    }

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private static void horizon$send(ServerPlayer player, CustomPacketPayload payload, CallbackInfo ci) {
        if (HorizonServer.isMirror(player)) {
            HorizonServer.dropped(payload);
            ci.cancel();
        }
    }

    @Inject(method = "reconfigure(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
    private static void horizon$reconfigure(ServerPlayer player, CallbackInfo ci) {
        if (HorizonServer.isMirror(player)) ci.cancel();
    }
}
