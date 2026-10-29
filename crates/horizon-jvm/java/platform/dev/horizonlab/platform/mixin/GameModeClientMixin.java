package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mod's screen in the headless client clicks what it shows: the server's menu itself, in this
 * JVM, not a packet to a server (HorizonClient.containerInput).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class GameModeClientMixin {
    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void horizon$containerInput(int containerId, int slot, int button, ContainerInput input, Player player,
                                        CallbackInfo ci) {
        if (!HorizonClient.headless()) return;
        ci.cancel();
        HorizonClient.containerInput(containerId, slot, button, input);
    }

    @Inject(method = "handleInventoryButtonClick", at = @At("HEAD"), cancellable = true)
    private void horizon$buttonClick(int containerId, int button, CallbackInfo ci) {
        if (!HorizonClient.headless()) return;
        ci.cancel();
        HorizonClient.buttonClick(containerId, button);
    }
}
