package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mirrored server's task loop: mods call in on Horizon's server thread (it is the server
 * thread to them), and what they schedule (server.execute) runs at the end of Horizon's tick,
 * as vanilla runs tasks between ticks.
 */
@Mixin(BlockableEventLoop.class)
public abstract class EventLoopMixin {
    @Inject(method = "isSameThread", at = @At("HEAD"), cancellable = true)
    private void horizon$sameThread(CallbackInfoReturnable<Boolean> cir) {
        if (HorizonServer.isMirror(this)) cir.setReturnValue(HorizonServer.onServerThread());
    }

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void horizon$schedule(Runnable task, CallbackInfo ci) {
        if (HorizonServer.isMirror(this)) {
            HorizonServer.schedule(task);
            ci.cancel();
        }
    }
}
