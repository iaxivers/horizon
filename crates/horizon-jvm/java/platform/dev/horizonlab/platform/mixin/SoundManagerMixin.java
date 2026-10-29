package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The headless client's sound manager (made without its constructor, so without a sound
 * engine) plays nothing: Horizon's game makes the sounds. A screen's button click, for one,
 * reaches here.
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void horizon$play(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
    private void horizon$playDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
        if (HorizonClient.headless()) ci.cancel();
    }

    @Inject(method = "queueTickingSound", at = @At("HEAD"), cancellable = true)
    private void horizon$queue(TickableSoundInstance sound, CallbackInfo ci) {
        if (HorizonClient.headless()) ci.cancel();
    }

    @Inject(method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"), cancellable = true)
    private void horizon$stop(SoundInstance sound, CallbackInfo ci) {
        if (HorizonClient.headless()) ci.cancel();
    }

    @Inject(method = "isActive", at = @At("HEAD"), cancellable = true)
    private void horizon$active(SoundInstance sound, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(false);
    }
}
