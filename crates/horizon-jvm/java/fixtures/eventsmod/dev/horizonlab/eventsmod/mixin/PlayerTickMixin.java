package dev.horizonlab.eventsmod.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A per-player tick the way mods count one: an injection at the head of Player.tick. */
@Mixin(Player.class)
public abstract class PlayerTickMixin {
    @Unique
    private int events$ticks;

    @Inject(method = "tick", at = @At("HEAD"))
    private void events$tick(CallbackInfo ci) {
        if (++events$ticks % 50 == 0) {
            ((Player) (Object) this).sendSystemMessage(Component.literal("[events] a mixin counted " + events$ticks + " player ticks"));
        }
    }
}
