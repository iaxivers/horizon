package dev.horizonlab.eventsmod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
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
 * greets players who join, answers chat, reports broken blocks, counts ticks and has a
 * command, /events (whoami, add a b, echo text); breaking a log fells the logs above it, and
 * diamond ore can't be broken. It knows
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
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("events")
                        .then(Commands.literal("whoami").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            context.getSource().sendSuccess(() -> Component.literal("[events] you are "
                                    + player.getName().getString() + " at " + player.blockPosition().toShortString()), false);
                            return 1;
                        }))
                        .then(Commands.literal("add")
                                .then(Commands.argument("a", IntegerArgumentType.integer())
                                        .then(Commands.argument("b", IntegerArgumentType.integer()).executes(context -> {
                                            int sum = IntegerArgumentType.getInteger(context, "a") + IntegerArgumentType.getInteger(context, "b");
                                            context.getSource().sendSuccess(() -> Component.literal("[events] " + sum), false);
                                            return sum;
                                        }))))
                        .then(Commands.literal("secret")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> {
                                    context.getSource().sendSuccess(() -> Component.literal("[events] the secret is 42"), false);
                                    return 1;
                                }))
                        .then(Commands.literal("echo")
                                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(context -> {
                                    context.getSource().sendSuccess(() -> Component.literal("[events] "
                                            + StringArgumentType.getString(context, "text")), false);
                                    return 1;
                                })))));
        // A tree feller, as FallingTree does it: breaking a log breaks the logs above it, with
        // their drops. And diamond ore can't be broken.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (state.is(net.minecraft.world.level.block.Blocks.DIAMOND_ORE)) {
                player.sendSystemMessage(Component.literal("[events] no diamonds for you"));
                return false;
            }
            if (!state.is(net.minecraft.tags.BlockTags.LOGS)) return true;
            int felled = 0;
            net.minecraft.core.BlockPos above = pos.above();
            while (level.isLoaded(above) && level.getBlockState(above).is(net.minecraft.tags.BlockTags.LOGS) && felled < 64) {
                level.destroyBlock(above, true, player);
                felled++;
                above = above.above();
            }
            if (felled > 0) player.sendSystemMessage(Component.literal("[events] felled " + felled + " logs"));
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) ->
                player.sendSystemMessage(Component.literal("[events] you broke "
                        + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + pos.toShortString())));
    }
}
