package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Recipe displays are read in the client's level (`minecraft.level`), whose fuel values come
 * from its connection, which the headless client's levels have none of: they read the server
 * level of the same dimension (HorizonServer.serverSide), whose registries and fuel values are
 * the ones a connection would have sent. (Else REI drops every recipe a tag or fuel shows in.)
 */
@Mixin(SlotDisplayContext.class)
public abstract class SlotDisplayContextMixin {
    @ModifyVariable(method = "fromLevel", at = @At("HEAD"), argsOnly = true)
    private static Level horizon$level(Level level) {
        if (!HorizonClient.headless()) return level;
        if (level == null && HorizonClient.minecraft().player != null) level = HorizonClient.minecraft().player.level();
        return level == null ? null : dev.horizonlab.platform.HorizonServer.serverSide(level);
    }
}
