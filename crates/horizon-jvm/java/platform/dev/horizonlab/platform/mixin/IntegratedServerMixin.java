package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** What Horizon's mirrored single-player server answers: the overrides a new player reaches. */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Inject(method = "getForcedGameType", at = @At("HEAD"), cancellable = true)
    private void horizon$forcedGameType(CallbackInfoReturnable<GameType> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(null);
    }

    @Inject(method = "forceGameMode", at = @At("HEAD"), cancellable = true)
    private void horizon$forceGameMode(CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(false);
    }

    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void horizon$permissions(NameAndId player, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.permissions(player));
    }

    /** The game folder Fabric runs in (config/ is there), not a client's or the process's. */
    @Inject(method = "getServerDirectory", at = @At("HEAD"), cancellable = true)
    private void horizon$serverDirectory(CallbackInfoReturnable<java.nio.file.Path> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
    }
}
