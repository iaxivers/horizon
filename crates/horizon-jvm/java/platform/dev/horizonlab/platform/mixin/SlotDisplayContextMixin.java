package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Recipe displays are read in the client's level (`minecraft.level`), which the headless client
 * has none of: they read the player's own level (the server's), whose registries and fuel
 * values are the same ones.
 */
@Mixin(SlotDisplayContext.class)
public abstract class SlotDisplayContextMixin {
    @ModifyVariable(method = "fromLevel", at = @At("HEAD"), argsOnly = true)
    private static Level horizon$level(Level level) {
        if (level == null && HorizonClient.headless() && HorizonClient.minecraft().player != null) {
            return HorizonClient.minecraft().player.level();
        }
        return level;
    }
}
