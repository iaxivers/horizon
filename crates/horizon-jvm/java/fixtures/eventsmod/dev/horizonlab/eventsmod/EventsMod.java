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
 * command, /events (whoami, add a b, echo text, count, kit, take); breaking a birch log fells the birch logs above it, and
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
                        .then(Commands.literal("count").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            var inventory = player.getInventory();
                            int items = 0;
                            for (int slot = 0; slot < 36; slot++) items += inventory.getItem(slot).getCount();
                            var head = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
                            int total = items;
                            context.getSource().sendSuccess(() -> Component.literal("[events] you carry " + total
                                    + " items, wearing " + BuiltInRegistries.ITEM.getKey(head.getItem())), false);
                            return items;
                        }))
                        .then(Commands.literal("kit").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            var registries = context.getSource().registryAccess();
                            var sword = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
                            sword.enchant(registries.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                                    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS), 3);
                            player.getInventory().add(sword);
                            player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 16));
                            context.getSource().sendSuccess(() -> Component.literal("[events] here's your kit"), false);
                            return 1;
                        }))
                        .then(Commands.literal("take").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            var inventory = player.getInventory();
                            for (int slot = 0; slot < 36; slot++) {
                                if (inventory.getItem(slot).is(net.minecraft.world.item.Items.BREAD)) {
                                    inventory.getItem(slot).shrink(1);
                                    break;
                                }
                            }
                            return 1;
                        }))
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
        // Right-clicking a bell rings it the mod's way (the game does nothing more); other
        // blocks are the game's.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !level.getBlockState(hit.getBlockPos()).is(net.minecraft.world.level.block.Blocks.BELL)) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            player.sendSystemMessage(Component.literal("[events] ding, with "
                    + BuiltInRegistries.ITEM.getKey(player.getItemInHand(hand).getItem()) + " in hand"));
            return net.minecraft.world.InteractionResult.SUCCESS;
        });
        // A tree feller, as FallingTree does it: breaking a log breaks the logs above it, with
        // their drops. And diamond ore can't be broken.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (state.is(net.minecraft.world.level.block.Blocks.DIAMOND_ORE)) {
                player.sendSystemMessage(Component.literal("[events] no diamonds for you"));
                return false;
            }
            // Birch only: real tree mods (FallingTree) take the others.
            if (!state.is(net.minecraft.world.level.block.Blocks.BIRCH_LOG)) return true;
            int felled = 0;
            net.minecraft.core.BlockPos above = pos.above();
            while (level.isLoaded(above) && level.getBlockState(above).is(net.minecraft.world.level.block.Blocks.BIRCH_LOG) && felled < 64) {
                level.destroyBlock(above, true, player);
                felled++;
                above = above.above();
            }
            if (felled > 0) player.sendSystemMessage(Component.literal("[events] felled " + felled + " logs"));
            return true;
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((old, now, alive) ->
                now.sendSystemMessage(Component.literal("[events] welcome back " + now.getName().getString())));
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) ->
                player.sendSystemMessage(Component.literal("[events] you broke "
                        + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + pos.toShortString())));
    }
}
