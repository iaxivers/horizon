package dev.horizonlab.eventsmod.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Join and leave the way libraries hook them: injections into vanilla's PlayerList. */
@Mixin(PlayerList.class)
public abstract class JoinLeaveMixin {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void events$joined(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        player.sendSystemMessage(Component.literal("[events] a mixin saw " + player.getName().getString() + " join"));
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void events$left(ServerPlayer player, CallbackInfo ci) {
        ((PlayerList) (Object) this).broadcastSystemMessage(
                Component.literal("[events] a mixin saw " + player.getName().getString() + " leave"), false);
    }
}
