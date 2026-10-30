package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gui.setScreen (26.3's Minecraft.setScreen) in the headless client: the screen is the
 * player's whose client runs now, shown in Horizon's window (HorizonClient.setScreen). Vanilla's
 * own body needs a window (its mouse, its size) and one player.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void horizon$setScreen(Screen screen, CallbackInfo ci) {
        if (!HorizonClient.headless()) return;
        ci.cancel();
        HorizonClient.setScreen(screen);
    }
}
