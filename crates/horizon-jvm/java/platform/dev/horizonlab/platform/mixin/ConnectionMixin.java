package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mirrored player's connection. With the headless client its client code runs in this JVM,
 * beside the server's, as singleplayer's integrated server does: vanilla's answer there is a
 * memory connection, and mods skip what only a remote client needs (Forge Config API Port's
 * config sync, which otherwise expects a configuration phase the mirror never had).
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "isMemoryConnection", at = @At("HEAD"), cancellable = true)
    private void horizon$memory(CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.loop());
    }
}
