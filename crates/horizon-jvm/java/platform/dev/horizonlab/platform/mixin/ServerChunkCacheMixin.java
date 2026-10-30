package dev.horizonlab.platform.mixin;

import dev.horizonlab.bridge.Natives;
import dev.horizonlab.platform.HorizonServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mirrored level's chunk source (made without its constructor: no chunk map, no tickets).
 * It holds no chunks: the level's blocks are Horizon's (LevelMixin, PathNavigationRegionMixin).
 * What entities send to the players tracking them (their events, equipment, moves) is
 * Horizon's to send: here it goes nowhere.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void horizon$chunk(int x, int z, net.minecraft.world.level.chunk.status.ChunkStatus status, boolean load,
                               CallbackInfoReturnable<net.minecraft.world.level.chunk.ChunkAccess> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(null);
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void horizon$chunkNow(int x, int z, CallbackInfoReturnable<net.minecraft.world.level.chunk.LevelChunk> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(null);
    }

    @Inject(method = "hasChunk", at = @At("HEAD"), cancellable = true)
    private void horizon$hasChunk(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) {
            cir.setReturnValue(Natives.blockState(level.dimension().identifier().toString(), x << 4, 64, z << 4) != null);
        }
    }

    /** Horizon ticks what it has (ServerChunkCache.isPositionTicking: entities' own checks). */
    @Inject(method = "isPositionTicking", at = @At("HEAD"), cancellable = true)
    private void horizon$ticking(long chunk, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(true);
    }

    @Inject(method = {"sendToTrackingPlayers", "sendToTrackingPlayersAndSelf", "sendToTrackingPlayersFiltered",
            "addEntity", "removeEntity", "move", "blockChanged", "onLightUpdate"},
            at = @At("HEAD"), cancellable = true, require = 0)
    private void horizon$nothing(CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }
}
