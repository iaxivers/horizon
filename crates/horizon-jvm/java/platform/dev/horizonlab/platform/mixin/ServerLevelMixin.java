package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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

    /** Its saved data (vanilla's is its chunk source's, which a mirror has none of). */
    @Inject(method = "getDataStorage", at = @At("HEAD"), cancellable = true)
    private void horizon$dataStorage(CallbackInfoReturnable<net.minecraft.world.level.storage.SavedDataStorage> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(dev.horizonlab.platform.HorizonSavedData.level((ServerLevel) (Object) this));
    }
}
