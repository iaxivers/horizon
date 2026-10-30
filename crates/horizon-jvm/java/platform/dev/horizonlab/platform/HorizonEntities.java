package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Mods' entities (a bear, a butterfly): Horizon's world has no such types, so they live here,
 * in the mirrored levels, as vanilla's levels keep theirs: added by {@code addFreshEntity} (a
 * spawn egg, {@code /summon}, a mod's own code), ticked with the server (ServerLevel.tick's
 * entity loop: {@code tickNonPassenger}), found by mods through the level's entity getter, and
 * saved beside the world ({@code horizon-java/entities/}). One whose chunk Horizon unloaded is
 * put away and comes back with it. Each tick their looks are captured by their own renderers
 * ({@link HorizonEntityRender}) for Horizon to draw.
 */
public final class HorizonEntities {
    private HorizonEntities() {}

    /** Every 10 s, the entities are saved (when one changed level or came or went). */
    private static final int SAVE_TICKS = 200;

    /** Per level: its mods' entities, by UUID, in the order they came (vanilla's tick order). */
    private static final Map<ServerLevel, Map<UUID, Entity>> LIVE = new IdentityHashMap<>();
    /** Per dimension: entities put away (their chunks unloaded), as saved. */
    private static final Map<String, List<CompoundTag>> SAVED = new HashMap<>();
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static Path dir;
    private static int ticks;

    /** A mod's entity type (Horizon simulates vanilla's itself). */
    public static boolean modded(EntityType<?> type) {
        return !BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace().equals("minecraft");
    }

    /** Every mod entity type's id, for Horizon's commands (/summon). */
    public static String[] types() {
        List<String> out = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (modded(type)) out.add(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        }
        return out.toArray(String[]::new);
    }

    /** The world changed (a new folder): what was held belongs to the last one. */
    static synchronized void world(Path worldDir) {
        Path now = worldDir == null ? null : worldDir.resolve("horizon-java").resolve("entities");
        if (now == null || now.equals(dir)) return;
        if (dir != null) save();
        for (Map<UUID, Entity> live : LIVE.values()) {
            for (Entity e : live.values()) e.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        }
        LIVE.clear();
        SAVED.clear();
        dir = now;
        // The new world's saved entities wait for their chunks (restore, each second).
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static boolean loaded(ServerLevel level, double x, double y, double z) {
        return Natives.blockState(dimension(level), (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) != null;
    }

    /**
     * ServerLevel.addFreshEntity of a mod's entity on a mirrored level: it is the level's now
     * (PersistentEntitySectionManager.addNewEntity): its callback set, Fabric's load event.
     * False when one with its UUID is already there.
     */
    public static synchronized boolean add(ServerLevel level, Entity entity) {
        Map<UUID, Entity> live = LIVE.computeIfAbsent(level, l -> new LinkedHashMap<>());
        for (Map<UUID, Entity> other : LIVE.values()) {
            if (other.containsKey(entity.getUUID())) return false;
        }
        // Vanilla's entities count their ids from 1 (Entity.ENTITY_COUNTER), as Horizon's do:
        // a mirrored level's are the bridge's own, apart from Horizon's.
        if (entity.getId() < 0x7fff0000) entity.setId(HorizonServer.nextEntityId());
        live.put(entity.getUUID(), entity);
        entity.setLevelCallback(new Callback(level, entity));
        fire(entity, "ServerEntityEvents.ENTITY_LOAD", () ->
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(entity, level));
        return true;
    }

    /** The level's mods' entities, for its entity getter (mods' searches, getEntity). */
    public static synchronized List<Entity> of(ServerLevel level) {
        Map<UUID, Entity> live = LIVE.get(level);
        return live == null ? List.of() : new ArrayList<>(live.values());
    }

    /** Every mirrored level's mods' entities. */
    static synchronized Map<ServerLevel, List<Entity>> all() {
        Map<ServerLevel, List<Entity>> out = new IdentityHashMap<>();
        for (var e : LIVE.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        return out;
    }

    /** One of the levels' mods' entities by its id (Horizon's players hit it by id). */
    static synchronized Entity byId(int id) {
        for (Map<UUID, Entity> live : LIVE.values()) {
            for (Entity e : live.values()) if (e.getId() == id) return e;
        }
        return null;
    }

    /**
     * An entity's EntityInLevelCallback, as the section manager's: removed (killed, discarded,
     * changed level), it leaves the level.
     */
    private record Callback(ServerLevel level, Entity entity) implements EntityInLevelCallback {
        @Override
        public void onMove() {}

        @Override
        public void onRemove(Entity.RemovalReason reason) {
            synchronized (HorizonEntities.class) {
                Map<UUID, Entity> live = LIVE.get(level);
                if (live != null && live.get(entity.getUUID()) == entity) live.remove(entity.getUUID());
            }
            fire(entity, "ServerEntityEvents.ENTITY_UNLOAD", () ->
                    net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.invoker().onUnload(entity, level));
            entity.setLevelCallback(EntityInLevelCallback.NULL);
        }
    }

    /**
     * Each server tick, ServerLevel.tick's entities: one whose chunk is gone is put away; the
     * rest check their despawning, then tick (tickNonPassenger: old position kept, the count
     * up, {@code tick}; riders through their vehicle).
     */
    public static void tick() {
        Map<ServerLevel, List<Entity>> all = all();
        for (var e : all.entrySet()) {
            ServerLevel level = e.getKey();
            for (Entity entity : e.getValue()) {
                if (entity.isRemoved()) continue;
                if (!loaded(level, entity.getX(), entity.getY(), entity.getZ())) {
                    putAway(level, entity);
                    continue;
                }
                if (entity.isPassenger()) continue;
                fire(entity, "Entity.checkDespawn", entity::checkDespawn);
                if (entity.isRemoved()) continue;
                fire(entity, "Entity.tick", () -> tickNonPassenger(entity));
            }
        }
        if (++ticks % SAVE_TICKS == 0) save();
        if (ticks % 20 == 0) restore();
    }

    private static void tickNonPassenger(Entity entity) {
        entity.setOldPosAndRot();
        entity.tickCount++;
        entity.tick();
        for (Entity passenger : entity.getPassengers()) tickPassenger(entity, passenger);
    }

    private static void tickPassenger(Entity vehicle, Entity passenger) {
        if (passenger.isRemoved() || passenger.getVehicle() != vehicle) {
            passenger.stopRiding();
            return;
        }
        passenger.setOldPosAndRot();
        passenger.tickCount++;
        passenger.rideTick();
        for (Entity rider : passenger.getPassengers()) tickPassenger(passenger, rider);
    }

    /** An entity whose chunk Horizon unloaded: saved, and out of the level until it loads. */
    private static void putAway(ServerLevel level, Entity entity) {
        CompoundTag tag = saveOne(level, entity);
        if (tag != null) {
            synchronized (HorizonEntities.class) {
                saved(dimension(level)).add(tag);
            }
        }
        entity.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
    }

    /** Saved entities whose chunks Horizon has now are made again (a chunk's entities load). */
    private static void restore() {
        for (ServerLevel level : HorizonServer.levels()) {
            List<CompoundTag> saved;
            synchronized (HorizonEntities.class) {
                saved = saved(dimension(level));
            }
            for (CompoundTag tag : new ArrayList<>(saved)) {
                var pos = tag.getListOrEmpty("Pos");
                if (pos.size() < 3) {
                    saved.remove(tag);
                    continue;
                }
                if (!loaded(level, pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0))) continue;
                saved.remove(tag);
                try {
                    Entity entity = EntityType.loadEntityRecursive(
                            TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
                            level, EntitySpawnReason.LOAD, e -> e);
                    if (entity != null && !add(level, entity)) entity.setRemoved(Entity.RemovalReason.DISCARDED);
                } catch (Throwable t) {
                    Natives.log("Java mods: an entity could not be loaded: " + t);
                }
            }
        }
    }

    private static List<CompoundTag> saved(String dimension) {
        return SAVED.computeIfAbsent(dimension, HorizonEntities::read);
    }

    private static Path file(String dimension) {
        return dir == null ? null : dir.resolve(dimension.replace(':', '_').replace('/', '_') + ".nbt");
    }

    private static List<CompoundTag> read(String dimension) {
        List<CompoundTag> out = new ArrayList<>();
        Path file = file(dimension);
        if (file == null || !Files.exists(file)) return out;
        try {
            ListTag list = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getListOrEmpty("entities");
            for (int i = 0; i < list.size(); i++) out.add(list.getCompoundOrEmpty(i));
        } catch (Throwable t) {
            Natives.log("Java mods: " + file + " could not be read: " + t);
        }
        return out;
    }

    /** An entity as a chunk saves it (Entity.save: its id, then its data); null if it won't. */
    private static CompoundTag saveOne(ServerLevel level, Entity entity) {
        try {
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            return entity.save(out) ? out.buildResult() : null;
        } catch (Throwable t) {
            Natives.log("Java mods: " + entity + " could not be saved: " + t);
            return null;
        }
    }

    /** Writes every dimension's entities: the live ones and those put away. */
    public static synchronized void save() {
        if (dir == null) return;
        Map<String, ListTag> lists = new HashMap<>();
        for (var e : SAVED.entrySet()) {
            ListTag list = lists.computeIfAbsent(e.getKey(), d -> new ListTag());
            for (CompoundTag tag : e.getValue()) list.add(tag);
        }
        for (var e : LIVE.entrySet()) {
            ListTag list = lists.computeIfAbsent(dimension(e.getKey()), d -> new ListTag());
            for (Entity entity : e.getValue().values()) {
                // Riders are saved with their vehicle (Entity.save writes its passengers).
                if (entity.isPassenger() || entity.isRemoved()) continue;
                CompoundTag tag = saveOne(e.getKey(), entity);
                if (tag != null) list.add(tag);
            }
        }
        try {
            Files.createDirectories(dir);
            for (var e : lists.entrySet()) {
                CompoundTag root = new CompoundTag();
                root.put("entities", (Tag) e.getValue());
                NbtIo.writeCompressed(root, file(e.getKey()));
            }
        } catch (Throwable t) {
            Natives.log("Java mods: entities could not be saved in " + dir + ": " + t);
        }
    }

    // ---- Players and mods' entities ---------------------------------------------------------

    /**
     * A player hit a mod's entity (ServerGamePacketListenerImpl.handleInteract's attack):
     * Player.attack, as vanilla's server runs it. False when there is no such entity near.
     */
    public static boolean attack(String uuid, int id) {
        Thread.currentThread().setContextClassLoader(HorizonEntities.class.getClassLoader());
        var player = HorizonServer.player(UUID.fromString(uuid));
        Entity target = byId(id);
        if (player == null || target == null || target.level() != player.level()) return false;
        // The mirror's player doesn't tick its attack strength: Horizon's player, whose
        // attack this is, swung with its own; the hit counts as charged.
        try {
            java.lang.reflect.Field ticker = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("attackStrengthTicker");
            ticker.setAccessible(true);
            ticker.setInt(player, 1000);
        } catch (ReflectiveOperationException e) {
            // Uncharged, then.
        }
        equipment(player);
        fire(target, "Player.attack", () -> player.attack(target));
        return true;
    }

    /**
     * A player used a mod's entity (handleInteract's interact: Player.interactOn, the entity's
     * interact then the item's): the result as useBlock's (0 nothing, 1 taken, 2 with a swing).
     */
    public static int interact(String uuid, int id, int hand, double x, double y, double z, int selected) {
        Thread.currentThread().setContextClassLoader(HorizonEntities.class.getClassLoader());
        var player = HorizonServer.player(UUID.fromString(uuid));
        Entity target = byId(id);
        if (player == null || target == null || target.level() != player.level()) return 0;
        if (selected >= 0 && selected < 9) player.getInventory().setSelectedSlot(selected);
        equipment(player);
        var interaction = hand == 1 ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        net.minecraft.world.InteractionResult[] result = {net.minecraft.world.InteractionResult.PASS};
        var at = new net.minecraft.world.phys.Vec3(x - target.getX(), y - target.getY(), z - target.getZ());
        fire(target, "Player.interactOn", () -> result[0] = player.interactOn(target, interaction, at));
        HorizonServer.afterCall();
        if (!result[0].consumesAction()) return 0;
        return result[0] instanceof net.minecraft.world.InteractionResult.Success success && success.shouldSwing() ? 2 : 1;
    }

    /**
     * LivingEntity.detectEquipmentUpdates, which a mirrored player (not ticked) misses: the held
     * item's attribute modifiers (a sword's damage) apply before it hits.
     */
    private static void equipment(net.minecraft.server.level.ServerPlayer player) {
        try {
            java.lang.reflect.Method detect = net.minecraft.world.entity.LivingEntity.class.getDeclaredMethod("detectEquipmentUpdates");
            detect.setAccessible(true);
            detect.invoke(player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Java mods: a player's equipment could not be read: " + e);
        }
    }

    static void fire(Entity entity, String what, Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            String key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " " + what + " " + t;
            if (FAILURES.merge(key, 1, Integer::sum) == 1) {
                Natives.log("Java mods: " + key);
                StackTraceElement[] frames = t.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
            }
        }
    }
}
