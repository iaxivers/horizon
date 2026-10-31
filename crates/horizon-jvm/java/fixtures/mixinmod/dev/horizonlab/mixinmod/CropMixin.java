package dev.horizonlab.mixinmod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Crops grow at any light: the brightness check inside CropBlock.randomTick answers 15. */
@Mixin(CropBlock.class)
public abstract class CropMixin {
    @Redirect(method = "randomTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getRawBrightness(Lnet/minecraft/core/BlockPos;I)I"))
    private int horizonMixins$light(ServerLevel level, BlockPos pos, int darkening) {
        return 15;
    }
}
