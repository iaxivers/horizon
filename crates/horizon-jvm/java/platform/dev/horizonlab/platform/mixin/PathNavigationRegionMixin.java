package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mob's path region over a mirrored level: the region copies the level's chunks, which a
 * mirror doesn't hold, so it reads the level itself (Horizon's blocks), as the copy would.
 */
@Mixin(PathNavigationRegion.class)
public abstract class PathNavigationRegionMixin {
    @Shadow
    @Final
    protected Level level;

    @Shadow
    protected boolean allEmpty;

    /** Not empty: the region has Horizon's blocks (the chunks it found were none). */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void horizon$made(Level level, BlockPos from, BlockPos to, CallbackInfo ci) {
        if (HorizonServer.isMirror(level)) allEmpty = false;
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void horizon$blockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (HorizonServer.isMirror(level)) cir.setReturnValue(level.getBlockState(pos));
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void horizon$fluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (HorizonServer.isMirror(level)) cir.setReturnValue(level.getFluidState(pos));
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void horizon$blockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (HorizonServer.isMirror(level)) cir.setReturnValue(level.getBlockEntity(pos));
    }

    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true)
    private void horizon$collisions(int x, int z, CallbackInfoReturnable<BlockGetter> cir) {
        if (HorizonServer.isMirror(level)) cir.setReturnValue(level);
    }
}
