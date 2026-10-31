package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonGraphics;
import dev.horizonlab.platform.HorizonGraphicsWrappers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * REI draws its screens through its own extractor, {@code GuiGraphics.of(graphics)}: on a
 * HorizonGraphics, a wrapper that records into it (HorizonGraphicsWrappers), so REI's recipe
 * view is drawn in Horizon's window.
 */
@Pseudo
@Mixin(targets = "me.shedaniel.rei.api.client.gui.compat.GuiGraphics", remap = false)
public abstract class ReiGuiGraphicsMixin {
    @Inject(method = "of", at = @At("HEAD"), cancellable = true, require = 0)
    private static void horizon$of(GuiGraphicsExtractor graphics, CallbackInfoReturnable<Object> cir) {
        if (!(graphics instanceof HorizonGraphics horizon)) return;
        Class<?> self;
        try {
            self = Class.forName("me.shedaniel.rei.api.client.gui.compat.GuiGraphics", false,
                    ReiGuiGraphicsMixin.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return;
        }
        Object wrapped = HorizonGraphicsWrappers.wrap(self, horizon);
        if (wrapped != null) cir.setReturnValue(wrapped);
    }
}
