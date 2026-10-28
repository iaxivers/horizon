package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.TextFilter;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * What Horizon's mirrored dedicated server answers in place of its server.properties (which
 * Horizon reads itself): the overrides a new player's constructor reaches.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {
    @Inject(method = "createTextFilterForPlayer", at = @At("HEAD"), cancellable = true)
    private void horizon$textFilter(ServerPlayer player, CallbackInfoReturnable<TextFilter> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(TextFilter.DUMMY);
    }

    @Inject(method = "getForcedGameType", at = @At("HEAD"), cancellable = true)
    private void horizon$forcedGameType(CallbackInfoReturnable<GameType> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(null);
    }

    @Inject(method = "forceGameMode", at = @At("HEAD"), cancellable = true)
    private void horizon$forceGameMode(CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(false);
    }
}
