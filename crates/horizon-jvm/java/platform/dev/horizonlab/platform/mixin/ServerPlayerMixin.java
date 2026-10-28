package dev.horizonlab.platform.mixin;

import dev.horizonlab.bridge.Natives;
import dev.horizonlab.platform.HorizonServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    private void horizon$tell(Component message, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (HorizonServer.isMirror(self)) {
            Natives.tell(self.getStringUUID(), message == null ? "" : message.getString());
            ci.cancel();
        }
    }
}
