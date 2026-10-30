package dev.horizonlab.loadermod;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Chunk tickets the way chunk loader mods take them, by command:
 * {@code /loader ticket <x> <z> <radius>} and {@code /loader unticket ...} (a persistent ticket
 * type of its own, as a mod's chunk loader block's), {@code /loader force <x> <z> <bool>}
 * (ServerLevel.setChunkForced, vanilla's /forceload path). Coordinates are chunks.
 */
public class LoaderMod implements ModInitializer {
    /** Saved with the world, loads and ticks, never times out (FORCED's flags). */
    public static final TicketType LOADER = Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath("horizon_loader", "loader"),
            new TicketType(TicketType.NO_TIMEOUT,
                    TicketType.FLAG_PERSIST | TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
                            | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) -> dispatcher.register(
                Commands.literal("loader")
                        .then(Commands.literal("ticket").then(chunk((x, z, r, source) -> {
                            source.getLevel().getChunkSource().addTicketWithRadius(LOADER, new ChunkPos(x, z), r);
                            return "ticket at " + x + " " + z + " radius " + r;
                        })))
                        .then(Commands.literal("unticket").then(chunk((x, z, r, source) -> {
                            source.getLevel().getChunkSource().removeTicketWithRadius(LOADER, new ChunkPos(x, z), r);
                            return "ticket removed at " + x + " " + z;
                        })))
                        .then(Commands.literal("force")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("on", BoolArgumentType.bool())
                                                        .executes(c -> {
                                                            int x = IntegerArgumentType.getInteger(c, "x");
                                                            int z = IntegerArgumentType.getInteger(c, "z");
                                                            boolean on = BoolArgumentType.getBool(c, "on");
                                                            boolean changed = c.getSource().getLevel().setChunkForced(x, z, on);
                                                            c.getSource().sendSystemMessage(Component.literal(
                                                                    "forced " + x + " " + z + " " + on + " (changed: " + changed + ")"));
                                                            return 1;
                                                        })))))));
    }

    interface Action {
        String run(int x, int z, int radius, net.minecraft.commands.CommandSourceStack source);
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<net.minecraft.commands.CommandSourceStack, ?> chunk(Action action) {
        return Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("z", IntegerArgumentType.integer())
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(c -> {
                                    String said = action.run(IntegerArgumentType.getInteger(c, "x"),
                                            IntegerArgumentType.getInteger(c, "z"),
                                            IntegerArgumentType.getInteger(c, "radius"), c.getSource());
                                    c.getSource().sendSystemMessage(Component.literal(said));
                                    return 1;
                                })));
    }
}
