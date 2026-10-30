package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A region of the mods' world generation (HorizonWorldgen) on a mirrored level: the chunks it
 * blends with would be old chunks of ChunkMap's, which the mirror has none of (Horizon's
 * world upgrades its own), so nothing is blended.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "isOldChunkAround", at = @At("HEAD"), cancellable = true)
    private void horizon$noOldChunks(ChunkPos pos, int range, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this.level)) cir.setReturnValue(false);
    }
}
