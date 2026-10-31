package dev.horizonlab.mixinmod;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Players can't pick up dirt. */
@Mixin(ItemEntity.class)
public abstract class PickupMixin {
    @Shadow public abstract net.minecraft.world.item.ItemStack getItem();

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void horizonMixins$noDirt(Player player, CallbackInfo ci) {
        if (getItem().is(Items.DIRT)) ci.cancel();
    }
}
