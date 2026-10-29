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

    @Inject(method = "getWorldPath", at = @At("HEAD"), cancellable = true)
    private void horizon$worldPath(net.minecraft.world.level.storage.LevelResource resource,
                                   CallbackInfoReturnable<java.nio.file.Path> cir) {
        if (HorizonServer.isMirror(this) && HorizonServer.worldDir() != null) {
            cir.setReturnValue(HorizonServer.worldDir().resolve(resource.id()).normalize());
        }
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

    /** Loot's random sequences (seeded by world generation settings the mirror lacks). */
    @Inject(method = "getRandomSequence", at = @At("HEAD"), cancellable = true)
    private void horizon$randomSequence(net.minecraft.resources.Identifier id,
                                        CallbackInfoReturnable<net.minecraft.util.RandomSource> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(net.minecraft.util.RandomSource.create());
    }

    /** Horizon's services: names of the players it knows, nothing online (HorizonServices). */
    @Inject(method = "services", at = @At("HEAD"), cancellable = true)
    private void horizon$services(CallbackInfoReturnable<net.minecraft.server.Services> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.services());
    }
}
