package dev.horizonlab.eventsmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * A server-side Fabric mod written the usual way, against vanilla and Fabric API only: it
 * greets players who join, answers chat, reports broken blocks and counts ticks. It knows
 * nothing about Horizon; the bridge's tests (tests/real_game.rs) and players run it as it is.
 */
public class EventsMod implements ModInitializer {
    private int ticks;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                server.getPlayerList().broadcastSystemMessage(Component.literal("[events] server started"), false));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ticks++;
            if (ticks % 100 == 0) {
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("[events] " + ticks + " ticks, " + server.getPlayerList().getPlayers().size() + " players on"), false);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            player.sendSystemMessage(Component.literal("[events] Welcome " + player.getName().getString()
                    + " at " + player.blockPosition().toShortString() + " in " + player.level().dimension().identifier()
                    + ", health " + player.getHealth()));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("[events] " + handler.player.getName().getString() + " left"), false));
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                sender.sendSystemMessage(Component.literal("[events] you said: " + message.signedContent())));
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) ->
                player.sendSystemMessage(Component.literal("[events] you broke "
                        + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + pos.toShortString())));
    }
}
