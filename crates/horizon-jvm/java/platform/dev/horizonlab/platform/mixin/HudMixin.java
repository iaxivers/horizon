package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The headless client's HUD draws nothing: Horizon's is the game's (subtitles included). */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Inject(method = "extractDeferredSubtitles", at = @At("HEAD"), cancellable = true)
    private void horizon$subtitles(CallbackInfo ci) {
        if (HorizonClient.headless()) ci.cancel();
    }
}
