package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.blockscan.BlockScanUtils;
import net.minecraft.world.level.blockscan.BlockStateConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A scan of a box's blocks (containsAnyLiquid, a spawn's obstruction check) on a mirrored
 * level: the scan reads chunk sections, which the mirror doesn't hold, so it reads the level's
 * blocks (Horizon's) one by one, in the same order. True when the consumer stopped it.
 */
@Mixin(BlockScanUtils.class)
public abstract class BlockScanMixin {
    @Inject(method = "sectionBasedScan", at = @At("HEAD"), cancellable = true)
    private static void horizon$scan(LevelReader level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                     Predicate<BlockState> filter, BlockStateConsumer consumer, CallbackInfoReturnable<Boolean> cir) {
        if (!HorizonServer.isMirror(level)) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (filter.test(state) && consumer.apply(pos, state).shouldAbort()) {
                        cir.setReturnValue(true);
                        return;
                    }
                }
            }
        }
        cir.setReturnValue(false);
    }
}
