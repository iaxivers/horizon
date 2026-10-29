package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric's packet context of a listener is its connection's, which a mirrored player has none
 * of: a context of its own (the server, its registries, the player's profile). Mods run code
 * "as from the player's connection" with it (Tom's Storage's terminal sync). After Fabric's
 * mixin, which adds the method.
 */
@Mixin(value = ServerCommonPacketListenerImpl.class, priority = 2000)
public abstract class MirrorPacketContextMixin {
    @Inject(method = "getPacketContext", at = @At("HEAD"), cancellable = true, remap = false)
    private void horizon$context(CallbackInfoReturnable<PacketContext> cir) {
        Object self = this;
        if (HorizonServer.isMirror((ServerCommonPacketListenerImpl) self)) {
            cir.setReturnValue(HorizonServer.packetContext((ServerCommonPacketListenerImpl) self));
        }
    }
}
