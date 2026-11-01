package dev.horizonlab.joinmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class JoinClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                say("horizon_join: fabric JOIN " + client.player.getName().getString()
                        + " connection " + (client.getConnection() == handler)));
    }

    private static void say(String text) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
        System.out.println(text);
    }
}
