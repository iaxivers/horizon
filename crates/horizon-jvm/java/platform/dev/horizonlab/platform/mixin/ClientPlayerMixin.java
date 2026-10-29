package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The headless client's players have no tab-list entry (no connection): their game mode
 * (isSpectator, isCreative, which screens ask) is their server player's.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class ClientPlayerMixin {
    @Inject(method = "gameMode", at = @At("HEAD"), cancellable = true)
    private void horizon$gameMode(CallbackInfoReturnable<GameType> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(HorizonClient.gameMode(this));
    }
}
