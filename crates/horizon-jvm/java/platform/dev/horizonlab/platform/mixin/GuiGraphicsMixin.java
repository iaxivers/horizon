package dev.horizonlab.platform.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.horizonlab.platform.HorizonGraphics;
import dev.horizonlab.platform.HorizonGraphicsWrappers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GuiGraphicsExtractor's private innerBlit, which mods' access wideners open (public and
 * final, so no subclass overrides it) and call themselves (REI's textured widgets): on a
 * HorizonGraphics or a wrapper of one, recorded as an image instead of drawn through the
 * texture manager the headless client lacks.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsMixin {
    @Inject(method = "innerBlit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIFFFFI)V",
            at = @At("HEAD"), cancellable = true)
    private void horizon$innerBlit(RenderPipeline pipeline, Identifier texture, int x0, int x1, int y0, int y1,
                                   float u0, float u1, float v0, float v1, int color, CallbackInfo ci) {
        HorizonGraphics graphics = HorizonGraphicsWrappers.of(this);
        if (graphics == null) return;
        ci.cancel();
        graphics.horizon$innerBlit(pipeline, texture, x0, x1, y0, y1, u0, u1, v0, v1, color);
    }
}
