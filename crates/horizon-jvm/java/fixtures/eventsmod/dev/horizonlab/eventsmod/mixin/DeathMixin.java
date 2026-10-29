package dev.horizonlab.eventsmod.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A death the way libraries hook it: an injection at the head of LivingEntity.die. */
@Mixin(LivingEntity.class)
public abstract class DeathMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void events$died(DamageSource source, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal("[events] a mixin saw " + player.getName().getString()
                    + " die of " + source.typeHolder().getRegisteredName()));
        }
    }
}
