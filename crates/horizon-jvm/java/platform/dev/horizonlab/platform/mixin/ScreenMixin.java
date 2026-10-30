package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A screen's background in the headless client is an in-world one: Horizon's window shows it
 * over the world (and blurs that itself), though the headless client has no level of its own,
 * which vanilla takes for the title screen (the panorama, the opaque menu background).
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Shadow @Final private static Identifier INWORLD_MENU_BACKGROUND;

    @Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
    private void horizon$panorama(GuiGraphicsExtractor graphics, float alpha, CallbackInfo ci) {
        if (HorizonClient.headless()) ci.cancel();
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V",
            at = @At("HEAD"), cancellable = true)
    private void horizon$menuBackground(GuiGraphicsExtractor graphics, int x, int y, int width, int height, CallbackInfo ci) {
        if (!HorizonClient.headless()) return;
        ci.cancel();
        Screen.extractMenuBackgroundTexture(graphics, INWORLD_MENU_BACKGROUND, x, y, 0, 0, width, height);
    }
}
