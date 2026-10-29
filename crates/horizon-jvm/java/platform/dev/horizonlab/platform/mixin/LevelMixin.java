package dev.horizonlab.platform.mixin;

import dev.horizonlab.bridge.Natives;
import dev.horizonlab.platform.HorizonBlockEntities;
import dev.horizonlab.platform.HorizonServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mirrored level's blocks are Horizon's: a mod reads them from Horizon's world, by name,
 * and what it sets goes to Horizon (HorizonServer.setBlock). The level holds no chunks.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void horizon$blockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.blockState((Level) (Object) this, pos));
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void horizon$fluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.blockState((Level) (Object) this, pos).getFluidState());
    }

    /** Vanilla blocks' block entities are Horizon's (none here); mod blocks' are Java's. */
    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void horizon$blockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonBlockEntities.get((Level) (Object) this, pos));
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void horizon$setBlockEntity(BlockEntity be, CallbackInfo ci) {
        if (!HorizonServer.isMirror(this)) return;
        ci.cancel();
        HorizonBlockEntities.put((Level) (Object) this, be);
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void horizon$removeBlockEntity(BlockPos pos, CallbackInfo ci) {
        if (!HorizonServer.isMirror(this)) return;
        ci.cancel();
        HorizonBlockEntities.removed((Level) (Object) this, pos);
    }

    /** BlockEntity.setChanged: saved with the others; the level holds no chunk to mark. */
    @Inject(method = "blockEntityChanged", at = @At("HEAD"), cancellable = true)
    private void horizon$blockEntityChanged(BlockPos pos, CallbackInfo ci) {
        if (!HorizonServer.isMirror(this)) return;
        ci.cancel();
        HorizonBlockEntities.changed();
    }

    /** Comparators are Horizon's. */
    @Inject(method = "updateNeighbourForOutputSignal", at = @At("HEAD"), cancellable = true)
    private void horizon$outputSignal(BlockPos pos, net.minecraft.world.level.block.Block block, CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    /** Horizon's light, read where a mod asks (the level has no chunk source). */
    @Inject(method = "getLightEngine", at = @At("HEAD"), cancellable = true)
    private void horizon$lightEngine(CallbackInfoReturnable<net.minecraft.world.level.lighting.LevelLightEngine> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.light((Level) (Object) this));
    }

    @Inject(method = "isLoaded", at = @At("HEAD"), cancellable = true)
    private void horizon$loaded(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) {
            Level level = (Level) (Object) this;
            cir.setReturnValue(Natives.blockState(level.dimension().identifier().toString(),
                    pos.getX(), pos.getY(), pos.getZ()) != null);
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void horizon$setBlock(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.setBlock((Level) (Object) this, pos, state));
    }
}
