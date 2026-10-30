package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mirrored level's chunk source (HorizonServer.level makes it without its constructor, as
 * the level). Horizon holds the chunks, tracks the entities and sends the updates: what mods
 * ask of it (a block entity's sync, ServerLevel.getChunkSource().blockChanged, the commonest)
 * is answered from the mirror, and what would load or send something is Horizon's.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    private Level horizon$level() {
        return (Level) ((ServerChunkCache) (Object) this).getLevel();
    }

    /** A block (or its block entity) for clients: Horizon sends its own; it is saved. */
    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    private void horizon$blockChanged(BlockPos pos, CallbackInfo ci) {
        if (!HorizonServer.isMirror(this)) return;
        ci.cancel();
        HorizonServer.blockChanged(horizon$level(), pos);
    }

    @Inject(method = "getDataStorage", at = @At("HEAD"), cancellable = true)
    private void horizon$dataStorage(CallbackInfoReturnable<net.minecraft.world.level.storage.SavedDataStorage> cir) {
        if (HorizonServer.isMirror(this) && horizon$level() instanceof net.minecraft.server.level.ServerLevel level) {
            cir.setReturnValue(dev.horizonlab.platform.HorizonSavedData.level(level));
        }
    }

    @Inject(method = "getGenerator", at = @At("HEAD"), cancellable = true)
    private void horizon$generator(CallbackInfoReturnable<net.minecraft.world.level.chunk.ChunkGenerator> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.generator((net.minecraft.server.level.ServerLevel) horizon$level()));
    }

    @Inject(method = "randomState", at = @At("HEAD"), cancellable = true)
    private void horizon$randomState(CallbackInfoReturnable<net.minecraft.world.level.levelgen.RandomState> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.randomState((net.minecraft.server.level.ServerLevel) horizon$level()));
    }

    @Inject(method = "getGeneratorState", at = @At("HEAD"), cancellable = true)
    private void horizon$generatorState(CallbackInfoReturnable<net.minecraft.world.level.chunk.ChunkGeneratorStructureState> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.generatorState((net.minecraft.server.level.ServerLevel) horizon$level()));
    }

    @Inject(method = "hasChunk", at = @At("HEAD"), cancellable = true)
    private void horizon$hasChunk(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(horizon$level().hasChunk(x, z));
    }

    /** The mirror holds no chunk objects: none is loaded, to a mod that would read one. */
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void horizon$chunk(int x, int z, net.minecraft.world.level.chunk.status.ChunkStatus status, boolean load,
                               CallbackInfoReturnable<net.minecraft.world.level.chunk.ChunkAccess> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(null);
    }

    /** Horizon ticks what it has (ServerChunkCache.isPositionTicking: entities' own checks). */
    @Inject(method = "isPositionTicking", at = @At("HEAD"), cancellable = true)
    private void horizon$ticking(long chunk, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(true);
    }


    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void horizon$chunkNow(int x, int z, CallbackInfoReturnable<net.minecraft.world.level.chunk.LevelChunk> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(null);
    }

    @Inject(method = "getLoadedChunksCount", at = @At("HEAD"), cancellable = true)
    private void horizon$loaded(CallbackInfoReturnable<Integer> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(0);
    }

    // Entities are Horizon's (it tracks them and sends their packets).
    @Inject(method = {"sendToTrackingPlayers", "sendToTrackingPlayersAndSelf", "sendToTrackingPlayersFiltered",
            "addEntity", "removeEntity", "move", "onLightUpdate"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void horizon$entities(CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    // Chunk tickets: Horizon loads its chunks (mods' chunk loaders don't reach it yet).
    @Inject(method = {"addTicket", "addTicketWithRadius", "removeTicketWithRadius"}, at = @At("HEAD"), cancellable = true)
    private void horizon$tickets(CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    @Inject(method = "updateChunkForced", at = @At("HEAD"), cancellable = true)
    private void horizon$forced(ChunkPos pos, boolean forced, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(false);
    }
}
