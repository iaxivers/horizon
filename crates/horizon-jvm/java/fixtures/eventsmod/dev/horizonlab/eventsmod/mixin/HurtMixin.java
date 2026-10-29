package dev.horizonlab.eventsmod.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cactus can't hurt players: a cancellable injection at the head of LivingEntity.hurtServer. */
@Mixin(LivingEntity.class)
public abstract class HurtMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void events$hurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer player && source.is(DamageTypes.CACTUS)) {
            player.sendSystemMessage(Component.literal("[events] no cactus for you"));
            cir.setReturnValue(false);
        }
    }
}
