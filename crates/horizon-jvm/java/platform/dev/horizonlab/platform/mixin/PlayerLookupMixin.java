package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import java.util.Collection;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric's "who sees this" for a mirrored level. Vanilla answers from the level's chunk map
 * (ServerChunkCache), which a mirror has none of (Horizon holds the chunks): it throws "Only
 * supported on server levels!" there, and mods call it each tick to sync data (Carry On's
 * attachments). The mirror answers with the level's players within view distance.
 */
@Mixin(value = PlayerLookup.class, remap = false)
public abstract class PlayerLookupMixin {
    @Inject(method = "tracking(Lnet/minecraft/world/entity/Entity;)Ljava/util/Collection;", at = @At("HEAD"), cancellable = true)
    private static void horizon$entity(Entity entity, CallbackInfoReturnable<Collection<ServerPlayer>> cir) {
        if (entity != null && entity.level() instanceof ServerLevel level && HorizonServer.isMirror(level)) {
            // The headless client's own players live in the mirror's levels too: a client
            // entity is tracked by no one (vanilla would not ask, its level being a client's).
            cir.setReturnValue(HorizonServer.isMirror(entity)
                    ? HorizonServer.tracking(level, entity.getX(), entity.getZ(), entity) : java.util.List.of());
        }
    }

    @Inject(method = "tracking(Lnet/minecraft/world/level/block/entity/BlockEntity;)Ljava/util/Collection;", at = @At("HEAD"), cancellable = true)
    private static void horizon$blockEntity(BlockEntity entity, CallbackInfoReturnable<Collection<ServerPlayer>> cir) {
        if (entity != null && entity.getLevel() instanceof ServerLevel level && HorizonServer.isMirror(level)) {
            BlockPos pos = entity.getBlockPos();
            cir.setReturnValue(HorizonServer.tracking(level, pos.getX() + 0.5, pos.getZ() + 0.5, null));
        }
    }

    @Inject(method = "tracking(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Ljava/util/Collection;", at = @At("HEAD"), cancellable = true)
    private static void horizon$block(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Collection<ServerPlayer>> cir) {
        if (HorizonServer.isMirror(level)) cir.setReturnValue(HorizonServer.tracking(level, pos.getX() + 0.5, pos.getZ() + 0.5, null));
    }

    @Inject(method = "tracking(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)Ljava/util/Collection;", at = @At("HEAD"), cancellable = true)
    private static void horizon$chunk(ServerLevel level, ChunkPos pos, CallbackInfoReturnable<Collection<ServerPlayer>> cir) {
        if (HorizonServer.isMirror(level)) cir.setReturnValue(HorizonServer.tracking(level, pos.getMiddleBlockX(), pos.getMiddleBlockZ(), null));
    }
}
