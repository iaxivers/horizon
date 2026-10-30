package dev.horizonlab.platform.mixin;

import dev.horizonlab.bridge.Natives;
import dev.horizonlab.platform.HorizonServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Horizon player's system messages go to Horizon, which sends them. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void horizon$sendSystemMessage(Component message, CallbackInfo ci) {
        horizon$tell(message, ci);
    }

    @Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
    private void horizon$sendSystemMessage(Component message, boolean overlay, CallbackInfo ci) {
        horizon$tell(message, ci);
    }

    /** A teleport (to this level or another): the mirror moves, and Horizon moves the player. */
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at = @At("HEAD"), cancellable = true)
    private void horizon$teleport(net.minecraft.world.level.portal.TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!HorizonServer.isMirror(self) || transition.newLevel() == null) return;
        var to = net.minecraft.world.entity.PositionMoveRotation.calculateAbsolute(
                net.minecraft.world.entity.PositionMoveRotation.of(self),
                net.minecraft.world.entity.PositionMoveRotation.of(transition), transition.relatives());
        HorizonServer.teleported(self, transition.newLevel(), to);
        cir.setReturnValue(self);
    }

    private void horizon$tell(Component message, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (HorizonServer.isMirror(self)) {
            Natives.tell(self.getStringUUID(), HorizonServer.chatJson(message));
            ci.cancel();
        }
    }
}
