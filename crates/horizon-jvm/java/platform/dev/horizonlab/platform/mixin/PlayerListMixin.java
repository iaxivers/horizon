package dev.horizonlab.platform.mixin;

import dev.horizonlab.bridge.Natives;
import dev.horizonlab.platform.HorizonServer;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Broadcasts on Horizon's player list go to Horizon, which sends them to every player; a
 * player's stats and advancements are Horizon's (empty, for now), not files the list reads.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
    private void horizon$broadcast(Component message, boolean overlay, CallbackInfo ci) {
        if (HorizonServer.isMirror((PlayerList) (Object) this)) {
            Natives.broadcast(message == null ? "" : message.getString());
            ci.cancel();
        }
    }

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V", at = @At("HEAD"), cancellable = true)
    private void horizon$broadcast(Component message, Function<ServerPlayer, Component> perPlayer, boolean overlay, CallbackInfo ci) {
        if (HorizonServer.isMirror((PlayerList) (Object) this)) {
            Natives.broadcast(message == null ? "" : message.getString());
            ci.cancel();
        }
    }

    @Inject(method = "getPlayerStats", at = @At("HEAD"), cancellable = true)
    private void horizon$stats(Player player, CallbackInfoReturnable<ServerStatsCounter> cir) {
        if (HorizonServer.isMirror((PlayerList) (Object) this)) cir.setReturnValue(HorizonServer.stats(player));
    }

    @Inject(method = "getPlayerAdvancements", at = @At("HEAD"), cancellable = true)
    private void horizon$advancements(ServerPlayer player, CallbackInfoReturnable<PlayerAdvancements> cir) {
        if (HorizonServer.isMirror((PlayerList) (Object) this)) cir.setReturnValue(HorizonServer.advancements(player));
    }
}
