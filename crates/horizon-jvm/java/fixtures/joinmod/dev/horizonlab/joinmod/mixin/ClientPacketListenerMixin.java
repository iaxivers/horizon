package dev.horizonlab.joinmod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    // Where Puzzles Lib fires its client player join.
    @Inject(method = "handleLogin", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;resetPos()V", shift = At.Shift.AFTER))
    private void horizon_join$login(ClientboundLoginPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        say("horizon_join: handleLogin " + minecraft.player.getName().getString()
                + " game mode " + (minecraft.gameMode != null) + " network " + (minecraft.getConnection().getConnection() != null));
    }

    private static void say(String text) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
        System.out.println(text);
    }
}
