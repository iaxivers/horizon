package dev.horizonlab.platform.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizonlab.platform.HorizonEntityRender;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An item kept for Horizon (ItemModelResolverMixin): not empty, so the entity renderer's
 * layer submits it, and its submit records the item and the pose for Horizon to draw.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin {
    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void horizon$kept(CallbackInfoReturnable<Boolean> cir) {
        if (HorizonEntityRender.keptItem(this)) cir.setReturnValue(false);
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void horizon$clear(CallbackInfo ci) {
        HorizonEntityRender.forgetItem(this);
    }

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void horizon$submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, int outline,
                                CallbackInfo ci) {
        if (HorizonEntityRender.submitItem(this, pose)) ci.cancel();
    }
}
