package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonServer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A command Horizon runs for its own entities runs here for the mods' (runForEntities):
 * its selectors find no players, so nothing happens to them twice.
 */
@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin {
    @Inject(method = "findEntities", at = @At("RETURN"), cancellable = true)
    private void horizon$noPlayers(CommandSourceStack source, CallbackInfoReturnable<List<? extends Entity>> cir) {
        if (!HorizonServer.ENTITIES_ONLY.get()) return;
        List<Entity> kept = new ArrayList<>();
        for (Entity e : cir.getReturnValue()) if (!(e instanceof Player)) kept.add(e);
        cir.setReturnValue(kept);
    }
}
