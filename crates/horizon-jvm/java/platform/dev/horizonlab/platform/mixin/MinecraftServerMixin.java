package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** What Horizon's mirrored server answers in place of world data it doesn't have. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "getForcedGameType", at = @At("HEAD"), cancellable = true)
    private void horizon$forcedGameType(CallbackInfoReturnable<GameType> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(null);
    }

    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void horizon$permissions(NameAndId player, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.permissions(player));
    }
}
