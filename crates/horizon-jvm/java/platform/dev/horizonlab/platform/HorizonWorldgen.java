package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Mods' world generation: the chunks of a dimension whose generation mods change (biomes a
 * mod adds, as TerraBlender's regions put Biomes O' Plenty's into the overworld's biome
 * source; features a mod adds to biomes) are generated here, by vanilla's own chunk steps
 * (ChunkPyramid.GENERATION_PYRAMID) run on the mirror's level, and handed to Horizon as
 * vanilla saves them. Horizon keeps its own generator for vanilla dimensions.
 *
 * <p>Horizon drives it as it drives its own generator: {@link #proto} brings a chunk to
 * TERRAIN (structure starts and references, biomes, noise, surface, carvers), {@link
 * #decorate} runs FEATURES once its eight neighbours are protos too (features write into
 * them), {@link #finish} gives the chunk and lets it go, {@link #forget} drops one Horizon let
 * go unfinished. The chunks Horizon holds are "live"; the neighbours vanilla's steps read
 * beyond them (structure starts eight chunks out, biomes one chunk out) are made here and
 * kept in a small cache, as ChunkMap keeps its lower-status chunks.
 */
public final class HorizonWorldgen {
    private HorizonWorldgen() {}

    /** The steps of a proto chunk, in order; FEATURES is decoration's. */
    private static final ChunkStatus[] STEPS = {
            ChunkStatus.STRUCTURE_STARTS, ChunkStatus.STRUCTURE_REFERENCES, ChunkStatus.BIOMES, ChunkStatus.TERRAIN,
            ChunkStatus.FEATURES};
    /** Chunks Horizon doesn't hold kept for their neighbours' steps (ChunkMap's lower statuses). */
    private static final int PARTIAL_CHUNKS = 3072;

    private record Live(ProtoChunk chunk, long token) {}

    private static final class Dimension {
        final ServerLevel level;
        final ChunkGenerator generator;
        final WorldGenContext context;
        final PalettedContainerFactory containers;
        final Map<Long, Live> live = new ConcurrentHashMap<>();
        final Map<Long, ProtoChunk> partial = java.util.Collections.synchronizedMap(
                new LinkedHashMap<>(PARTIAL_CHUNKS, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, ProtoChunk> eldest) {
                        return size() > PARTIAL_CHUNKS;
                    }
                });

        Dimension(ServerLevel level, ChunkGenerator generator, WorldGenContext context, PalettedContainerFactory containers) {
            this.level = level;
            this.generator = generator;
            this.context = context;
            this.containers = containers;
        }
    }

    private static final Map<String, Dimension> DIMENSIONS = new ConcurrentHashMap<>();
    private static final AtomicLong TOKENS = new AtomicLong();
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static boolean serverReady;

    // ---- Start ----------------------------------------------------------------------------

    /**
     * Readies generation of {@code dimension} for Horizon's world (its seed, whether it has
     * structures): whether mods change that dimension's generation, so Horizon generates its
     * chunks here. Called once a dimension, before its first chunk.
     */
    public static synchronized boolean start(String dimension, long seed, boolean structures) {
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        try {
            HorizonServer.ensureServer();
            HorizonServer.worldSeed(seed);
            if (!serverReady) {
                serverReady = true;
                readyServer(seed, structures);
            }
            ServerLevel level = HorizonServer.level(dimension);
            ChunkGenerator generator = HorizonServer.generator(level);
            if (generator == null) return false;
            List<String> mods = modded(level, generator);
            if (mods.isEmpty()) {
                Natives.log("Java mods: " + dimension + " generates as vanilla's (Horizon's generator)");
                return false;
            }
            MinecraftServer server = HorizonServer.server();
            PalettedContainerFactory containers = PalettedContainerFactory.create(level.registryAccess());
            readyLevel(level, generator, server, containers, seed);
            WorldGenContext context = new WorldGenContext(level, generator, server.getStructureTemplateManager(),
                    null, Runnable::run, null);
            DIMENSIONS.put(dimension, new Dimension(level, generator, context, containers));
            Natives.log("Java mods: " + dimension + " generates through the mods (" + String.join(", ", mods) + ")");
            return true;
        } catch (Throwable t) {
            Natives.log("Java mods: " + dimension + " can't generate through the mods: " + t);
            log(t);
            return false;
        }
    }

    /**
     * What MinecraftServer's constructor gives world generation, on the mirrored server: the
     * world's settings (seed, structures), the structure templates, the data fixer; then the
     * mods' injections at the constructor's end, where world-generation mods set up
     * (TerraBlender puts its regions into the level stems' biome sources and its surface rules
     * into their noise settings; Fabric API applies biome modifications).
     */
    private static void readyServer(long seed, boolean structures) throws ReflectiveOperationException {
        MinecraftServer server = HorizonServer.server();
        var registries = server.registryAccess();
        set(MinecraftServer.class, server, "worldGenSettings",
                WorldGenSettings.of(new WorldOptions(seed, structures, false), registries));
        var fixer = net.minecraft.util.datafix.DataFixers.getDataFixer();
        set(MinecraftServer.class, server, "fixerUpper", fixer);
        // Structure templates from the data packs (vanilla's and the mods'); Horizon keeps
        // the world's own structure files.
        StructureTemplateManager templates = (StructureTemplateManager) Natives.allocate(StructureTemplateManager.class);
        var source = new net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource(
                fixer, registries.lookupOrThrow(Registries.BLOCK), HorizonData.loaded.resources(),
                new net.minecraft.resources.FileToIdConverter("structure", ".nbt"));
        set(StructureTemplateManager.class, templates, "resourceManagerSource", source);
        set(StructureTemplateManager.class, templates, "sources", List.of(source));
        set(StructureTemplateManager.class, templates, "structureRepository", new ConcurrentHashMap<>());
        set(MinecraftServer.class, server, "structureTemplateManager", templates);
        for (String at : new String[] {"RETURN", "TAIL"}) {
            HorizonHooks.fire(server, MinecraftServer.class, "<init>", "()V", at, new Object[0], null, false);
        }
    }

    /** What ServerLevel's constructor makes for generation, on a mirrored level. */
    private static void readyLevel(ServerLevel level, ChunkGenerator generator, MinecraftServer server,
                                   PalettedContainerFactory containers, long seed) throws ReflectiveOperationException {
        var random = level.getChunkSource().randomState();
        if (level.palettedContainerFactory() == null) {
            set(net.minecraft.world.level.Level.class, level, "palettedContainerFactory", containers);
        }
        // Nothing of Horizon's chunks is scanned for structures (locating is Horizon's).
        net.minecraft.world.level.chunk.storage.ChunkScanAccess scanner =
                (pos, visitor) -> CompletableFuture.completedFuture(null);
        StructureCheck check = new StructureCheck(scanner, level.registryAccess(), server.getStructureTemplateManager(),
                level.dimension(), generator, random, level, generator.getBiomeSource(), seed, server.getFixerUpper());
        set(ServerLevel.class, level, "structureCheck", check);
        set(ServerLevel.class, level, "structureManager",
                new StructureManager(level, server.getWorldGenSettings().options(), check));
        set(ServerLevel.class, level, "uncachedBiomeResolver", generator.getBiomeSource().createUncachedResolver(random));
    }

    /** The mods that change a dimension's generation: their biomes in its source, their features in its biomes. */
    private static List<String> modded(ServerLevel level, ChunkGenerator generator) {
        Set<String> mods = new java.util.TreeSet<>();
        var features = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        for (Holder<Biome> biome : generator.getBiomeSource().possibleBiomes()) {
            biome.unwrapKey().ifPresent(k -> {
                if (!k.identifier().getNamespace().equals("minecraft")) mods.add(k.identifier().getNamespace());
            });
            for (var step : generator.getBiomeGenerationSettings(biome).features()) {
                for (var feature : step) {
                    feature.unwrapKey().or(() -> features.getResourceKey(feature.value())).ifPresent(k -> {
                        if (!k.identifier().getNamespace().equals("minecraft")) mods.add(k.identifier().getNamespace());
                    });
                }
            }
        }
        return new ArrayList<>(mods);
    }

    // ---- Horizon's calls ------------------------------------------------------------------

    /** Brings chunk x z of dimension to TERRAIN and holds it for Horizon: its token (0: failed). */
    public static long proto(String dimension, int x, int z) {
        // Horizon's generation threads, attached to the JVM, have no context class loader.
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return 0;
        long key = ChunkPos.pack(x, z);
        try {
            ProtoChunk chunk = d.partial.remove(key);
            Live old = d.live.get(key);
            if (old != null) chunk = old.chunk();
            if (chunk == null) chunk = fresh(d, x, z);
            long token = TOKENS.incrementAndGet();
            d.live.put(key, new Live(chunk, token));
            advance(d, chunk, ChunkStatus.TERRAIN);
            return token;
        } catch (Throwable t) {
            failed("proto", t);
            d.live.remove(key);
            return 0;
        }
    }

    /** Runs FEATURES on live chunk x z (its eight neighbours are live too). */
    public static void decorate(String dimension, int x, int z) {
        // Horizon's generation threads, attached to the JVM, have no context class loader.
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return;
        Live live = d.live.get(ChunkPos.pack(x, z));
        if (live == null) return;
        try {
            advance(d, live.chunk(), ChunkStatus.FEATURES);
        } catch (Throwable t) {
            failed("features", t);
        }
    }

    /** Live chunk x z as vanilla saves a chunk (NBT, full status), let go; null if it has none. */
    public static byte[] finish(String dimension, int x, int z, long token) {
        // Horizon's generation threads, attached to the JVM, have no context class loader.
        Thread.currentThread().setContextClassLoader(HorizonWorldgen.class.getClassLoader());
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return null;
        long key = ChunkPos.pack(x, z);
        Live live = d.live.get(key);
        if (live == null || live.token() != token) return null;
        d.live.remove(key, live);
        try {
            CompoundTag tag = save(d, live.chunk());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            NbtIo.write(tag, new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (Throwable t) {
            failed("finish", t);
            return null;
        }
    }

    /** Horizon let live chunk x z go unfinished. */
    public static void forget(String dimension, int x, int z, long token) {
        Dimension d = DIMENSIONS.get(dimension);
        if (d == null) return;
        long key = ChunkPos.pack(x, z);
        Live live = d.live.get(key);
        if (live != null && live.token() == token) d.live.remove(key, live);
    }

    // ---- Steps ------------------------------------------------------------------------------

    private static ProtoChunk fresh(Dimension d, int x, int z) {
        return new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, d.level, d.containers, null);
    }

    /**
     * Runs vanilla's steps on {@code chunk} up to {@code target}, one at a time under the
     * chunk's lock: a step reads its neighbours at lower statuses only, so two threads
     * advancing neighbours never wait on each other in a circle.
     */
    private static void advance(Dimension d, ProtoChunk chunk, ChunkStatus target) {
        for (ChunkStatus status : STEPS) {
            if (status.isAfter(target)) break;
            if (chunk.getPersistedStatus().isOrAfter(status)) continue;
            synchronized (chunk) {
                if (chunk.getPersistedStatus().isOrAfter(status)) continue;
                ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
                step.apply(d.context, around(d, chunk, step), chunk).join();
                if (!chunk.getPersistedStatus().isOrAfter(status)) chunk.setPersistedStatus(status);
            }
        }
    }

    /** The chunks a step reads around {@code center}, each at the status the step needs at its distance. */
    private static StaticCache2D<GenerationChunkHolder> around(Dimension d, ProtoChunk center, ChunkStep step) {
        ChunkPos pos = center.getPos();
        var needs = step.directDependencies();
        int radius = Math.max(needs.size() - 1, 0);
        return StaticCache2D.create(pos.x(), pos.z(), radius, (x, z) -> {
            if (x == pos.x() && z == pos.z()) return new Held(center);
            int distance = Math.max(Math.abs(x - pos.x()), Math.abs(z - pos.z()));
            return new Held(neighbour(d, x, z, needs.get(distance)));
        });
    }

    /** Chunk x z at {@code status} at least: Horizon's if it holds it, else one of the cache's. */
    private static ProtoChunk neighbour(Dimension d, int x, int z, ChunkStatus status) {
        long key = ChunkPos.pack(x, z);
        Live live = d.live.get(key);
        ProtoChunk chunk = live != null ? live.chunk() : d.partial.get(key);
        if (chunk == null) {
            chunk = fresh(d, x, z);
            ProtoChunk raced = d.partial.putIfAbsent(key, chunk);
            if (raced != null) chunk = raced;
        }
        advance(d, chunk, status);
        return chunk;
    }

    /** A chunk holder that only has its chunk, at every status up to the chunk's. */
    private static final class Held extends GenerationChunkHolder {
        private static java.lang.reflect.Field futures;

        Held(ProtoChunk chunk) {
            super(chunk.getPos());
            try {
                if (futures == null) {
                    var f = GenerationChunkHolder.class.getDeclaredField("futures");
                    f.setAccessible(true);
                    futures = f;
                }
                @SuppressWarnings("unchecked")
                var array = (AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>>) futures.get(this);
                var done = CompletableFuture.completedFuture(ChunkResult.<ChunkAccess>of(chunk));
                ChunkStatus status = chunk.getPersistedStatus();
                for (ChunkStatus s : ChunkStatus.getStatusList()) {
                    if (s.isAfter(status)) break;
                    array.set(s.getIndex(), done);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        protected void addSaveDependency(CompletableFuture<?> sync) {}

        @Override
        public int getTicketLevel() {
            return 33;
        }

        @Override
        public int getQueueLevel() {
            return 33;
        }
    }

    // ---- The chunk as vanilla saves it ----------------------------------------------------

    /**
     * SerializableChunkData's form of a generated chunk (what Horizon reads of a saved world):
     * its sections' block states and biomes, block entities, entities (structures' mobs) and
     * structure references, as a full chunk. Light, heightmaps and ticks are Horizon's.
     */
    private static CompoundTag save(Dimension d, ProtoChunk chunk) {
        CompoundTag tag = new CompoundTag();
        ChunkPos pos = chunk.getPos();
        tag.putInt("xPos", pos.x());
        tag.putInt("yPos", chunk.getMinSectionY());
        tag.putInt("zPos", pos.z());
        tag.putString("Status", "minecraft:full");
        ListTag sections = new ListTag();
        LevelChunkSection[] all = chunk.getSections();
        for (int i = 0; i < all.length; i++) {
            CompoundTag section = new CompoundTag();
            section.store("block_states", d.containers.blockStatesContainerCodec(), all[i].getStates());
            section.store("biomes", d.containers.biomeContainerCodec(), all[i].getBiomes());
            section.putByte("Y", (byte) (chunk.getMinSectionY() + i));
            sections.add(section);
        }
        tag.put("sections", sections);
        ListTag blockEntities = new ListTag();
        for (BlockPos at : chunk.getBlockEntitiesPos()) {
            CompoundTag be = chunk.getBlockEntityNbtForSaving(at, d.level.registryAccess());
            if (be != null) blockEntities.add(be);
        }
        tag.put("block_entities", blockEntities);
        ListTag entities = new ListTag();
        entities.addAll(chunk.getEntities());
        tag.put("entities", entities);
        CompoundTag references = new CompoundTag();
        var structures = d.level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        chunk.getAllReferences().forEach((structure, starts) -> {
            var id = structures.getKey(structure);
            if (id != null && !starts.isEmpty()) references.put(id.toString(), new LongArrayTag(starts.toLongArray()));
        });
        CompoundTag structureData = new CompoundTag();
        structureData.put("References", references);
        tag.put("structures", structureData);
        return tag;
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private static void set(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void failed(String what, Throwable t) {
        if (FAILURES.merge(what + t, 1, Integer::sum) <= 3) {
            Natives.log("Java mods' worldgen: " + what + " failed: " + t);
            log(t);
        }
    }

    private static void log(Throwable t) {
        Throwable cause = t.getCause() != null && t instanceof java.util.concurrent.CompletionException ? t.getCause() : t;
        StackTraceElement[] frames = cause.getStackTrace();
        if (cause != t) Natives.log("  caused by " + cause);
        for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
    }
}
