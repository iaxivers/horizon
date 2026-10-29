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
import net.minecraft.world.item.ItemStack;
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
    private static final Map<ServerLevel, List<ServerPlayer>> LEVEL_PLAYERS = new IdentityHashMap<>();
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
    /** Horizon's world folder, where mods keep their world data (getWorldPath). */
    private static java.nio.file.Path worldDir;

    /** The world folder Horizon plays, for MinecraftServer.getWorldPath. */
    public static java.nio.file.Path worldDir() {
        return worldDir;
    }

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

    /** Permission levels by player, as Horizon gives them each tick (0 everyone to 4 owner). */
    private static final Map<UUID, Integer> PERMISSIONS = new ConcurrentHashMap<>();

    /** A player's permissions on Horizon's server: the level Horizon gave for them. */
    public static net.minecraft.server.permissions.LevelBasedPermissionSet permissions(net.minecraft.server.players.NameAndId player) {
        int level = player == null ? 0 : PERMISSIONS.getOrDefault(player.id(), 0);
        return net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(
                net.minecraft.server.permissions.PermissionLevel.byId(level));
    }

    /** A player's stats: none kept yet (Horizon keeps its own). */
    public static net.minecraft.stats.ServerStatsCounter stats(net.minecraft.world.entity.player.Player player) {
        // The real counter (mods award stats), on a file that isn't there: Horizon keeps the
        // player's statistics, this one is never read or saved.
        return STATS.computeIfAbsent(player.getUUID(), u -> new net.minecraft.stats.ServerStatsCounter(server,
                java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "horizon-mirror", u + ".json")));
    }

    /** A player's advancements: none kept yet (Horizon keeps its own). */
    public static net.minecraft.server.PlayerAdvancements advancements(ServerPlayer player) {
        return ADVANCEMENTS.computeIfAbsent(player.getUUID(), u -> {
            var advancements = allocate(net.minecraft.server.PlayerAdvancements.class);
            // No trigger listens (Horizon awards the real advancements): triggers find none.
            ((dev.horizonlab.platform.mixin.PlayerAdvancementsAccess) advancements).horizon$setActiveTriggers(new java.util.HashMap<>());
            return advancements;
        });
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
            // Vanilla's defaults; Horizon's own rules are mirrored over them later.
            ((MinecraftServerAccess) server).horizon$setGameRules(MinecraftServer.DEFAULT_GAME_RULES.get());
            // Teams and objectives: an empty one (Horizon's own come later); display names read it.
            ((MinecraftServerAccess) server).horizon$setScoreboard(new net.minecraft.server.ServerScoreboard(server));
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
            ((ServerLevelAccess) level).horizon$setSleepStatus(new net.minecraft.server.players.SleepStatus());
            // Its players' list from the start: vanilla's code (setGameMode's sleeping check)
            // may read it before this tick's lists are made.
            List<ServerPlayer> players = new ArrayList<>();
            LEVEL_PLAYERS.put(level, players);
            ((ServerLevelAccess) level).horizon$setPlayers(players);
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

    /** A packet sender that sends nothing (Fabric networking to a client with no mod channels). */
    public static PacketSender noPackets() {
        return NO_PACKETS;
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
     * @param world Horizon's world folder
     * @param motion five values per player: x, y, z, yaw, pitch
     * @param permissions per player: vanilla's permission level, 0 to 4
     * @param kinds per event: 0 joined, 1 left, 2 chat, 3 broke a block, 4 placed a block
     * @param who per event: the player's UUID
     * @param texts per event: the chat line, or the block state ("minecraft:oak_log[axis=y]")
     * @param positions three per event: the block's x, y, z
     * @param inventories 41 per player, vanilla's Inventory slots as SNBT ("" empty), all null
     *     for a player whose inventory didn't change
     */
    public static void tick(long tick, boolean dedicated, String world, String[] ids, String[] names, String[] dimensions,
                            double[] motion, int[] modes, float[] health, int[] permissions,
                            int[] kinds, String[] who, String[] texts, int[] positions,
                            String[] inventories) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        serverThread = Thread.currentThread();
        try {
            if (server == null) setUp(dedicated);
        } catch (Throwable t) {
            Natives.log("Horizon's server mirror could not be set up: " + t);
            for (StackTraceElement frame : t.getStackTrace()) Natives.log("    at " + frame);
            return;
        }
        MinecraftServer server = HorizonServer.server;
        if (world != null && !world.isEmpty()) worldDir = java.nio.file.Paths.get(world).toAbsolutePath();
        // The server's tick count follows Horizon's (mods time their tasks with it).
        ((MinecraftServerAccess) server).horizon$setTickCount((int) tick);
        if (!started) {
            started = true;
            fire("ServerLifecycleEvents.SERVER_STARTING", () -> ServerLifecycleEvents.SERVER_STARTING.invoker().onServerStarting(server));
            fire("ServerLifecycleEvents.SERVER_STARTED", () -> ServerLifecycleEvents.SERVER_STARTED.invoker().onServerStarted(server));
        }
        fire("ServerTickEvents.START_SERVER_TICK", () -> ServerTickEvents.START_SERVER_TICK.invoker().onStartTick(server));
        for (ServerLevel level : new ArrayList<>(LEVELS.values())) {
            fire("ServerTickEvents.START_LEVEL_TICK", () -> ServerTickEvents.START_LEVEL_TICK.invoker().onStartTick(level));
        }

        closeForwarded();
        // Horizon made last tick's writes: its world answers again.
        synchronized (WRITTEN) {
            WRITTEN.clear();
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
                // A player's head and body face where Horizon's player looks.
                player.setYHeadRot((float) motion[i * 5 + 3]);
                player.setYBodyRot((float) motion[i * 5 + 3]);
                player.setHealth(health[i]);
                MODES.put(uuid, modes[i]);
                // Horizon's game mode, so mods see it (ServerPlayer.setGameMode: abilities
                // follow, and a mod that grants flight hears of the change).
                if (player.gameMode().getId() != modes[i]) {
                    // What vanilla tells clients of the change, Horizon already did.
                    syncing = true;
                    try {
                        player.setGameMode(net.minecraft.world.level.GameType.byId(modes[i]));
                    } finally {
                        syncing = false;
                    }
                }
                PERMISSIONS.put(uuid, permissions[i]);
                mirrorInventory(player, inventories, i);
                now.put(uuid, player);
            } catch (Throwable t) {
                if (FAILURES.merge("mirroring players", 1, Integer::sum) <= 10) {
                    Natives.log("Mirroring player " + names[i] + " failed: " + t);
                    StackTraceElement[] frames = t.getStackTrace();
                    for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
                }
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
        // Each level's players, as its own list (vanilla's code reads level.players()).
        for (ServerLevel level : LEVELS.values()) LEVEL_PLAYERS.computeIfAbsent(level, l -> {
            List<ServerPlayer> list = new ArrayList<>();
            ((ServerLevelAccess) l).horizon$setPlayers(list);
            return list;
        }).clear();
        for (ServerPlayer player : PLAYERS) {
            if (player.level() instanceof ServerLevel level) {
                List<ServerPlayer> list = LEVEL_PLAYERS.get(level);
                if (list != null) list.add(player);
            }
        }

        for (ServerLevel level : new ArrayList<>(LEVELS.values())) {
            fire("ServerTickEvents.END_LEVEL_TICK", () -> ServerTickEvents.END_LEVEL_TICK.invoker().onEndTick(level));
        }
        fire("ServerTickEvents.END_SERVER_TICK", () -> ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server));
        runTasks();
        syncInventories();
    }

    /**
     * A mod moved a mirrored player: the mirror is there now, and Horizon is told to move the
     * real player (its own /tp, across levels too).
     */
    public static void teleported(ServerPlayer player, ServerLevel level, net.minecraft.world.entity.PositionMoveRotation to) {
        if (player.level() != level) ((EntityAccess) player).horizon$setLevel(level);
        player.setPosRaw(to.position().x, to.position().y, to.position().z);
        player.setYRot(to.yRot());
        player.setXRot(to.xRot());
        player.setYHeadRot(to.yRot());
        player.setYBodyRot(to.yRot());
        Natives.teleport(player.getStringUUID(), level.dimension().identifier().toString(),
                to.position().x, to.position().y, to.position().z, to.yRot(), to.xRot());
    }

    private static final Map<String, Integer> DROPPED = new ConcurrentHashMap<>();

    /** A packet a mod sent a mirrored player that Horizon doesn't take yet: logged once per type. */
    /** Per player: the window of a menu a mod opened that Horizon now shows instead. */
    private static final Map<UUID, Integer> FORWARDED = new ConcurrentHashMap<>();

    /** A mod's menu for {@code player} (window {@code containerId}) went to Horizon. */
    public static void forwarded(ServerPlayer player, int containerId) {
        FORWARDED.put(player.getUUID(), containerId);
    }

    public static boolean isForwarded(ServerPlayer player, int containerId) {
        Integer window = FORWARDED.get(player.getUUID());
        return window != null && containerId >= 0 && window == containerId;
    }

    /**
     * Closes the mirror's copies of the menus Horizon shows (AbstractContainerMenu.removed),
     * so the next menu a mod opens starts from the player's inventory, as vanilla's do.
     */
    private static void closeForwarded() {
        for (var entry : new ArrayList<>(FORWARDED.entrySet())) {
            ServerPlayer player = KNOWN.get(entry.getKey());
            FORWARDED.remove(entry.getKey());
            if (player == null || player.containerMenu == player.inventoryMenu) continue;
            if (player.containerMenu.containerId != entry.getValue()) continue;
            syncing = true;
            try {
                player.doCloseContainer();
            } catch (Throwable t) {
                Natives.log("Closing a mod's menu failed: " + t);
            } finally {
                syncing = false;
            }
        }
    }

    /** True while Horizon's own state is copied in: the packets that sends are Horizon's already. */
    private static boolean syncing;

    public static void dropped(Object packet) {
        if (syncing) return;
        // Horizon sends its own blocks: a mod's resync (a cancelled break) is already done.
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket) return;
        String type = packet.getClass().getSimpleName();
        if (DROPPED.merge(type, 1, Integer::sum) == 1) {
            Natives.log("A mod sent a player a " + type + "; Horizon doesn't send those yet");
        }
    }

    // ---- Commands -------------------------------------------------------------------------

    private static com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher() {
        HorizonData.Loaded data = HorizonData.loaded;
        return data == null ? null : data.reloadable().getCommands().getDispatcher();
    }

    /**
     * Every command the server's dispatcher has, vanilla's and the mods' (Fabric API's command
     * callback registers the mods' when the server's data loads). Horizon takes the names that
     * aren't vanilla's.
     */
    public static String[] commandNames() {
        var dispatcher = dispatcher();
        if (dispatcher == null) return new String[0];
        return dispatcher.getRoot().getChildren().stream().map(n -> n.getName()).sorted().toArray(String[]::new);
    }

    /**
     * These commands as a root of the data generator's commands report, the format Horizon's
     * command tree is in: {@code {"type": "root", "children": {...}}}. An argument of a type a
     * vanilla client doesn't know (a mod's own) is sent as a greedy string: the client then
     * still parses the line and Horizon's server checks it.
     */
    public static String commandTree(String[] names) {
        var dispatcher = dispatcher();
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("type", "root");
        com.google.gson.JsonObject children = new com.google.gson.JsonObject();
        if (dispatcher != null) {
            for (String name : names) {
                var node = dispatcher.getRoot().getChild(name);
                if (node == null) continue;
                try {
                    com.google.gson.JsonObject json = net.minecraft.commands.synchronization.ArgumentUtils.serializeNodeToJson(dispatcher, node);
                    vanillaParsers(json);
                    children.add(name, json);
                } catch (Throwable t) {
                    Natives.log("Command /" + name + " can't be sent to players: " + t);
                }
            }
        }
        root.add("children", children);
        return root.toString();
    }

    private static void vanillaParsers(com.google.gson.JsonObject node) {
        if (node.has("parser")) {
            String parser = node.get("parser").getAsString();
            if (!parser.startsWith("minecraft:") && !parser.startsWith("brigadier:")) {
                node.addProperty("parser", "brigadier:string");
                com.google.gson.JsonObject properties = new com.google.gson.JsonObject();
                properties.addProperty("type", "greedy");
                node.add("properties", properties);
            }
        }
        if (node.has("children")) {
            for (var entry : node.getAsJsonObject("children").entrySet()) {
                vanillaParsers(entry.getValue().getAsJsonObject());
            }
        }
    }

    /**
     * Runs a command line (no slash) as a player, as vanilla's server runs a player's command:
     * their command source, the dispatcher's parse and errors. What the command says comes
     * back through the player's system messages.
     *
     * @return false when the player isn't on Horizon's server (yet)
     */
    public static boolean command(String uuid, String line) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = KNOWN.get(UUID.fromString(uuid));
        HorizonData.Loaded data = HorizonData.loaded;
        if (player == null || data == null || !BY_UUID.containsKey(player.getUUID())) return false;
        try {
            if (DEBUG_COMMANDS) debugCommand(data, player, line);
            data.reloadable().getCommands().performPrefixedCommand(player.createCommandSourceStack(), line);
        } catch (Throwable t) {
            Natives.tell(uuid, "An error occurred while running /" + line + ": " + t);
            fire("command /" + line, () -> { throw new RuntimeException(t); });
        }
        syncInventories();
        return true;
    }

    /**
     * HORIZON_JAVA_DEBUG=commands: each command also runs once straight through the dispatcher
     * first, so an exception vanilla would catch and hide ("An unexpected error occurred")
     * is logged with its stack. The command then runs twice: for finding a mod's needs only.
     */
    private static final boolean DEBUG_COMMANDS = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("commands");

    private static void debugCommand(HorizonData.Loaded data, ServerPlayer player, String line) {
        try {
            var dispatcher = data.reloadable().getCommands().getDispatcher();
            dispatcher.execute(dispatcher.parse(line, player.createCommandSourceStack()));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            Natives.log("debug /" + line + ": " + e.getMessage());
        } catch (Throwable t) {
            Natives.log("debug /" + line + ": " + t);
            for (StackTraceElement frame : t.getStackTrace()) {
                if (frame.getClassName().startsWith("com.mojang.brigadier")) break;
                Natives.log("    at " + frame);
            }
        }
    }

    private static BlockState state(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (Exception e) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
    }

    /** A text component as JSON (ComponentSerialization.CODEC), or null for none. */
    public static String json(net.minecraft.network.chat.Component component) {
        if (component == null) return null;
        try {
            var ops = server.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
            return net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(ops, component)
                    .getOrThrow().toString();
        } catch (Throwable t) {
            // The plain text, if the component won't encode.
            return new com.google.gson.JsonPrimitive(component.getString()).toString();
        }
    }

    // ---- Blocks ---------------------------------------------------------------------------

    /** Block states by their names, parsed once (BlockStateParser). */
    private static final Map<String, BlockState> STATES = new ConcurrentHashMap<>();
    /** Per level: what mods set since Horizon last had the world, so they read their writes. */
    private static final Map<ServerLevel, Map<Long, BlockState>> WRITTEN = new IdentityHashMap<>();

    private static String dimensionOf(Level level) {
        return level.dimension().identifier().toString();
    }

    /** {@code minecraft:oak_log[axis=y]} as a BlockState; null if it isn't one. */
    public static BlockState parseState(String name) {
        if (name == null) return null;
        BlockState known = STATES.get(name);
        if (known != null) return known;
        try {
            BlockState state = net.minecraft.commands.arguments.blocks.BlockStateParser
                    .parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, name, false).blockState();
            if (STATES.size() < 65536) STATES.put(name, state);
            return state;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A mirrored level's block: the call's own write, else Horizon's, else air (unloaded). */
    public static BlockState blockState(Level level, BlockPos pos) {
        synchronized (WRITTEN) {
            Map<Long, BlockState> written = WRITTEN.get(level);
            if (written != null) {
                BlockState mine = written.get(pos.asLong());
                if (mine != null) return mine;
            }
        }
        BlockState state = parseState(Natives.blockState(dimensionOf(level), pos.getX(), pos.getY(), pos.getZ()));
        return state != null ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    /** A mod's Level.setBlock on a mirrored level: Horizon makes it; reads see it at once. */
    public static boolean setBlock(Level level, BlockPos pos, BlockState state) {
        if (level.isOutsideBuildHeight(pos)) return false;
        synchronized (WRITTEN) {
            WRITTEN.computeIfAbsent((ServerLevel) level, l -> new java.util.HashMap<>()).put(pos.asLong(), state);
        }
        Natives.setBlock(dimensionOf(level), pos.getX(), pos.getY(), pos.getZ(),
                net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state));
        return true;
    }

    /** A mod spawned an entity in a mirrored level: items become Horizon's; the rest, not yet. */
    public static boolean addEntity(Level level, net.minecraft.world.entity.Entity entity) {
        if (entity instanceof net.minecraft.world.entity.item.ItemEntity item && !item.getItem().isEmpty()) {
            var motion = item.getDeltaMovement();
            Natives.dropItem(dimensionOf(level), item.getX(), item.getY(), item.getZ(),
                    motion.x, motion.y, motion.z,
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString(),
                    item.getItem().getCount());
            return true;
        }
        dropped(entity);
        return false;
    }

    /** A level event of a mirrored level: Horizon plays it. 2001's data is a state, sent by name. */
    public static void levelEvent(ServerLevel level, int type, BlockPos pos, int data) {
        String state = null;
        if (type == 2001) { // LevelEvent.PARTICLES_DESTROY_BLOCK: data is Block.getId(state)
            state = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(
                    net.minecraft.world.level.block.Block.stateById(data));
        }
        Natives.levelEvent(dimensionOf(level), type, pos.getX(), pos.getY(), pos.getZ(), data, state);
    }

    /**
     * A player uses a block (right-click), called by Horizon before it does: vanilla's
     * ServerPlayerGameMode.useItemOn runs on the mirrored player up to the end of the mods'
     * injections at its head (GameModeMixin). 0: no mod took the click (Horizon uses the
     * block); 1: a mod took it; 2: a mod took it and the arm swings.
     */
    public static int useBlock(String uuid, String dimension, int x, int y, int z, int face,
                               double hitX, double hitY, double hitZ, int hand, int selected) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return 0;
        ServerLevel level = level(dimension);
        if (selected >= 0 && selected < 9) player.getInventory().setSelectedSlot(selected);
        var interaction = hand == 1 ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        var hit = new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(hitX, hitY, hitZ),
                net.minecraft.core.Direction.from3DDataValue(face), new BlockPos(x, y, z), false);
        net.minecraft.world.InteractionResult[] result = {net.minecraft.world.InteractionResult.PASS};
        fire("ServerPlayerGameMode.useItemOn", () -> result[0] =
                player.gameMode.useItemOn(player, level, player.getItemInHand(interaction), interaction, hit));
        syncInventories();
        if (!result[0].consumesAction() && !(result[0] instanceof net.minecraft.world.InteractionResult.Fail)) return 0;
        return result[0] instanceof net.minecraft.world.InteractionResult.Success success && success.shouldSwing() ? 2 : 1;
    }

    /**
     * PlayerBlockBreakEvents.BEFORE, called by Horizon before a player breaks a block: false
     * when a mod cancels the break.
     */
    public static boolean beforeBreak(String uuid, String dimension, int x, int y, int z, String stateName) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        BlockState state = parseState(stateName);
        if (player == null || state == null) return true;
        ServerLevel level = level(dimension);
        BlockPos pos = new BlockPos(x, y, z);
        boolean[] allowed = {true};
        fire("PlayerBlockBreakEvents.BEFORE", () -> allowed[0] =
                net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker()
                        .beforeBlockBreak(level, player, pos, state, null));
        if (!allowed[0]) {
            fire("PlayerBlockBreakEvents.CANCELED", () ->
                    net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.CANCELED.invoker()
                            .onBlockBreakCanceled(level, player, pos, state, null));
        }
        syncInventories();
        return allowed[0];
    }

    // ---- Inventories ----------------------------------------------------------------------

    /** Vanilla's Inventory slots Horizon mirrors: 36 + armour 36-39 + the off hand 40. */
    private static final int SLOTS = 41;
    /** Per player: each slot as Horizon last had it, to find what the mods changed. */
    private static final Map<UUID, ItemStack[]> SEEN = new ConcurrentHashMap<>();

    private static com.mojang.serialization.DynamicOps<net.minecraft.nbt.Tag> nbtOps() {
        return server.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
    }

    /** An item stack from SNBT as ItemStack.CODEC reads it; empty for "" or what won't read. */
    static ItemStack item(String snbt) {
        if (snbt == null || snbt.isEmpty()) return ItemStack.EMPTY;
        try {
            var tag = net.minecraft.nbt.TagParser.parseCompoundFully(snbt);
            return ItemStack.CODEC.parse(nbtOps(), tag).result().orElse(ItemStack.EMPTY);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** An item stack as SNBT (ItemStack.CODEC); null when empty. */
    static String snbt(ItemStack stack) {
        if (stack.isEmpty()) return null;
        return ItemStack.CODEC.encodeStart(nbtOps(), stack).result().map(Object::toString).orElse(null);
    }

    /** Horizon's inventory, when it changed, into the mirrored player. */
    private static void mirrorInventory(ServerPlayer player, String[] inventories, int i) {
        if (inventories == null || inventories.length < (i + 1) * SLOTS || inventories[i * SLOTS] == null) return;
        ItemStack[] seen = new ItemStack[SLOTS];
        var inventory = player.getInventory();
        syncing = true;
        try {
            for (int slot = 0; slot < SLOTS; slot++) {
                ItemStack stack = item(inventories[i * SLOTS + slot]);
                inventory.setItem(slot, stack);
                seen[slot] = stack.copy();
            }
        } finally {
            syncing = false;
        }
        SEEN.put(player.getUUID(), seen);
    }

    /** After mods ran: each slot a mod changed (given, taken, counted down) goes to Horizon. */
    private static void syncInventories() {
        for (ServerPlayer player : new ArrayList<>(BY_UUID.values())) {
            ItemStack[] seen = SEEN.get(player.getUUID());
            if (seen == null) continue;
            var inventory = player.getInventory();
            for (int slot = 0; slot < SLOTS; slot++) {
                ItemStack now = inventory.getItem(slot);
                if (ItemStack.matches(now, seen[slot])) continue;
                Natives.setSlot(player.getStringUUID(), slot, snbt(now));
                seen[slot] = now.copy();
            }
        }
    }

    // ---- The server's tasks ---------------------------------------------------------------

    /** What mods scheduled on the server (server.execute), run at the end of the tick. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> TASKS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** The thread Horizon calls in on: the mirrored server's thread, to mods. */
    private static volatile Thread serverThread;

    public static void schedule(Runnable task) {
        if (TASKS.size() < 65536) TASKS.add(task);
    }

    public static boolean onServerThread() {
        return Thread.currentThread() == serverThread;
    }

    /** Runs the tasks mods scheduled (those they schedule meanwhile wait for the next tick). */
    private static void runTasks() {
        for (int n = TASKS.size(); n > 0; n--) {
            Runnable task = TASKS.poll();
            if (task == null) break;
            fire("a scheduled server task", task);
        }
    }
}
