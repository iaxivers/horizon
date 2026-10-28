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
}
