package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** What Horizon's mirrored levels answer in place of chunks they don't hold. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "getNextEntityId", at = @At("HEAD"), cancellable = true)
    private void horizon$nextEntityId(CallbackInfoReturnable<Integer> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.nextEntityId());
    }

    /** Items a mod drops (a block's drops) become Horizon's item entities. */
    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void horizon$addEntity(net.minecraft.world.entity.Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.addEntity((ServerLevel) (Object) this, entity));
    }

    /** Game events (sculk) are Horizon's: Horizon posts its own for the blocks it changes. */
    @Inject(method = "gameEvent", at = @At("HEAD"), cancellable = true)
    private void horizon$gameEvent(net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
                                   net.minecraft.world.phys.Vec3 at,
                                   net.minecraft.world.level.gameevent.GameEvent.Context context,
                                   org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    /** Level events (a block's break effects) go to Horizon, once, not once per player. */
    @Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
    private void horizon$levelEvent(net.minecraft.world.entity.Entity source, int type, net.minecraft.core.BlockPos pos, int data,
                                    org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!HorizonServer.isMirror(this)) return;
        ci.cancel();
        // A client half's own effects: the server's are played.
        if (HorizonServer.clientHalf == Thread.currentThread()) return;
        HorizonServer.levelEvent((ServerLevel) (Object) this, type, pos, data);
    }

    /** A block's update for clients: Horizon sends its own. */
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"), cancellable = true)
    private void horizon$sendBlockUpdated(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState old,
                                          net.minecraft.world.level.block.state.BlockState now, int flags,
                                          org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    /** The level's entities, for a mod's checks: its mirrored players. */
    @Inject(method = "getEntities()Lnet/minecraft/world/level/entity/LevelEntityGetter;", at = @At("HEAD"), cancellable = true)
    private void horizon$entities(CallbackInfoReturnable<net.minecraft.world.level.entity.LevelEntityGetter<net.minecraft.world.entity.Entity>> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.entities((ServerLevel) (Object) this));
    }

    /** Horizon's world seed. */
    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void horizon$seed(CallbackInfoReturnable<Long> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.seed());
    }

    /** Its saved data (vanilla's is its chunk source's, which a mirror has none of). */
    @Inject(method = "getDataStorage", at = @At("HEAD"), cancellable = true)
    private void horizon$dataStorage(CallbackInfoReturnable<net.minecraft.world.level.storage.SavedDataStorage> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(dev.horizonlab.platform.HorizonSavedData.level((ServerLevel) (Object) this));
    }

    /**
     * The world's difficulty here: Horizon's world has no chunk inhabited time to add, and the
     * mirror no world clocks (getOverworldClockTime reads the server's ClockManager): its game
     * time stands for the day's.
     */
    @Inject(method = "getCurrentDifficultyAt", at = @At("HEAD"), cancellable = true)
    private void horizon$difficulty(net.minecraft.core.BlockPos pos, CallbackInfoReturnable<net.minecraft.world.DifficultyInstance> cir) {
        if (!HorizonServer.isMirror(this)) return;
        ServerLevel level = (ServerLevel) (Object) this;
        cir.setReturnValue(new net.minecraft.world.DifficultyInstance(level.getDifficulty(), level.getGameTime(), 0L,
                level.getMoonBrightness(pos)));
    }

    /** /summon's add: the entity and its riders, unless one of them is there already. */
    @Inject(method = "tryAddFreshEntityWithPassengers", at = @At("HEAD"), cancellable = true)
    private void horizon$addWithPassengers(net.minecraft.world.entity.Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!HorizonServer.isMirror(this)) return;
        ServerLevel level = (ServerLevel) (Object) this;
        boolean there = entity.getSelfAndPassengers().anyMatch(e -> level.getEntity(e.getUUID()) != null);
        if (!there) level.addFreshEntityWithPassengers(entity);
        cir.setReturnValue(!there);
    }

    private static final net.minecraft.world.level.border.WorldBorder HORIZON$BORDER = new net.minecraft.world.level.border.WorldBorder();

    /**
     * The world border mods' entities collide with: vanilla's default one (the mirror keeps no
     * saved data, where the level's is). Horizon keeps the real border for its players.
     */
    @Inject(method = "getWorldBorder", at = @At("HEAD"), cancellable = true)
    private void horizon$border(CallbackInfoReturnable<net.minecraft.world.level.border.WorldBorder> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HORIZON$BORDER);
    }

    /**
     * Entities tick wherever Horizon has the world (the level's entity manager, which a mirror
     * has none of, would say per chunk).
     */
    @Inject(method = "isPositionEntityTicking", at = @At("HEAD"), cancellable = true)
    private void horizon$entityTicking(net.minecraft.core.BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(((ServerLevel) (Object) this).isLoaded(pos));
    }

    @Inject(method = {"areEntitiesLoaded", "areEntitiesActuallyLoadedAndTicking", "canSpawnEntitiesInChunk"},
            at = @At("HEAD"), cancellable = true)
    private void horizon$entitiesLoaded(CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(true);
    }

    /**
     * Points of interest (villagers' beds, workstations) are Horizon's: a block change on the
     * mirror doesn't schedule vanilla's update, which reads the chunk map a mirror has none of.
     */
    @Inject(method = "updatePOIOnBlockStateChange", at = @At("HEAD"), cancellable = true)
    private void horizon$poi(CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    /**
     * An entity's event for the clients tracking it: a mod entity's death poof (EntityEvent.POOF,
     * which vanilla's client turns into particles) goes to Horizon's players as particles; the
     * rest are Horizon's to send.
     */
    @Inject(method = "broadcastEntityEvent", at = @At("HEAD"), cancellable = true)
    private void horizon$entityEvent(net.minecraft.world.entity.Entity entity, byte event,
                                     org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!HorizonServer.isMirror(this)) return;
        ci.cancel();
        dev.horizonlab.platform.HorizonEntities.entityEvent((ServerLevel) (Object) this, entity, event);
    }
}
