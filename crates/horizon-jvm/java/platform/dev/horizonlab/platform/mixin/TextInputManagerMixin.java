package dev.horizonlab.platform.mixin;

import com.mojang.blaze3d.platform.TextInputManager;
import dev.horizonlab.platform.HorizonClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Text boxes tell the window where typing happens (SDL's text input, for input methods): the
 * headless client has no window. Horizon's game window takes the keys and sends the text.
 */
@Mixin(TextInputManager.class)
public abstract class TextInputManagerMixin {
    @Inject(method = {"setTextInputArea", "startTextInput", "stopTextInput(Ljava/lang/Object;)V",
            "stopTextInput()V", "onTextInputFocusChange"}, at = @At("HEAD"), cancellable = true)
    private void horizon$noWindow(CallbackInfo ci) {
        if (HorizonClient.headless()) ci.cancel();
    }
}
