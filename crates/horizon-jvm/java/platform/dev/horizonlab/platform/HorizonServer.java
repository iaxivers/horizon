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

    /** Each mirrored player's packet context (Fabric's, which its connection would hold). */
    private static final java.util.Map<Object, net.fabricmc.fabric.api.networking.v1.context.PacketContext> CONTEXTS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * A mirrored listener's packet context (MirrorPacketContextMixin): no connection, the
     * server, its registries and the player's profile.
     */
    public static net.fabricmc.fabric.api.networking.v1.context.PacketContext packetContext(
            net.minecraft.server.network.ServerCommonPacketListenerImpl listener) {
        return CONTEXTS.computeIfAbsent(listener, l -> {
            var context = new net.fabricmc.fabric.impl.networking.context.PacketContextImpl(null);
            var server = server();
            if (server != null) {
                context.set(net.fabricmc.fabric.impl.networking.context.PacketContextImpl.SERVER_INSTANCE, server);
                context.set(net.fabricmc.fabric.impl.networking.context.PacketContextImpl.REGISTRY_ACCESS, server.registryAccess());
            }
            if (l instanceof net.minecraft.server.network.ServerGamePacketListenerImpl game && game.player != null) {
                context.set(net.fabricmc.fabric.impl.networking.context.PacketContextImpl.GAME_PROFILE, game.player.getGameProfile());
            }
            return context;
        });
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

    /**
     * The server's operators (PlayerList.getOps, which the mirror's skipped constructor never
     * made; Nature's Compass reads it when used): Horizon's players with a permission level
     * above 0, as Horizon gave them. Never saved: Horizon keeps ops.json.
     */
    public static synchronized net.minecraft.server.players.ServerOpList ops() {
        if (OPS == null) {
            OPS = new net.minecraft.server.players.ServerOpList(
                    new java.io.File(System.getProperty("java.io.tmpdir"), "horizon-mirror-ops.json"),
                    new net.minecraft.server.notifications.NotificationManager()) {
                @Override
                public void save() {
                    // Horizon's ops.json is the real one.
                }
            };
        }
        OPS.clear();
        for (ServerPlayer player : KNOWN.values()) {
            int level = PERMISSIONS.getOrDefault(player.getUUID(), 0);
            if (level <= 0) continue;
            var who = new net.minecraft.server.players.NameAndId(player.getGameProfile());
            OPS.add(new net.minecraft.server.players.ServerOpListEntry(who, permissions(who), false));
        }
        return OPS;
    }

    private static net.minecraft.server.players.ServerOpList OPS;

    /** PlayerList.isOp: whether Horizon gave the player a permission level above 0. */
    public static boolean isOp(net.minecraft.server.players.NameAndId player) {
        return player != null && PERMISSIONS.getOrDefault(player.id(), 0) > 0;
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
        // Its tick rate (Level.tickRateManager: mods' tooltips read it) and the collections
        // mods' mixins add to it.
        try {
            java.lang.reflect.Field rate = MinecraftServer.class.getDeclaredField("tickRateManager");
            rate.setAccessible(true);
            rate.set(server, new net.minecraft.server.ServerTickRateManager(server));
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's server mirror has no tick rate: " + e);
        }
        emptyCollections(server, server.getClass());
        // Its debug subscribers (entities ask whether anyone watches their debug data): none.
        try {
            java.lang.reflect.Field debug = MinecraftServer.class.getDeclaredField("debugSubscribers");
            debug.setAccessible(true);
            debug.set(server, new net.minecraft.util.debug.ServerDebugSubscribers(server));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Horizon's server mirror has no debug subscribers: " + e);
        }
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

    /** The mirrored server, made now if no tick made it yet (a single player's, until one does). */
    static synchronized void ensureServer() {
        if (server != null) return;
        try {
            setUp(false);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        HorizonHooks.scan();
    }

    /**
     * Fills the null collection fields of {@code object} (made without its constructor), up its
     * classes from {@code from}: what the constructor (and mods' mixins into it) would have set.
     */
    static void emptyCollections(Object object, Class<?> from) {
        for (Class<?> c = from; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                Object empty;
                if (type == Map.class) empty = new java.util.concurrent.ConcurrentHashMap<>();
                else if (type == java.util.Set.class) empty = java.util.concurrent.ConcurrentHashMap.newKeySet();
                else if (type == List.class || type == java.util.Collection.class) empty = new java.util.concurrent.CopyOnWriteArrayList<>();
                else if (type == java.util.Queue.class || type == java.util.Deque.class) empty = new java.util.concurrent.ConcurrentLinkedDeque<>();
                else continue;
                try {
                    field.setAccessible(true);
                    if (field.get(object) == null) field.set(object, empty);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // A field it can't reach stays as it is.
                }
            }
        }
    }

    static ServerLevel level(String dimension) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
        return LEVELS.computeIfAbsent(key, k -> {
            ServerLevel level = allocate(ServerLevel.class);
            LevelAccess access = (LevelAccess) level;
            access.horizon$setDimension(k);
            access.horizon$setClientSide(false);
            access.horizon$setRandom(net.minecraft.util.RandomSource.create());
            ((ServerLevelAccess) level).horizon$setServer(server);
            ((ServerLevelAccess) level).horizon$setSleepStatus(new net.minecraft.server.players.SleepStatus());
            // What the skipped constructor makes that mods' code reaches (a placement's
            // entity check, sounds, block events).
            ServerLevelAccess sl = (ServerLevelAccess) level;
            sl.horizon$setDragonParts(new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>());
            sl.horizon$setNavigatingMobs(new java.util.HashSet<>());
            sl.horizon$setBlockEvents(new it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<>());
            sl.horizon$setBlockEventsToReschedule(new ArrayList<>());
            sl.horizon$setCustomSpawners(new ArrayList<>());
            sl.horizon$setBlockTicks(new HorizonTicks<>());
            sl.horizon$setFluidTicks(new HorizonTicks<>());
            // A chunk source holding no chunks (ServerChunkCacheMixin): what entities send their
            // trackers goes nowhere, their paths read the level.
            var chunks = allocate(net.minecraft.server.level.ServerChunkCache.class);
            try {
                java.lang.reflect.Field owner = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("level");
                owner.setAccessible(true);
                owner.set(chunks, level);
            } catch (ReflectiveOperationException e) {
                Natives.log("Java mods: a level's chunk source has no level: " + e);
            }
            sl.horizon$setChunkSource(chunks);
            // Its waypoint manager (living entities leave it when removed; Horizon has the
            // locator bar's waypoints).
            try {
                java.lang.reflect.Field waypoints = ServerLevel.class.getDeclaredField("waypointManager");
                waypoints.setAccessible(true);
                waypoints.set(level, new net.minecraft.server.waypoints.ServerWaypointManager());
            } catch (ReflectiveOperationException e) {
                Natives.log("Java mods: a level has no waypoint manager: " + e);
            }
            access.horizon$setSoundSeedGenerator(net.minecraft.util.RandomSource.create());
            access.horizon$setBlockEntityTickers(new ArrayList<>());
            access.horizon$setPendingBlockEntityTickers(new ArrayList<>());
            // Its players' list from the start: vanilla's code (setGameMode's sleeping check)
            // may read it before this tick's lists are made.
            List<ServerPlayer> players = new ArrayList<>();
            LEVEL_PLAYERS.put(level, players);
            // Collections other mods' mixins add to the level and fill in its constructor
            // (Fabric's Transfer API's lookup caches): empty ones of their own.
            emptyCollections(level, ServerLevel.class);
            ((ServerLevelAccess) level).horizon$setPlayers(players);
            HorizonData.Loaded data = HorizonData.loaded;
            if (data != null) {
                net.minecraft.core.RegistryAccess.Frozen registries = data.registries().compositeAccess();
                access.horizon$setRegistryAccess(registries);
                access.horizon$setDamageSources(new net.minecraft.world.damagesource.DamageSources(registries));
                access.horizon$setLevelData(data.worldData());
                // ServerLevel's own copy (its name, its spawn, what mods read of the world).
                try {
                    java.lang.reflect.Field own = ServerLevel.class.getDeclaredField("serverLevelData");
                    own.setAccessible(true);
                    own.set(level, data.worldData());
                } catch (ReflectiveOperationException e) {
                    Natives.log("Java mods: a level's data could not be set: " + e);
                }
                // The dimension's type: the level stem's, as vanilla's server binds it.
                var stem = registries.lookupOrThrow(Registries.LEVEL_STEM)
                        .get(ResourceKey.create(Registries.LEVEL_STEM, k.identifier()));
                stem.ifPresent(s -> access.horizon$setDimensionType(s.value().type()));
                // Its environment attributes (what mobs read of the sky and the moon:
                // EnvironmentAttributeSystem.addDefaultLayers): the dimension's own values. The
                // mirror has no world clocks for the time-based layers, nor biomes for theirs.
                stem.ifPresent(s -> {
                    try {
                        var builder = net.minecraft.world.attribute.EnvironmentAttributeSystem.builder();
                        java.lang.reflect.Method layer = net.minecraft.world.attribute.EnvironmentAttributeSystem.class
                                .getDeclaredMethod("addDimensionLayer", builder.getClass(),
                                        net.minecraft.world.level.dimension.DimensionType.class);
                        layer.setAccessible(true);
                        layer.invoke(null, builder, s.value().type().value());
                        level.setEnvironmentAttributes(builder.build());
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        Natives.log("Java mods: a level has no environment attributes: " + e);
                    }
                });
            }
            return level;
        });
    }

    /** Horizon's world game time, which the mirrored levels read (Level.getGameTime). */
    public static void gameTime(long time, long seed) {
        if (server == null) return;
        server.getWorldData().overworldData().setGameTime(time);
        HorizonServer.seed = seed;
    }

    /** Horizon's world seed (ServerLevel.getSeed on a mirrored level), from its first tick. */
    private static volatile long seed;

    public static long seed() {
        return seed;
    }

    /**
     * A mirrored level's chunk generator (ServerChunkCache.getGenerator): its level stem's,
     * from the data packs. Horizon generates the terrain; mods ask the generator about it
     * (Nature's Compass searches its biome source), and Horizon's generation is vanilla's.
     */
    public static net.minecraft.world.level.chunk.ChunkGenerator generator(ServerLevel level) {
        HorizonData.Loaded data = HorizonData.loaded;
        if (data == null) return null;
        return data.registries().compositeAccess().lookupOrThrow(Registries.LEVEL_STEM)
                .get(ResourceKey.create(Registries.LEVEL_STEM, level.dimension().identifier()))
                .map(stem -> stem.value().generator()).orElse(null);
    }

    private static final Map<ServerLevel, net.minecraft.world.level.levelgen.RandomState> RANDOM_STATES = new IdentityHashMap<>();
    private static final Map<ServerLevel, net.minecraft.world.level.chunk.ChunkGeneratorStructureState> STRUCTURE_STATES = new IdentityHashMap<>();
    private static long statesSeed;

    /** ServerChunkCache.randomState: the generator's noises with Horizon's seed (ChunkMap's constructor). */
    public static synchronized net.minecraft.world.level.levelgen.RandomState randomState(ServerLevel level) {
        if (statesSeed != seed) {
            RANDOM_STATES.clear();
            STRUCTURE_STATES.clear();
            statesSeed = seed;
        }
        return RANDOM_STATES.computeIfAbsent(level, l -> {
            var registries = HorizonData.loaded.registries().compositeAccess();
            var noises = registries.lookupOrThrow(Registries.NOISE);
            var settings = generator(l) instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise
                    ? noise.generatorSettings().value()
                    : registries.lookupOrThrow(Registries.NOISE_SETTINGS)
                            .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD).value();
            return net.minecraft.world.level.levelgen.RandomState.create(noises, seed, settings);
        });
    }

    /** ServerChunkCache.getGeneratorState: where structures may be (a locator's search). */
    public static synchronized net.minecraft.world.level.chunk.ChunkGeneratorStructureState generatorState(ServerLevel level) {
        var random = randomState(level);
        return STRUCTURE_STATES.computeIfAbsent(level, l -> generator(l).createState(
                HorizonData.loaded.registries().compositeAccess().lookupOrThrow(Registries.STRUCTURE_SET), random, seed));
    }

    /** The mirrored levels (one a dimension Horizon has). */
    public static java.util.List<ServerLevel> levels() {
        return new ArrayList<>(LEVELS.values());
    }

    /** A mirrored player by id; null when Horizon doesn't list them. */
    public static ServerPlayer player(UUID uuid) {
        return BY_UUID.get(uuid);
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
            // Its network connection, which mods read of a player (Forge Config API Port's
            // isMemoryConnection each tick): the mirror's (Horizon has the real one).
            ((ServerCommonListenerAccess) connection).horizon$setConnection(mirrorConnection(player));
            player.connection = connection;
            KNOWN.put(uuid, player);
        }
        return player;
    }

    // ---- Damage --------------------------------------------------------------------------

    /**
     * The head of a player's hurtServer, called by Horizon before it hurts the player:
     * Fabric's ALLOW_DAMAGE, then the mods' injections at the heads of ServerPlayer, Player and
     * LivingEntity.hurtServer (vanilla's calls, in that order). The amount, or -1 when a mod
     * stops the hit.
     */
    public static float hurt(String uuid, String damageType, float amount) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return amount;
        ServerLevel level = (ServerLevel) player.level();
        net.minecraft.world.damagesource.DamageSource source = damageSource(level, damageType);
        boolean[] allowed = {true};
        fire("ServerLivingEntityEvents.ALLOW_DAMAGE", () -> allowed[0] =
                net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.invoker()
                        .allowDamage(player, source, amount));
        if (!allowed[0]) return -1;
        Object[] args = {level, source, amount};
        String desc = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z";
        for (Class<?> owner : new Class<?>[] {ServerPlayer.class, net.minecraft.world.entity.player.Player.class,
                net.minecraft.world.entity.LivingEntity.class}) {
            if (!HorizonHooks.hooked(owner.getName().replace('.', '/'), "hurtServer")) continue;
            HorizonHooks.Result r = HorizonHooks.fire(player, owner, "hurtServer", desc, "HEAD", args, true, false);
            if (r.cancelled() && Boolean.FALSE.equals(r.returnValue())) return -1;
            if (r.cancelled()) return amount;
        }
        syncInventories();
        return amount;
    }

    /** A damage type by id as a DamageSource (generic if it isn't one). */
    private static net.minecraft.world.damagesource.DamageSource damageSource(ServerLevel level, String type) {
        try {
            var key = net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(type));
            return new net.minecraft.world.damagesource.DamageSource(
                    level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key));
        } catch (Throwable t) {
            return level.damageSources().generic();
        }
    }

    // ---- Death and respawn ----------------------------------------------------------------

    /**
     * Horizon's player died of {@code cause} (a damage type id): vanilla's LivingEntity.die as
     * mods hook it (ServerPlayer.die and LivingEntity.die's head, dropAllDeathLoot's head and end,
     * Fabric's AFTER_DEATH, then the ends of die). Horizon has dropped the items already: the
     * mirrored inventory is what is left (so a mod can't keep a second copy of them).
     */
    private static void died(ServerPlayer player, String cause) {
        ServerLevel level = (ServerLevel) player.level();
        net.minecraft.world.damagesource.DamageSource source;
        try {
            var key = net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(cause));
            source = new net.minecraft.world.damagesource.DamageSource(
                    level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key));
        } catch (Throwable t) {
            source = level.damageSources().generic();
        }
        net.minecraft.world.damagesource.DamageSource from = source;
        Object[] dying = {from};
        String die = "(Lnet/minecraft/world/damagesource/DamageSource;)V";
        hook(player, ServerPlayer.class, "die", die, "HEAD", dying);
        hook(player, net.minecraft.world.entity.LivingEntity.class, "die", die, "HEAD", dying);
        Object[] loot = {level, from};
        String dropLoot = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V";
        hook(player, net.minecraft.world.entity.LivingEntity.class, "dropAllDeathLoot", dropLoot, "HEAD", loot);
        hook(player, net.minecraft.world.entity.LivingEntity.class, "dropAllDeathLoot", dropLoot, "RETURN", loot);
        hook(player, net.minecraft.world.entity.LivingEntity.class, "dropAllDeathLoot", dropLoot, "TAIL", loot);
        fire("ServerLivingEntityEvents.AFTER_DEATH", () ->
                net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.invoker().afterDeath(player, from));
        hook(player, net.minecraft.world.entity.LivingEntity.class, "die", die, "RETURN", dying);
        hook(player, net.minecraft.world.entity.LivingEntity.class, "die", die, "TAIL", dying);
        hook(player, ServerPlayer.class, "die", die, "RETURN", dying);
        hook(player, ServerPlayer.class, "die", die, "TAIL", dying);
    }

    /**
     * Horizon's dead player respawned: vanilla makes a new ServerPlayer and copies the old one in
     * (ServerPlayer.restoreFrom); the mirror keeps one, so the copy is from itself (mods' copies
     * of their own data keep it), then Fabric's COPY_FROM and AFTER_RESPAWN.
     */
    private static void respawned(ServerPlayer player) {
        Object[] restoring = {player, false};
        String restoreFrom = "(Lnet/minecraft/server/level/ServerPlayer;Z)V";
        hook(player, ServerPlayer.class, "restoreFrom", restoreFrom, "HEAD", restoring);
        hook(player, ServerPlayer.class, "restoreFrom", restoreFrom, "RETURN", restoring);
        hook(player, ServerPlayer.class, "restoreFrom", restoreFrom, "TAIL", restoring);
        fire("ServerPlayerEvents.COPY_FROM", () ->
                net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.COPY_FROM.invoker().copyFromPlayer(player, player, false));
        fire("ServerPlayerEvents.AFTER_RESPAWN", () ->
                net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(player, player, false));
    }

    // ---- The mods' event-like Mixins (HorizonHooks) ---------------------------------------

    private static final String PLACE_NEW_PLAYER =
            "(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V";
    private static final String REMOVE = "(Lnet/minecraft/server/level/ServerPlayer;)V";
    private static final String TICK_SERVER = "(Ljava/util/function/BooleanSupplier;)V";
    private static final Object[] NO_ARGUMENTS = {};
    private static final Map<UUID, net.minecraft.network.Connection> CONNECTIONS = new ConcurrentHashMap<>();

    /** Fires the mods' injections (not Fabric API's: Horizon fires its events) at a point. */
    private static HorizonHooks.Result hook(Object self, Class<?> owner, String method, String desc, String at, Object[] args) {
        if (!HorizonHooks.hooked(owner.getName().replace('.', '/'), method)) return HorizonHooks.Result.NONE;
        return HorizonHooks.fire(self, owner, method, desc, at, args, null, true);
    }

    /** A player's network connection, as mods are handed it (a mirror: Horizon has the real one). */
    private static net.minecraft.network.Connection mirrorConnection(ServerPlayer player) {
        return CONNECTIONS.computeIfAbsent(player.getUUID(), u -> allocate(net.minecraft.network.Connection.class));
    }

    /**
     * The players who would see a point of a mirrored level (Fabric's PlayerLookup.tracking):
     * the level's players within the server's view distance of it, but {@code self} (a player
     * doesn't track itself).
     */
    public static java.util.Collection<ServerPlayer> tracking(ServerLevel level, double x, double z, Object self) {
        List<ServerPlayer> players = LEVEL_PLAYERS.get(level);
        if (players == null || players.isEmpty()) return List.of();
        int view = server.getPlayerList().getViewDistance();
        double reach = (view > 0 ? view : 12) * 16.0 + 8;
        List<ServerPlayer> seen = new ArrayList<>();
        for (ServerPlayer player : new ArrayList<>(players)) {
            if (player == self) continue;
            if (Math.abs(player.getX() - x) <= reach && Math.abs(player.getZ() - z) <= reach) seen.add(player);
        }
        return seen;
    }

    /** ServerChunkCache.blockChanged on a mirrored level: its block entity is saved with the others. */
    public static void blockChanged(Level level, net.minecraft.core.BlockPos pos) {
        if (level != null) HorizonBlockEntities.changed(level, pos);
    }

    /** The mirrored server's services (HorizonServices). */
    public static net.minecraft.server.Services services() {
        return HorizonServices.get();
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

    /**
     * Runs each listener of a Fabric event on its own (in the event's phase order), so a mod
     * that throws does not stop the others. Vanilla's server would crash on that throw; the
     * mirror goes on, and through the invoker one mod failing each tick (Carry On, Forge Config
     * API Port in a 40-mod pack) silently cut off every listener registered after it. Only for
     * events with no result: those with one keep their invoker (it combines the results).
     */
    @SuppressWarnings("unchecked")
    static <T> void fireEach(String event, net.fabricmc.fabric.api.event.Event<T> source, java.util.function.Consumer<T> call) {
        Object[] handlers = listeners(source);
        if (handlers == null) {
            fire(event, () -> call.accept(source.invoker()));
            return;
        }
        for (Object handler : handlers) {
            String name = handler.getClass().getName();
            int lambda = name.indexOf("$$");
            fire(event + " [" + (lambda > 0 ? name.substring(0, lambda) : name) + "]", () -> call.accept((T) handler));
        }
    }

    private static java.lang.reflect.Field HANDLERS;

    /** An array-backed Fabric event's listeners, or null if it is some other kind. */
    static Object[] listeners(net.fabricmc.fabric.api.event.Event<?> source) {
        try {
            if (HANDLERS == null) {
                Class<?> backed = Class.forName("net.fabricmc.fabric.impl.base.event.ArrayBackedEvent", false, source.getClass().getClassLoader());
                java.lang.reflect.Field field = backed.getDeclaredField("handlers");
                field.setAccessible(true);
                HANDLERS = field;
            }
            if (!HANDLERS.getDeclaringClass().isInstance(source)) return null;
            return (Object[]) HANDLERS.get(source);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Runs a mod event, logging (not throwing) what a mod throws, ten times per event at most. */
    private static void fire(String event, Runnable run) {
        try {
            run.run();
        } catch (Throwable t) {
            int count = FAILURES.merge(event, 1, Integer::sum);
            if (count <= 3) {
                // The innermost cause: mods wrap their failures.
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                Natives.log("A mod failed in " + event + ": " + root);
                StackTraceElement[] frames = root.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 12); f++) {
                    if (frames[f].getClassName().startsWith("dev.horizonlab.platform")) break;
                    Natives.log("    at " + frames[f]);
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
     * @param kinds per event: 0 joined, 1 left, 2 chat, 3 broke a block, 4 placed a block, 5 died
     *     (texts: the damage type), 6 respawned
     * @param who per event: the player's UUID
     * @param texts per event: the chat line, or the block state ("minecraft:oak_log[axis=y]")
     * @param positions three per event: the block's x, y, z
     * @param inventories 41 per player, vanilla's Inventory slots as SNBT ("" empty), all null
     *     for a player whose inventory didn't change
     */
    public static void tick(long tick, boolean dedicated, String world, String[] ids, String[] names, String[] dimensions,
                            double[] motion, int[] modes, float[] health, float[] food, int[] permissions,
                            int[] kinds, String[] who, String[] texts, int[] positions,
                            String[] inventories) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        serverThread = Thread.currentThread();
        try {
            if (server == null) {
                setUp(dedicated);
                HorizonHooks.scan();
            }
        } catch (Throwable t) {
            Natives.log("Horizon's server mirror could not be set up: " + t);
            for (StackTraceElement frame : t.getStackTrace()) Natives.log("    at " + frame);
            return;
        }
        MinecraftServer server = HorizonServer.server;
        if (world != null && !world.isEmpty()) worldDir = java.nio.file.Paths.get(world).toAbsolutePath();
        HorizonBlockEntities.world(worldDir);
        HorizonSavedData.world(server, worldDir);
        HorizonEntities.world(worldDir);
        // The server's tick count follows Horizon's (mods time their tasks with it).
        ((MinecraftServerAccess) server).horizon$setTickCount((int) tick);
        // The headless client's tick (its frame, then its game tick), before the server's.
        if (client) HorizonClient.clientTick();
        if (!started) {
            started = true;
            fireEach("ServerLifecycleEvents.SERVER_STARTING", ServerLifecycleEvents.SERVER_STARTING, l -> l.onServerStarting(server));
            // MinecraftServer.loadLevel (runServer's initServer): the levels exist at its end.
            hook(server, MinecraftServer.class, "loadLevel", "()V", "HEAD", NO_ARGUMENTS);
            for (String dimension : new String[] {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
                level(dimension);
            }
            hook(server, MinecraftServer.class, "loadLevel", "()V", "RETURN", NO_ARGUMENTS);
            hook(server, MinecraftServer.class, "loadLevel", "()V", "TAIL", NO_ARGUMENTS);
            fireEach("ServerLifecycleEvents.SERVER_STARTED", ServerLifecycleEvents.SERVER_STARTED, l -> l.onServerStarted(server));
        }
        // MinecraftServer.tickServer's head (Fabric's START_SERVER_TICK is its own injection there).
        Object[] haveTime = {(java.util.function.BooleanSupplier) () -> true};
        hook(server, MinecraftServer.class, "tickServer", TICK_SERVER, "HEAD", haveTime);
        fireEach("ServerTickEvents.START_SERVER_TICK", ServerTickEvents.START_SERVER_TICK, l -> l.onStartTick(server));
        for (ServerLevel level : new ArrayList<>(LEVELS.values())) {
            fireEach("ServerTickEvents.START_LEVEL_TICK", ServerTickEvents.START_LEVEL_TICK, l -> l.onStartTick(level));
            hook(level, ServerLevel.class, "tick", TICK_SERVER, "HEAD", haveTime);
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
                HorizonServices.known(uuid, names[i]);
                if (player.level() != level) ((EntityAccess) player).horizon$setLevel(level);
                player.setPosRaw(motion[i * 5], motion[i * 5 + 1], motion[i * 5 + 2]);
                // Its box follows (setPosRaw leaves it): mods find players by it (a cabinet's viewers).
                player.setBoundingBox(player.getDimensions(player.getPose()).makeBoundingBox(player.position()));
                player.setYRot((float) motion[i * 5 + 3]);
                player.setXRot((float) motion[i * 5 + 4]);
                // A player's head and body face where Horizon's player looks.
                player.setYHeadRot((float) motion[i * 5 + 3]);
                player.setYBodyRot((float) motion[i * 5 + 3]);
                player.setHealth(health[i]);
                if (food != null && food.length >= (i + 1) * 2) {
                    var data = player.getFoodData();
                    data.setFoodLevel((int) food[i * 2]);
                    data.setSaturation(food[i * 2 + 1]);
                    FOOD_SEEN.put(uuid, new float[] {food[i * 2], food[i * 2 + 1]});
                }
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
                    // PlayerList.placeNewPlayer: the mods' injections at its head, Fabric's
                    // events, then those at its end (Fabric's own injections are its events).
                    Object[] joining = {mirrorConnection(player), player,
                            net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)};
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "placeNewPlayer", PLACE_NEW_PLAYER, "HEAD", joining);
                    fireEach("ServerPlayConnectionEvents.INIT", ServerPlayConnectionEvents.INIT, l -> l.onPlayInit(connection, server));
                    fireEach("ServerEntityEvents.ENTITY_LOAD", ServerEntityEvents.ENTITY_LOAD, l -> l.onLoad(player, level));
                    fireEach("ServerPlayConnectionEvents.JOIN", ServerPlayConnectionEvents.JOIN, l -> l.onPlayReady(connection, NO_PACKETS, server));
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "placeNewPlayer", PLACE_NEW_PLAYER, "RETURN", joining);
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "placeNewPlayer", PLACE_NEW_PLAYER, "TAIL", joining);
                }
                case 1 -> {
                    ServerGamePacketListenerImpl connection = player.connection;
                    ServerLevel level = (ServerLevel) player.level();
                    Object[] leaving = {player};
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "remove", REMOVE, "HEAD", leaving);
                    fireEach("ServerPlayConnectionEvents.DISCONNECT", ServerPlayConnectionEvents.DISCONNECT, l -> l.onPlayDisconnect(connection, server));
                    fireEach("ServerEntityEvents.ENTITY_UNLOAD", ServerEntityEvents.ENTITY_UNLOAD, l -> l.onUnload(player, level));
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "remove", REMOVE, "RETURN", leaving);
                    hook(server.getPlayerList(), net.minecraft.server.players.PlayerList.class, "remove", REMOVE, "TAIL", leaving);
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
                case 5 -> died(player, texts[e]);
                case 6 -> respawned(player);
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
            hook(level, ServerLevel.class, "tick", TICK_SERVER, "RETURN", haveTime);
            hook(level, ServerLevel.class, "tick", TICK_SERVER, "TAIL", haveTime);
            fireEach("ServerTickEvents.END_LEVEL_TICK", ServerTickEvents.END_LEVEL_TICK, l -> l.onEndTick(level));
        }
        // Each player's tick (ServerPlayer.tick, Player.tick from doTick), as vanilla's
        // levels and connections run them within the server's tick.
        for (ServerPlayer player : new ArrayList<>(PLAYERS)) {
            hook(player, ServerPlayer.class, "tick", "()V", "HEAD", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.player.Player.class, "tick", "()V", "HEAD", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.player.Player.class, "tick", "()V", "RETURN", NO_ARGUMENTS);
            hook(player, net.minecraft.world.entity.player.Player.class, "tick", "()V", "TAIL", NO_ARGUMENTS);
            hook(player, ServerPlayer.class, "tick", "()V", "RETURN", NO_ARGUMENTS);
            hook(player, ServerPlayer.class, "tick", "()V", "TAIL", NO_ARGUMENTS);
        }
        // Mod blocks' scheduled ticks and block entities, as their levels' ticks run them.
        for (ServerLevel level : new ArrayList<>(LEVELS.values())) {
            if (level.getBlockTicks() instanceof HorizonTicks<?> ticks) ticks.run(level);
        }
        HorizonBlockEntities.tick();
        HorizonSavedData.tick();
        // Mods' entities, as ServerLevel.tick's entity loop runs them.
        HorizonEntities.tick();
        // ServerPlayer.tick and doTick: a menu no longer valid closes, and the open menu sends
        // its changes (a mod's menu keeps its block working while it is watched, and syncs its
        // own data there: Tom's Storage's terminal its item list).
        for (ServerPlayer player : new ArrayList<>(PLAYERS)) {
            var menu = player.containerMenu;
            if (menu == null || menu == player.inventoryMenu || !SHOWN.containsKey(player.getUUID())) continue;
            fire("AbstractContainerMenu.stillValid", () -> {
                if (!menu.stillValid(player)) player.closeContainer();
            });
            if (player.containerMenu == menu) fire("AbstractContainerMenu.broadcastChanges", menu::broadcastChanges);
        }
        // Mods' own screens: each changed frame to its player.
        if (client) HorizonClient.sendFrames();
        fireEach("ServerTickEvents.END_SERVER_TICK", ServerTickEvents.END_SERVER_TICK, l -> l.onEndTick(server));
        hook(server, MinecraftServer.class, "tickServer", TICK_SERVER, "RETURN", haveTime);
        hook(server, MinecraftServer.class, "tickServer", TICK_SERVER, "TAIL", haveTime);
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

    // ---- Mods' menus Horizon shows ---------------------------------------------------------

    /** Menus Horizon runs itself when a mod opens one (the rest are shown, run here). */
    public static boolean horizonMenu(String type, String title) {
        return switch (type) {
            case "minecraft:crafting", "minecraft:stonecutter", "minecraft:grindstone", "minecraft:loom",
                 "minecraft:cartography_table", "minecraft:smithing" -> true;
            case "minecraft:generic_9x3" -> title != null && title.contains("container.enderchest");
            default -> false;
        };
    }

    /** Menu types a guest's game draws, with their container slots. */
    private static final Map<String, Integer> DRAWN = Map.of(
            "minecraft:generic_9x1", 9, "minecraft:generic_9x2", 18, "minecraft:generic_9x3", 27,
            "minecraft:generic_9x4", 36, "minecraft:generic_9x5", 45, "minecraft:generic_9x6", 54,
            "minecraft:generic_3x3", 9, "minecraft:hopper", 5, "minecraft:shulker_box", 27);

    /**
     * A mod's menu a player has open, as Horizon shows it: the menu type the player's game
     * draws, and for each of its slots the menu's slot (-1: none), the container's first, then
     * the player's 36 as vanilla's menus have them (inventory rows, then the hotbar).
     */
    private record Shown(int containerId, String type, String title, int[] slots) {}

    private record Opening(int containerId, String type, String title, net.minecraft.network.chat.Component text) {}

    private static final Map<UUID, Opening> OPENING = new ConcurrentHashMap<>();
    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SHOWN_CONTENT = new ConcurrentHashMap<>();
    /** Players whose window is sent again whole, changed or not (a click the menu refused). */
    private static final Set<UUID> RESEND = ConcurrentHashMap.newKeySet();

    /** ClientboundOpenScreenPacket of a mod's menu: shown once the menu is the player's. */
    public static void menuOpened(ServerPlayer player, int containerId, String type, String title) {
        menuOpened(player, containerId, type, title, net.minecraft.network.chat.Component.empty());
    }

    public static void menuOpened(ServerPlayer player, int containerId, String type, String title,
                                  net.minecraft.network.chat.Component text) {
        OPENING.put(player.getUUID(), new Opening(containerId, type, title, text));
    }

    public static boolean isShown(ServerPlayer player, int containerId) {
        Shown shown = SHOWN.get(player.getUUID());
        Opening opening = OPENING.get(player.getUUID());
        return containerId >= 0 && ((shown != null && shown.containerId == containerId)
                || (opening != null && opening.containerId == containerId));
    }

    private static Shown layout(net.minecraft.world.inventory.AbstractContainerMenu menu, Opening opening) {
        List<Integer> own = new ArrayList<>();
        int[] player = new int[36];
        java.util.Arrays.fill(player, -1);
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                int k = slot.getContainerSlot();
                // The shown order: inventory 9-35, then the hotbar 0-8.
                if (k >= 9 && k < 36) player[k - 9] = i;
                else if (k >= 0 && k < 9) player[27 + k] = i;
            } else {
                own.add(i);
            }
        }
        String type = opening.type;
        Integer drawn = DRAWN.get(type);
        if (drawn == null || drawn != own.size()) {
            // A menu the game can't draw (a cooking pot's): its slots in a chest's rows.
            int rows = Math.max(1, Math.min(6, (own.size() + 8) / 9));
            type = "minecraft:generic_9x" + rows;
            drawn = rows * 9;
        }
        int[] slots = new int[drawn + 36];
        java.util.Arrays.fill(slots, -1);
        for (int i = 0; i < Math.min(drawn, own.size()); i++) slots[i] = own.get(i);
        System.arraycopy(player, 0, slots, drawn, 36);
        return new Shown(opening.containerId, type, opening.title, slots);
    }

    /**
     * Each mirrored player's menu, after anything that may change it: a mod's menu just opened
     * is shown, one closed (or replaced) is closed, and a shown one's slots go to Horizon when
     * they changed (a cooking pot's progress, a click).
     */
    private static void syncMenus() {
        for (ServerPlayer player : new ArrayList<>(BY_UUID.values())) {
            UUID id = player.getUUID();
            var menu = player.containerMenu;
            Opening opening = OPENING.get(id);
            if (opening != null && menu != null && menu.containerId == opening.containerId) {
                OPENING.remove(id);
                Shown shown = layout(menu, opening);
                SHOWN.put(id, shown);
                // The mod's own screen, in Horizon's headless client (its frames: sendFrames).
                if (client) HorizonClient.openScreen(player, menu, opening.text());
                SHOWN_CONTENT.remove(id);
            }
            Shown shown = SHOWN.get(id);
            if (shown == null) continue;
            if (menu == null || menu == player.inventoryMenu || menu.containerId != shown.containerId) {
                SHOWN.remove(id);
                SHOWN_CONTENT.remove(id);
                if (client) HorizonClient.closeScreen(id);
                Natives.menuContent(player.getStringUUID(), null, null, null, null);
                continue;
            }
            String[] items = new String[shown.slots.length];
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < items.length; i++) {
                int at = shown.slots[i];
                items[i] = at >= 0 && at < menu.slots.size() ? snbt(menu.slots.get(at).getItem()) : null;
                all.append(items[i]).append('\n');
            }
            String carried = snbt(menu.getCarried());
            all.append(carried);
            String now = all.toString();
            if (now.equals(SHOWN_CONTENT.get(id)) && !RESEND.remove(id)) continue;
            boolean first = !SHOWN_CONTENT.containsKey(id);
            SHOWN_CONTENT.put(id, now);
            Natives.menuContent(player.getStringUUID(), first ? shown.type : null, first ? shown.title : null,
                    items, carried);
        }
    }

    /**
     * A click in a shown menu's window (ServerboundContainerClickPacket): the menu's own
     * AbstractContainerMenu.clicked, on the slot behind the shown one.
     */
    public static void menuClick(String uuid, int slot, int button, int input) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return;
        Shown shown = SHOWN.get(player.getUUID());
        var menu = player.containerMenu;
        if (shown == null || menu == null || menu.containerId != shown.containerId) return;
        int at = slot == -999 || slot == -1 ? slot : slot >= 0 && slot < shown.slots.length ? shown.slots[slot] : -2;
        if (at == -2 || (at == -1 && slot != -1)) {
            SHOWN_CONTENT.remove(player.getUUID()); // the window has it wrong: send it again
        } else if (input >= 0 && input < net.minecraft.world.inventory.ContainerInput.values().length) {
            var type = net.minecraft.world.inventory.ContainerInput.values()[input];
            // The player's game guessed the click's result: whatever the menu made of it (a
            // slot it refuses, a cooking pot's meal), the window is sent again as it is.
            RESEND.add(player.getUUID());
            fire("AbstractContainerMenu.clicked", () -> {
                if (menu.stillValid(player)) {
                    menu.clicked(at, button, type, player);
                    menu.broadcastChanges();
                } else {
                    player.closeContainer();
                }
            });
        }
        syncInventories();
    }

    /** After input to a mod's screen: what its clicks changed reaches Horizon. */
    static void afterScreenInput() {
        syncInventories();
    }

    /** The player closed a shown menu's window (ServerboundContainerClosePacket). */
    public static void menuClose(String uuid) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return;
        Shown shown = SHOWN.remove(player.getUUID());
        SHOWN_CONTENT.remove(player.getUUID());
        if (client) HorizonClient.closeScreen(player.getUUID());
        if (shown != null && player.containerMenu != null && player.containerMenu.containerId == shown.containerId) {
            fire("ServerPlayer.doCloseContainer", player::doCloseContainer);
        }
        syncInventories();
    }

    /**
     * Whether this JVM runs the headless client (the game's own, not a dedicated server's): set
     * by HorizonClient as it boots. Without it no client class is touched (a server has none).
     */
    static volatile boolean client;

    /**
     * The thread running the client half of a player's action now (HorizonClient.useItem), for
     * which mirrored levels answer isClientSide() true (LevelMixin); null otherwise.
     */
    public static volatile Thread clientHalf;

    /** Whether the mods' messages loop between their two sides here (HorizonLoop). */
    public static boolean loop() {
        return client;
    }

    /** After a mod's client message ran its server code: its changes go out as a click's do. */
    static void afterLoop() {
        syncInventories();
    }

    /** True while Horizon's own state is copied in: the packets that sends are Horizon's already. */
    private static boolean syncing;

    /**
     * A mod's payload for a mirrored player: to the mod's own client code when it receives it
     * (HorizonLoop); else Fabric API's menu opening (ExtendedMenuProvider's) is shown as
     * vanilla's are; the rest is dropped.
     */
    public static void payload(ServerPlayer player,
                               net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        Class<?> c = payload.getClass();
        // Fabric API's menu opening is Horizon's to show (its own screens, below), never the
        // client's Fabric code's: that would make the client's menu from a client's level.
        boolean opening = c.getName().equals("net.fabricmc.fabric.impl.menu.Networking$OpenScreenPayload");
        // The mod's own client code takes its messages (the headless client's receivers).
        if (!opening && client && HorizonLoop.toClient(player, payload)) return;
        if (opening) {
            try {
                Object type = c.getMethod("identifier").invoke(payload);
                int containerId = (int) c.getMethod("containerId").invoke(payload);
                Object title = c.getMethod("title").invoke(payload);
                menuOpened(player, containerId, String.valueOf(type),
                        json((net.minecraft.network.chat.Component) title), (net.minecraft.network.chat.Component) title);
                return;
            } catch (ReflectiveOperationException e) {
                Natives.log("Java mods: a menu's opening could not be read: " + e);
            }
        }
        dropped(payload);
    }

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
            Natives.tell(uuid, chatJson(net.minecraft.network.chat.Component.literal("An error occurred while running /" + line + ": " + t)));
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
    /**
     * A chat line for Horizon (Natives.tell, broadcast): the component as JSON, which Horizon
     * resolves with its own language, mods' lang files included (the server side's Language
     * is vanilla's English only: a mod's translatable line came out as its key).
     */
    public static String chatJson(net.minecraft.network.chat.Component component) {
        if (component == null) return "\"\"";
        String json = json(component);
        return json != null ? json : new com.google.gson.JsonPrimitive(component.getString()).toString();
    }

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

    /**
     * A mod spawned an entity in a mirrored level: items become Horizon's, mods' own entities
     * live in the level (HorizonEntities); vanilla's others, not yet.
     */
    public static boolean addEntity(Level level, net.minecraft.world.entity.Entity entity) {
        if (HorizonEntities.modded(entity.getType())) return HorizonEntities.add((ServerLevel) level, entity);
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
     * A player uses the item in a hand on nothing (right-click in the air: a compass, a
     * scroll), called by Horizon before it does: vanilla's ServerPlayerGameMode.useItem on the
     * mirrored player, its mods' injections first (Fabric's UseItemCallback), the item's own
     * use for a mod's item (GameModeMixin: vanilla's items are Horizon's). Results as useBlock's.
     */
    public static int useItem(String uuid, String dimension, int hand, int selected, float yaw, float pitch) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        ServerPlayer player = BY_UUID.get(UUID.fromString(uuid));
        if (player == null) return 0;
        ServerLevel level = level(dimension);
        if (selected >= 0 && selected < 9) player.getInventory().setSelectedSlot(selected);
        // handleUseItem's rotation: where the client looked as it clicked.
        player.setYRot(yaw);
        player.setXRot(pitch);
        var interaction = hand == 1 ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        net.minecraft.world.InteractionResult[] result = {net.minecraft.world.InteractionResult.PASS};
        fire("ServerPlayerGameMode.useItem", () -> result[0] =
                player.gameMode.useItem(player, level, player.getItemInHand(interaction), interaction));
        syncInventories();
        // The client half (MultiPlayerGameMode.useItem): a mod's item opens its screen there
        // (Nature's Compass, guide books). It runs after the server's, so what the server sent
        // the client for it (delivered at syncInventories) is in.
        if (client && result[0] != null && modItem(player.getItemInHand(interaction))) {
            var clientResult = HorizonClient.useItem(player, interaction);
            syncInventories();
            if (!result[0].consumesAction() && clientResult != null && clientResult.consumesAction()) result[0] = clientResult;
        }
        if (!result[0].consumesAction() && !(result[0] instanceof net.minecraft.world.InteractionResult.Fail)) return 0;
        return result[0] instanceof net.minecraft.world.InteractionResult.Success success && success.shouldSwing() ? 2 : 1;
    }

    /** Whether a stack is a mod's item (vanilla's are Horizon's, both halves). */
    private static boolean modItem(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return false;
        return !"minecraft".equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
    }

    /**
     * ServerLevel.getEntities on a mirrored level (it has no entity manager): the level's
     * mirrored players and mods' entities, so a mod's checks for what stands in a block's way
     * see them, and mods' mobs find their targets.
     */
    public static net.minecraft.world.level.entity.LevelEntityGetter<net.minecraft.world.entity.Entity> entities(ServerLevel level) {
        return new net.minecraft.world.level.entity.LevelEntityGetter<>() {
            private List<net.minecraft.world.entity.Entity> all() {
                List<ServerPlayer> players = LEVEL_PLAYERS.get(level);
                List<net.minecraft.world.entity.Entity> all = new ArrayList<>();
                if (players != null) all.addAll(players);
                all.addAll(HorizonEntities.of(level));
                return all;
            }

            @Override
            public net.minecraft.world.entity.Entity get(int id) {
                for (var e : all()) if (e.getId() == id) return e;
                return null;
            }

            @Override
            public net.minecraft.world.entity.Entity get(UUID uuid) {
                for (var e : all()) if (e.getUUID().equals(uuid)) return e;
                return null;
            }

            @Override
            public Iterable<net.minecraft.world.entity.Entity> getAll() {
                return all();
            }

            @Override
            public <U extends net.minecraft.world.entity.Entity> void get(
                    net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, U> test,
                    net.minecraft.util.AbortableIterationConsumer<U> out) {
                for (var e : all()) {
                    U u = test.tryCast(e);
                    if (u != null && out.accept(u).shouldAbort()) return;
                }
            }

            @Override
            public void get(net.minecraft.world.phys.AABB box, java.util.function.Consumer<net.minecraft.world.entity.Entity> out) {
                for (var e : all()) if (e.getBoundingBox().intersects(box)) out.accept(e);
            }

            @Override
            public <U extends net.minecraft.world.entity.Entity> void get(
                    net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, U> test,
                    net.minecraft.world.phys.AABB box, net.minecraft.util.AbortableIterationConsumer<U> out) {
                for (var e : all()) {
                    if (!e.getBoundingBox().intersects(box)) continue;
                    U u = test.tryCast(e);
                    if (u != null && out.accept(u).shouldAbort()) return;
                }
            }
        };
    }

    /**
     * Random ticks Horizon's level gave mods' blocks (ServerLevel.tickChunk's): each still a
     * mod's block that ticks randomly grows as its mod says (BlockState.randomTick).
     */
    /**
     * Java mods' blocks placed or removed in Horizon's world: a block with a block entity has
     * it made now (it ticks from its placement, as vanilla's do); a removed one's goes at its
     * next tick (HorizonBlockEntities.tick).
     */
    public static void blockEntities(String dimension, int[] positions) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return;
        ServerLevel level = level(dimension);
        for (int i = 0; i + 2 < positions.length; i += 3) {
            BlockPos pos = new BlockPos(positions[i], positions[i + 1], positions[i + 2]);
            fire("BlockEntity (made)", () -> HorizonBlockEntities.ensure(level, pos));
        }
    }

    public static void randomTicks(String dimension, int[] positions) {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return;
        ServerLevel level = level(dimension);
        for (int i = 0; i + 2 < positions.length; i += 3) {
            BlockPos pos = new BlockPos(positions[i], positions[i + 1], positions[i + 2]);
            BlockState state = level.getBlockState(pos);
            if (!HorizonBlockEntities.modded(state) || !state.isRandomlyTicking()) continue;
            fire("BlockState.randomTick", () -> state.randomTick(level, pos, level.getRandom()));
        }
        syncInventories();
    }

    private static final Map<Level, HorizonLight> LIGHT = new IdentityHashMap<>();

    /** A mirrored level's light: Horizon's (HorizonLight). */
    public static synchronized HorizonLight light(Level level) {
        return LIGHT.computeIfAbsent(level, HorizonLight::new);
    }

    /** A mod's item (not vanilla's). */
    public static boolean moddedItem(ItemStack stack) {
        return !stack.isEmpty()
                && !BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft");
    }

    private static final Map<Class<?>, Boolean> USABLE = new ConcurrentHashMap<>();

    /** Whether a block does something when used (its class has useItemOn or useWithoutItem). */
    public static boolean usable(BlockState state) {
        return USABLE.computeIfAbsent(state.getBlock().getClass(), c -> {
            for (Class<?> k = c; k != null && k != net.minecraft.world.level.block.state.BlockBehaviour.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals("useItemOn") || m.getName().equals("useWithoutItem")) return true;
                }
            }
            return false;
        });
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
    /** Each player's food as Horizon last gave it, to send back what mods change. */
    private static final Map<UUID, float[]> FOOD_SEEN = new ConcurrentHashMap<>();

    // ---- Mods' entities (HorizonEntities) ------------------------------------------------

    /** Every mod entity type's id (Horizon's /summon hands these to the mods' server). */
    public static String[] entityTypes() {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        return HorizonEntities.types();
    }

    /**
     * The mods' entities of every level, with their looks when the headless client draws them
     * (HorizonEntityRender's snapshot), as JSON.
     */
    public static String entities() {
        Thread.currentThread().setContextClassLoader(HorizonServer.class.getClassLoader());
        if (server == null) return "{}";
        if (client) return HorizonEntityRender.snapshot();
        return "{}";
    }

    /** A player hit a mod's entity: true when it was one (HorizonEntities.attack). */
    public static boolean attackEntity(String uuid, int id) {
        boolean hit = HorizonEntities.attack(uuid, id);
        syncInventories();
        return hit;
    }

    /** A player used a mod's entity (HorizonEntities.interact): 0 nothing, 1 taken, 2 swing. */
    public static int interactEntity(String uuid, int id, int hand, double x, double y, double z, int selected) {
        return HorizonEntities.interact(uuid, id, hand, x, y, z, selected);
    }

    /** What a call into the mods leaves for Horizon: slots, menus, messages (syncInventories). */
    static void afterCall() {
        syncInventories();
    }

    private static void syncInventories() {
        HorizonBlockEntities.flushVanilla();
        syncMenus();
        // What mods sent their client code, now that the screens it is for are up.
        if (client) HorizonLoop.flush();
        for (ServerPlayer player : new ArrayList<>(BY_UUID.values())) {
            float[] seen = FOOD_SEEN.get(player.getUUID());
            if (seen == null) continue;
            var data = player.getFoodData();
            if (data.getFoodLevel() != (int) seen[0] || data.getSaturationLevel() != seen[1]) {
                seen[0] = data.getFoodLevel();
                seen[1] = data.getSaturationLevel();
                Natives.setFood(player.getStringUUID(), data.getFoodLevel(), data.getSaturationLevel());
            }
        }
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
