package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A headless client player's own messages (a mod's client code telling its player: Chunk
 * Loaders' "no owner"): vanilla shows them in the client's chat or action bar, which are
 * Horizon's window here. They go to that player's game as the server's do.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMessagesMixin {
    @Inject(method = "sendSystemMessage", at = @At("HEAD"), cancellable = true)
    private void horizon$chat(Component message, CallbackInfo ci) {
        if (HorizonClient.clientMessage((LocalPlayer) (Object) this, message, false)) ci.cancel();
    }

    @Inject(method = "sendOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void horizon$overlay(Component message, CallbackInfo ci) {
        if (HorizonClient.clientMessage((LocalPlayer) (Object) this, message, true)) ci.cancel();
    }
}
