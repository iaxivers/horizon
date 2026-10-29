package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import dev.horizonlab.platform.HorizonLoop;
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
 * Fabric's networking for a mirrored player. In the game's own JVM the mods' client code runs
 * too (the headless client): the player has its channels, and what a mod sends goes to it
 * (HorizonLoop). On a dedicated server Horizon's clients are no Fabric clients: no channels (a
 * vanilla client's answer), and what mods send is dropped.
 */
@Mixin(value = ServerPlayNetworking.class, remap = false)
public abstract class PlayNetworkingMixin {
    @Inject(method = "getSendable(Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/Set;", at = @At("HEAD"), cancellable = true)
    private static void horizon$sendable(ServerPlayer player, CallbackInfoReturnable<Set<Identifier>> cir) {
        if (HorizonServer.isMirror(player)) cir.setReturnValue(HorizonServer.loop() ? HorizonLoop.clientChannels() : Set.of());
    }

    @Inject(method = "getSendable(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)Ljava/util/Set;", at = @At("HEAD"), cancellable = true)
    private static void horizon$sendable(ServerGamePacketListenerImpl handler, CallbackInfoReturnable<Set<Identifier>> cir) {
        if (HorizonServer.isMirror(handler)) cir.setReturnValue(HorizonServer.loop() ? HorizonLoop.clientChannels() : Set.of());
    }

    @Inject(method = "getReceived(Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/Set;", at = @At("HEAD"), cancellable = true)
    private static void horizon$received(ServerPlayer player, CallbackInfoReturnable<Set<Identifier>> cir) {
        if (HorizonServer.isMirror(player)) cir.setReturnValue(HorizonServer.loop() ? HorizonLoop.serverChannels() : Set.of());
    }

    @Inject(method = "getReceived(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)Ljava/util/Set;", at = @At("HEAD"), cancellable = true)
    private static void horizon$received(ServerGamePacketListenerImpl handler, CallbackInfoReturnable<Set<Identifier>> cir) {
        if (HorizonServer.isMirror(handler)) cir.setReturnValue(HorizonServer.loop() ? HorizonLoop.serverChannels() : Set.of());
    }

    @Inject(method = "canSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/Identifier;)Z", at = @At("HEAD"), cancellable = true)
    private static void horizon$canSend(ServerPlayer player, Identifier channel, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(player)) cir.setReturnValue(HorizonServer.loop() && HorizonLoop.clientChannels().contains(channel));
    }

    @Inject(method = "canSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;)Z", at = @At("HEAD"), cancellable = true)
    private static void horizon$canSend(ServerPlayer player, CustomPacketPayload.Type<?> type, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(player)) cir.setReturnValue(HorizonServer.loop() && HorizonLoop.clientChannels().contains(type.id()));
    }

    @Inject(method = "canSend(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/resources/Identifier;)Z", at = @At("HEAD"), cancellable = true)
    private static void horizon$canSend(ServerGamePacketListenerImpl handler, Identifier channel, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(handler)) cir.setReturnValue(HorizonServer.loop() && HorizonLoop.clientChannels().contains(channel));
    }

    @Inject(method = "canSend(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;)Z", at = @At("HEAD"), cancellable = true)
    private static void horizon$canSend(ServerGamePacketListenerImpl handler, CustomPacketPayload.Type<?> type, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(handler)) cir.setReturnValue(HorizonServer.loop() && HorizonLoop.clientChannels().contains(type.id()));
    }

    @Inject(method = "getSender(Lnet/minecraft/server/level/ServerPlayer;)Lnet/fabricmc/fabric/api/networking/v1/PacketSender;", at = @At("HEAD"), cancellable = true)
    private static void horizon$sender(ServerPlayer player, CallbackInfoReturnable<PacketSender> cir) {
        if (HorizonServer.isMirror(player)) cir.setReturnValue(HorizonServer.loop() ? HorizonLoop.serverSender(player) : HorizonServer.noPackets());
    }

    @Inject(method = "getSender(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)Lnet/fabricmc/fabric/api/networking/v1/PacketSender;", at = @At("HEAD"), cancellable = true)
    private static void horizon$sender(ServerGamePacketListenerImpl handler, CallbackInfoReturnable<PacketSender> cir) {
        if (HorizonServer.isMirror(handler)) cir.setReturnValue(HorizonServer.loop() ? HorizonLoop.serverSender(handler.player) : HorizonServer.noPackets());
    }

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private static void horizon$send(ServerPlayer player, CustomPacketPayload payload, CallbackInfo ci) {
        if (HorizonServer.isMirror(player)) {
            HorizonServer.payload(player, payload);
            ci.cancel();
        }
    }

    @Inject(method = "reconfigure(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
    private static void horizon$reconfigure(ServerPlayer player, CallbackInfo ci) {
        if (HorizonServer.isMirror(player)) ci.cancel();
    }
}
