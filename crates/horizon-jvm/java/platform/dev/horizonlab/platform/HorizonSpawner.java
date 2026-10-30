package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.MobSpawnSettings;

/**
 * Natural spawning of mods' mobs. Horizon spawns vanilla's; the mods' (their biomes' spawn
 * lists, which Fabric's BiomeModifications fill) spawn here, as NaturalSpawner does it per
 * player: each tick a chunk near each player, each category under its cap (counting the mods'
 * mobs only), a position in it, a group of the type the biome picks, each member placed where
 * its spawn placement and rules allow, out of the player's 24 blocks. Creatures (persistent
 * categories) spawn every 400 ticks, as vanilla's.
 *
 * <p>The mirrored levels get their biomes here too (Level.biomeManager, from the dimension's
 * biome source with Horizon's seed: Horizon generates vanilla's terrain), and their
 * environment attributes with the biome layer (spawn lists are one).
 */
public final class HorizonSpawner {
    private HorizonSpawner() {}

    private static final RandomSource RANDOM = RandomSource.create();
    private static final Map<ServerLevel, Long> BIOMES_SEED = new IdentityHashMap<>();
    private static boolean modified;
    private static int ticks;

    /** Each server tick: the levels' biomes, then spawning near each player. */
    public static void tick() {
        ticks++;
        for (ServerLevel level : HorizonServer.levels()) {
            biomes(level);
            if (level.players().isEmpty()) continue;
            boolean mobs;
            try {
                mobs = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS);
            } catch (Throwable t) {
                mobs = true;
            }
            if (!mobs) continue;
            for (ServerPlayer player : List.copyOf(level.players())) {
                if (player.isSpectator()) continue;
                for (MobCategory category : MobCategory.values()) {
                    if (category == MobCategory.MISC) continue;
                    if (category.isPersistent() && ticks % 400 != 0) continue;
                    try {
                        spawn(level, player, category);
                    } catch (Throwable t) {
                        HorizonServer.fire("natural spawning (" + category.getName() + ")", () -> { throw new RuntimeException(t); });
                    }
                }
            }
        }
    }

    /**
     * Level.biomeManager and the biome layer of the environment attributes, once Horizon's
     * seed is known (and again if it changes: another world).
     */
    private static void biomes(ServerLevel level) {
        long seed = HorizonServer.seed();
        Long had = BIOMES_SEED.get(level);
        if (had != null && had == seed) return;
        BIOMES_SEED.put(level, seed);
        try {
            if (!modified) {
                modified = true;
                // Fabric's BiomeModifications (mods' spawns added to biomes), as its mixin into
                // MinecraftServer's constructor (which the mirror skips) applies them.
                net.fabricmc.fabric.impl.biome.modification.BiomeModificationImpl.INSTANCE.finalizeWorldGen(
                        HorizonData.loaded.registries().compositeAccess());
            }
        } catch (IllegalStateException done) {
            // Applied already: the mods' worldgen set-up fires the same constructor hooks
            // (HorizonWorldgen.readyServer), and Fabric applies them once a registry access.
        } catch (Throwable t) {
            Natives.log("Java mods: biome modifications could not be applied: " + t);
        }
        try {
            var generator = HorizonServer.generator(level);
            if (generator == null) return;
            var resolver = generator.getBiomeSource().createCachingResolver(HorizonServer.randomState(level));
            java.lang.reflect.Field manager = net.minecraft.world.level.Level.class.getDeclaredField("biomeManager");
            manager.setAccessible(true);
            manager.set(level, new BiomeManager(resolver, BiomeManager.obfuscateSeed(seed)));
            level.setEnvironmentAttributes(net.minecraft.world.attribute.EnvironmentAttributeSystem.builder()
                    .addStaticLayers(level).build());
        } catch (Throwable t) {
            Natives.log("Java mods: a level's biomes could not be set: " + t);
        }
    }

    private static void spawn(ServerLevel level, ServerPlayer player, MobCategory category) {
        // The cap: the category's per-chunk cap (vanilla's mob cap per player), mods' mobs only.
        int cap = category.getMaxInstancesPerChunk();
        int count = 0;
        for (Entity e : HorizonEntities.of(level)) {
            if (e.getType().getCategory() == category && e.distanceToSqr(player) < 128 * 128) count++;
        }
        if (count >= cap) return;
        // A chunk within 8 of the player's, a position in it near the player's height.
        int cx = (player.getBlockX() >> 4) + RANDOM.nextInt(17) - 8;
        int cz = (player.getBlockZ() >> 4) + RANDOM.nextInt(17) - 8;
        int x = (cx << 4) + RANDOM.nextInt(16);
        int z = (cz << 4) + RANDOM.nextInt(16);
        int y = Math.max(level.getMinY(), Math.min(level.getMaxY(), player.getBlockY() + RANDOM.nextInt(49) - 24));
        BlockPos center = new BlockPos(x, y, z);
        if (!level.isLoaded(center)) return;
        // NaturalSpawner.spawnCategoryForPosition: up to 3 groups, each up to 4 tries around.
        MobSpawnSettings.SpawnerData data = null;
        int size = 0;
        for (int group = 0; group < 3; group++) {
            int px = x, pz = z;
            for (int i = 0; i < 4; i++) {
                px += RANDOM.nextInt(6) - RANDOM.nextInt(6);
                pz += RANDOM.nextInt(6) - RANDOM.nextInt(6);
                BlockPos pos = ground(level, new BlockPos(px, y, pz));
                if (pos == null) continue;
                double dx = px + 0.5, dz = pz + 0.5;
                if (player.distanceToSqr(dx, pos.getY(), dz) <= 24 * 24) continue;
                if (data == null) {
                    var settings = level.environmentAttributes().getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos);
                    if (!(settings instanceof MobSpawnSettings mobs)) return;
                    data = mobs.getMobsToSpawn(category).getRandom(RANDOM).orElse(null);
                    // Horizon spawns vanilla's own mobs.
                    if (data == null || !HorizonEntities.modded(data.type())) return;
                    size = data.count().sample(RANDOM);
                }
                if (spawnOne(level, data.type(), category, pos, dx, dz) && --size <= 0) return;
            }
        }
    }

    /**
     * The first open block above the ground at or below {@code pos} (within 32): where
     * NaturalSpawner's random height under the heightmap lands for a mob that stands, without
     * the chunk's heightmap the mirror lacks. Null for none.
     */
    private static BlockPos ground(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos at = pos.mutable();
        if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return null;
        for (int i = 0; i < 32 && at.getY() > level.getMinY(); i++) {
            at.move(0, -1, 0);
            if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty() || !level.getFluidState(at).isEmpty()) {
                return at.above().immutable();
            }
        }
        return null;
    }

    private static boolean spawnOne(ServerLevel level, EntityType<?> type, MobCategory category, BlockPos pos, double x, double z) {
        if (!type.canSummon() || type.getCategory() != category) return false;
        if (!SpawnPlacements.isSpawnPositionOk(type, level, pos)) return false;
        if (!SpawnPlacements.checkSpawnRules(type, level, EntitySpawnReason.NATURAL, pos, RANDOM)) return false;
        if (!level.noCollision(type.getSpawnAABB(x, pos.getY(), z))) return false;
        Entity entity = type.create(level, EntitySpawnReason.NATURAL);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) entity.discard();
            return false;
        }
        mob.snapTo(x, pos.getY(), z, RANDOM.nextFloat() * 360.0F, 0.0F);
        if (!mob.checkSpawnRules(level, EntitySpawnReason.NATURAL) || !mob.checkSpawnObstruction(level)) return false;
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.NATURAL, null);
        level.addFreshEntityWithPassengers(mob);
        return true;
    }
}
