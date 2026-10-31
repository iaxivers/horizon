package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The headless client's camera is its player (the one whose client runs now): it has no game
 * renderer, whose main camera vanilla's getCameraEntity reads. The HUD's parts draw for it.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftCameraMixin {
    @Inject(method = "getCameraEntity", at = @At("HEAD"), cancellable = true)
    private void horizon$camera(CallbackInfoReturnable<Entity> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(((Minecraft) (Object) this).player);
    }

    /** No frames, so no partial ticks: whole ticks (DeltaTracker.ONE). */
    @Inject(method = "getDeltaTracker", at = @At("HEAD"), cancellable = true)
    private void horizon$deltaTracker(CallbackInfoReturnable<DeltaTracker> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(DeltaTracker.ONE);
    }
}
