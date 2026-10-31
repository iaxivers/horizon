package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import dev.horizonlab.platform.HorizonServer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The headless client's levels (HorizonClient.clientLevel) hold no entities of their own: they
 * see the mirror's (its players, mods' entities), as a client sees what the server tracks.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "getEntities()Lnet/minecraft/world/level/entity/LevelEntityGetter;", at = @At("HEAD"), cancellable = true)
    private void horizon$entities(CallbackInfoReturnable<LevelEntityGetter<Entity>> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonClient.entities((ClientLevel) (Object) this));
    }

    // What a client level asks its connection for: the mirror's level answers (one process).

    @Inject(method = "enabledFeatures", at = @At("HEAD"), cancellable = true)
    private void horizon$features(CallbackInfoReturnable<net.minecraft.world.flag.FeatureFlagSet> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(horizon$server().enabledFeatures());
    }

    @Inject(method = "recipeAccess", at = @At("HEAD"), cancellable = true)
    private void horizon$recipes(CallbackInfoReturnable<net.minecraft.world.item.crafting.RecipeAccess> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(horizon$server().recipeAccess());
    }

    @Inject(method = "getScoreboard", at = @At("HEAD"), cancellable = true)
    private void horizon$scoreboard(CallbackInfoReturnable<net.minecraft.world.scores.Scoreboard> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(horizon$server().getScoreboard());
    }

    /** A packet a mod's client code sends through the level: no connection to take it. */
    @Inject(method = "sendPacketToServer", at = @At("HEAD"), cancellable = true)
    private void horizon$send(net.minecraft.network.protocol.Packet<?> packet, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) ci.cancel();
    }

    private net.minecraft.world.level.Level horizon$server() {
        return HorizonServer.serverSide((ClientLevel) (Object) this);
    }
}
