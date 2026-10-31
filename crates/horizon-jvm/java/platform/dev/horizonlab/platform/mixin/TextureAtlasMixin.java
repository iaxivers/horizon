package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import dev.horizonlab.platform.HorizonSprites;
import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The headless client's atlases hold no textures (nothing is uploaded): a sprite asked of one
 * is made from its PNG's name and size (HorizonSprites), and Horizon draws it from its own atlas.
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
    @Shadow private Map<Identifier, TextureAtlasSprite> texturesByName;
    @Shadow @Final private Identifier location;

    @Inject(method = "getSprite", at = @At("HEAD"), cancellable = true)
    private void horizon$sprite(Identifier id, CallbackInfoReturnable<TextureAtlasSprite> cir) {
        if (texturesByName == null && HorizonClient.headless()) cir.setReturnValue(HorizonSprites.get(location, id));
    }
}
