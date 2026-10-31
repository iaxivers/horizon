package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import dev.horizonlab.platform.HorizonTextures;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The headless client's texture manager: named views (HorizonTextures), nothing uploaded. */
@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {
    @Inject(method = "getTexture", at = @At("HEAD"), cancellable = true)
    private void horizon$texture(Identifier id, CallbackInfoReturnable<AbstractTexture> cir) {
        if (HorizonClient.headless()) cir.setReturnValue(HorizonTextures.get(id));
    }

    /** A mod's own texture under a name (a dynamic one, drawn into memory): kept by name. */
    @Inject(method = "register", at = @At("HEAD"), cancellable = true)
    private void horizon$register(Identifier id, AbstractTexture texture, CallbackInfo ci) {
        if (!HorizonClient.headless()) return;
        ci.cancel();
        HorizonTextures.register(id, texture);
    }

    @Inject(method = "release", at = @At("HEAD"), cancellable = true)
    private void horizon$release(Identifier id, CallbackInfo ci) {
        if (!HorizonClient.headless()) return;
        ci.cancel();
        HorizonTextures.release(id);
    }
}
