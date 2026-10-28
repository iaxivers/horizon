package dev.horizonlab.platform;

import com.mojang.authlib.GameProfile;
import dev.horizonlab.bridge.Natives;
import dev.horizonlab.platform.mixin.EntityAccess;
import dev.horizonlab.platform.mixin.LevelAccess;
import dev.horizonlab.platform.mixin.MinecraftServerAccess;
import dev.horizonlab.platform.mixin.PlayerListAccess;
import dev.horizonlab.platform.mixin.ServerCommonListenerAccess;
import dev.horizonlab.platform.mixin.ServerLevelAccess;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Horizon's server as vanilla objects, for Fabric mods. Horizon simulates the world in Rust;
 * once per tick it calls {@link #tick} with its players and what they did, and this class
 * mirrors them into a {@code MinecraftServer}, {@code ServerLevel}s and {@code ServerPlayer}s
 * made without their constructors ({@link Natives#allocate}), then fires Fabric API's events
 * with them. A mirror holds what Horizon gave it: a player's UUID, name, position, rotation,
 * level, game mode and health. What a mod asks of a mirror goes back to Horizon through the
 * natives (see the mixins: system messages, broadcasts).
 *
 * <p>Every event is fired on its own: a mod that throws is logged and the rest go on.
 */
public final class HorizonServer {
    private HorizonServer() {}

    private static MinecraftServer server;
    private static PlayerList playerList;
    private static final Map<ResourceKey<Level>, ServerLevel> LEVELS = new LinkedHashMap<>();
    private static final List<ServerPlayer> PLAYERS = new ArrayList<>();
    private static final Map<UUID, ServerPlayer> BY_UUID = new HashMap<>();
    /** Every player ever mirrored, by UUID: a player who leaves and comes back is the same object. */
    private static final Map<UUID, ServerPlayer> KNOWN = new HashMap<>();
    /**
     * Every mirror: the server, its player list, the levels, players and their connections.
     * An identity set: an entity's equals and hashCode read its id, which a mirror has only
     * once its constructor ran.
     */
    private static final Set<Object> MIRRORS = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static boolean started;

    /** Whether this object is one of Horizon's mirrors (not a vanilla object a mod made). */
    public static boolean isMirror(Object object) {
        return object != null && MIRRORS.contains(object);
    }

    /** Ids for mirrored entities, apart from the ids Horizon gives its own (counting down). */
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID = new java.util.concurrent.atomic.AtomicInteger(0x7fff0000);

    /** An entity id for a mirror. */
    public static int nextEntityId() {
        return NEXT_ID.getAndIncrement();
    }

    private static final Map<UUID, Integer> MODES = new ConcurrentHashMap<>();
    private static final Map<UUID, net.minecraft.stats.ServerStatsCounter> STATS = new ConcurrentHashMap<>();
    private static final Map<UUID, net.minecraft.server.PlayerAdvancements> ADVANCEMENTS = new ConcurrentHashMap<>();

    /**
     * A player's permissions on Horizon's server. Single player and a LAN host own their world;
     * operators on dedicated servers come with Horizon's op list later.
     */
    public static net.minecraft.server.permissions.LevelBasedPermissionSet permissions(net.minecraft.server.players.NameAndId player) {
        return net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER;
    }

    /** A player's stats: none kept yet (Horizon keeps its own). */
    public static net.minecraft.stats.ServerStatsCounter stats(net.minecraft.world.entity.player.Player player) {
        return STATS.computeIfAbsent(player.getUUID(), u -> allocate(net.minecraft.stats.ServerStatsCounter.class));
    }

    /** A player's advancements: none kept yet (Horizon keeps its own). */
    public static net.minecraft.server.PlayerAdvancements advancements(ServerPlayer player) {
        return ADVANCEMENTS.computeIfAbsent(player.getUUID(), u -> allocate(net.minecraft.server.PlayerAdvancements.class));
    }

    /** The mirrored server, once Horizon's first tick made it. */
    public static MinecraftServer server() {
        return server;
    }

    private static <T> T allocate(Class<T> type) {
        T object = type.cast(Natives.allocate(type));
        MIRRORS.add(object);
        return object;
    }

    private static void setUp(boolean dedicated) throws ClassNotFoundException {
        String serverClass = dedicated
                ? "net.minecraft.server.dedicated.DedicatedServer"
                : "net.minecraft.client.server.IntegratedServer";
        String listClass = dedicated
                ? "net.minecraft.server.dedicated.DedicatedPlayerList"
                : "net.minecraft.client.server.IntegratedPlayerList";
        ClassLoader loader = HorizonServer.class.getClassLoader();
        server = (MinecraftServer) allocate(Class.forName(serverClass, true, loader));
        playerList = (PlayerList) allocate(Class.forName(listClass, true, loader));
        ((PlayerListAccess) playerList).horizon$setPlayers(PLAYERS);
        ((PlayerListAccess) playerList).horizon$setPlayersByUUID(BY_UUID);
        ((PlayerListAccess) playerList).horizon$setServer(server);
        ((MinecraftServerAccess) server).horizon$setPlayerList(playerList);
        ((MinecraftServerAccess) server).horizon$setLevels(LEVELS);
        // The registries and resources HorizonData loaded: vanilla's data pack and the mods'.
        HorizonData.Loaded data = HorizonData.loaded;
        if (data != null) {
            ((MinecraftServerAccess) server).horizon$setRegistries(data.registries());
            ((MinecraftServerAccess) server).horizon$setWorldData(data.worldData());
            try {
                var constructor = Class.forName("net.minecraft.server.MinecraftServer$ReloadableResources", true, loader)
                        .getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                var field = MinecraftServer.class.getDeclaredField("resources");
                field.setAccessible(true);
                field.set(server, constructor.newInstance(data.resources(), data.reloadable()));
            } catch (ReflectiveOperationException e) {
                Natives.log("Horizon's server mirror has no resources: " + e);
            }
        }
        for (String dimension : new String[] {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
            level(dimension);
        }
    }

    private static ServerLevel level(String dimension) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
        return LEVELS.computeIfAbsent(key, k -> {
            ServerLevel level = allocate(ServerLevel.class);
            LevelAccess access = (LevelAccess) level;
            access.horizon$setDimension(k);
            access.horizon$setClientSide(false);
            access.horizon$setRandom(net.minecraft.util.RandomSource.create());
            ((ServerLevelAccess) level).horizon$setServer(server);
            HorizonData.Loaded data = HorizonData.loaded;
            if (data != null) {
                net.minecraft.core.RegistryAccess.Frozen registries = data.registries().compositeAccess();
                access.horizon$setRegistryAccess(registries);
                access.horizon$setDamageSources(new net.minecraft.world.damagesource.DamageSources(registries));
                access.horizon$setLevelData(data.worldData());
                // The dimension's type: the level stem's, as vanilla's server binds it.
                var stem = registries.lookupOrThrow(Registries.LEVEL_STEM)
                        .get(ResourceKey.create(Registries.LEVEL_STEM, k.identifier()));
                stem.ifPresent(s -> access.horizon$setDimensionType(s.value().type()));
            }
            return level;
        });
    }

    private static ServerPlayer player(UUID uuid, String name, ServerLevel level) {
        ServerPlayer player = KNOWN.get(uuid);
        if (player == null) {
            // The real constructor, so a mirror has what vanilla's code expects of a player:
            // attributes, synced data, inventory, food, abilities. The mixins answer what it
            // asks of the server that only a running world would know.
            player = new ServerPlayer(server, level, new GameProfile(uuid, name),
                    net.minecraft.server.level.ClientInformation.createDefault());
            MIRRORS.add(player);
            ServerGamePacketListenerImpl connection = allocate(ServerGamePacketListenerImpl.class);
            connection.player = player;
            ((ServerCommonListenerAccess) connection).horizon$setServer(server);
            player.connection = connection;
            KNOWN.put(uuid, player);
        }
        return player;
    }

    private static final PacketSender NO_PACKETS = (PacketSender) Proxy.newProxyInstance(
            PacketSender.class.getClassLoader(),
            new Class<?>[] {PacketSender.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return "Horizon's packet sender (none yet)";
                    default: return null;
                }
            });

    /** Runs a mod event, logging (not throwing) what a mod throws, ten times per event at most. */
    private static void fire(String event, Runnable run) {
        try {
            run.run();
        } catch (Throwable t) {
            int count = FAILURES.merge(event, 1, Integer::sum);
            if (count <= 10) {
                Natives.log("A mod failed in " + event + ": " + t);
                for (StackTraceElement frame : t.getStackTrace()) {
                    if (frame.getClassName().startsWith("dev.horizonlab.platform")) break;
                    Natives.log("    at " + frame);
                }
            }
        }
    }

    /**
     * One server tick, called by Horizon (horizon-jvm's events.rs) at the tick's end.
     *
     * @param motion five values per player: x, y, z, yaw, pitch
     * @param kinds per event: 0 joined, 1 left, 2 chat, 3 broke a block, 4 placed a block
     * @param who per event: the player's UUID
     * @param texts per event: the chat line, or the block state ("minecraft:oak_log[axis=y]")
     * @param positions three per event: the block's x, y, z
     */
    public static void tick(long tick, boolean dedicated, String[] ids, String[] names, String[] dimensions,
                            double[] motion, int[] modes, float[] health,
                            int[] kinds, String[] who, String[] texts, int[] positions) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        try {
            if (server == null) setUp(dedicated);
        } catch (Throwable t) {
            Natives.log("Horizon's server mirror could not be set up: " + t);
            for (StackTraceElement frame : t.getStackTrace()) Natives.log("    at " + frame);
            return;
        }
        MinecraftServer server = HorizonServer.server;
        if (!started) {
            started = true;
            fire("ServerLifecycleEvents.SERVER_STARTING", () -> ServerLifecycleEvents.SERVER_STARTING.invoker().onServerStarting(server));
            fire("ServerLifecycleEvents.SERVER_STARTED", () -> ServerLifecycleEvents.SERVER_STARTED.invoker().onServerStarted(server));
        }
        fire("ServerTickEvents.START_SERVER_TICK", () -> ServerTickEvents.START_SERVER_TICK.invoker().onStartTick(server));
        for (ServerLevel level : new ArrayList<>(LEVELS.values())) {
            fire("ServerTickEvents.START_LEVEL_TICK", () -> ServerTickEvents.START_LEVEL_TICK.invoker().onStartTick(level));
        }

        // Mirror the players: those still on, as Horizon gives them.
        Map<UUID, ServerPlayer> now = new LinkedHashMap<>();
        for (int i = 0; i < ids.length; i++) {
            try {
                UUID uuid = UUID.fromString(ids[i]);
                ServerLevel level = level(dimensions[i]);
                ServerPlayer player = player(uuid, names[i], level);
                if (player.level() != level) ((EntityAccess) player).horizon$setLevel(level);
                player.setPosRaw(motion[i * 5], motion[i * 5 + 1], motion[i * 5 + 2]);
                player.setYRot((float) motion[i * 5 + 3]);
                player.setXRot((float) motion[i * 5 + 4]);
                player.setHealth(health[i]);
                MODES.put(uuid, modes[i]);
                now.put(uuid, player);
            } catch (Throwable t) {
                fire("mirroring player " + names[i], () -> { throw new RuntimeException(t); });
            }
        }

        for (int e = 0; e < kinds.length; e++) {
            UUID uuid = UUID.fromString(who[e]);
            ServerPlayer player = KNOWN.get(uuid);
            if (player == null) continue;
            switch (kinds[e]) {
                case 0 -> {
                    PLAYERS.add(player);
                    BY_UUID.put(uuid, player);
                    ServerGamePacketListenerImpl connection = player.connection;
                    ServerLevel level = (ServerLevel) player.level();
                    fire("ServerPlayConnectionEvents.INIT", () -> ServerPlayConnectionEvents.INIT.invoker().onPlayInit(connection, server));
                    fire("ServerEntityEvents.ENTITY_LOAD", () -> ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(player, level));
                    fire("ServerPlayConnectionEvents.JOIN", () -> ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(connection, NO_PACKETS, server));
                }
                case 1 -> {
                    ServerGamePacketListenerImpl connection = player.connection;
                    ServerLevel level = (ServerLevel) player.level();
                    fire("ServerPlayConnectionEvents.DISCONNECT", () -> ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(connection, server));
                    fire("ServerEntityEvents.ENTITY_UNLOAD", () -> ServerEntityEvents.ENTITY_UNLOAD.invoker().onUnload(player, level));
                    PLAYERS.remove(player);
                    BY_UUID.remove(uuid);
                }
                case 2 -> {
                    PlayerChatMessage message = PlayerChatMessage.unsigned(uuid, texts[e]);
                    fire("ServerMessageEvents.CHAT_MESSAGE", () -> ServerMessageEvents.CHAT_MESSAGE.invoker().onChatMessage(message, player, null));
                }
                case 3 -> {
                    BlockPos pos = new BlockPos(positions[e * 3], positions[e * 3 + 1], positions[e * 3 + 2]);
                    BlockState state = state(texts[e]);
                    Level level = player.level();
                    fire("PlayerBlockBreakEvents.AFTER", () -> PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, player, pos, state, null));
                }
                default -> { }
            }
        }
        // A player Horizon lists but never announced (the bridge started late) is on all the same.
        for (Map.Entry<UUID, ServerPlayer> entry : now.entrySet()) {
            if (!BY_UUID.containsKey(entry.getKey())) {
                PLAYERS.add(entry.getValue());
                BY_UUID.put(entry.getKey(), entry.getValue());
            }
        }

        for (ServerLevel level : new ArrayList<>(LEVELS.values())) {
            fire("ServerTickEvents.END_LEVEL_TICK", () -> ServerTickEvents.END_LEVEL_TICK.invoker().onEndTick(level));
        }
        fire("ServerTickEvents.END_SERVER_TICK", () -> ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server));
    }

    private static BlockState state(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (Exception e) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
    }
}
