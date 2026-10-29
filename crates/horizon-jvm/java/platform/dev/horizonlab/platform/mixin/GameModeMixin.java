package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonBlockEntities;
import dev.horizonlab.platform.HorizonServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Horizon runs vanilla's ServerPlayerGameMode.useItemOn on a mirrored player when its player
 * uses a block, so every mod's injection at its head runs (Fabric API's UseBlockCallback is
 * one). This one comes last (the highest priority applies last) and ends the method there,
 * the body (using the block) being Horizon's, except where the block or the item is a mod's.
 * A mod's result reaches Horizon (HorizonServer.useBlock).
 */
@Mixin(value = ServerPlayerGameMode.class, priority = Integer.MAX_VALUE)
public abstract class GameModeMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void horizon$useItemOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                   BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (!HorizonServer.isMirror(player)) return;
        // A mod's block, or a mod's item on a vanilla block that does nothing when used (stone,
        // grass): the whole use is the mod's code, vanilla's body runs (placing, the block's own
        // use). Else Horizon uses the block.
        var state = level.getBlockState(hit.getBlockPos());
        if (HorizonBlockEntities.modded(state)) return;
        if (HorizonServer.moddedItem(stack) && !HorizonServer.usable(state)) return;
        cir.setReturnValue(InteractionResult.PASS);
    }
}
