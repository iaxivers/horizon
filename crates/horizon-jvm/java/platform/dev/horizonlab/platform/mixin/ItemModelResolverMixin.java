package dev.horizonlab.platform.mixin;

import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The headless client's item models: it has none (no model manager), so what an entity
 * renderer asks for the items its entity holds or wears is nothing to draw
 * (HorizonEntityRender's context makes this resolver with no manager).
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {
    @Shadow
    @Final
    private net.minecraft.client.resources.model.ModelManager modelManager;

    @Inject(method = "updateForLiving", at = @At("HEAD"), cancellable = true)
    private void horizon$living(ItemStackRenderState state, net.minecraft.world.item.ItemStack stack,
                                net.minecraft.world.item.ItemDisplayContext context, net.minecraft.world.entity.LivingEntity owner,
                                CallbackInfo ci) {
        horizon$none(state, ci);
    }

    @Inject(method = "updateForNonLiving", at = @At("HEAD"), cancellable = true)
    private void horizon$nonLiving(ItemStackRenderState state, net.minecraft.world.item.ItemStack stack,
                                   net.minecraft.world.item.ItemDisplayContext context, net.minecraft.world.entity.Entity owner,
                                   CallbackInfo ci) {
        horizon$none(state, ci);
    }

    @Inject(method = "updateForTopItem", at = @At("HEAD"), cancellable = true)
    private void horizon$top(ItemStackRenderState state, net.minecraft.world.item.ItemStack stack,
                             net.minecraft.world.item.ItemDisplayContext context, net.minecraft.world.level.Level level,
                             net.minecraft.world.entity.ItemOwner owner, int seed, CallbackInfo ci) {
        horizon$none(state, ci);
    }

    @Inject(method = "appendItemLayers", at = @At("HEAD"), cancellable = true)
    private void horizon$noLayers(CallbackInfo ci) {
        if (modelManager == null) ci.cancel();
    }

    private void horizon$none(ItemStackRenderState state, CallbackInfo ci) {
        if (modelManager != null) return;
        state.clear();
        ci.cancel();
    }
}
