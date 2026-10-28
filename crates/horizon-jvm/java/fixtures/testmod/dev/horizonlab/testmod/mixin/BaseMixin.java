package dev.horizonlab.testmod.mixin;

import dev.horizonlab.fixture.Base;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Proves Mixin patches facade classes: Base.answer() returns 99 instead of the stub's 0. */
@Mixin(Base.class)
public class BaseMixin {
    @Inject(method = "answer", at = @At("HEAD"), cancellable = true)
    private void horizonTest$answer(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(99);
    }
}
