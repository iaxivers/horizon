package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Chunks of a mirrored level, for code that reads a chunk rather than the level: 26.3's block
 * scans (BlockScanUtils, behind a sapling's "flowers nearby", a vine's spread) walk a chunk's
 * sections, and heightmaps are a chunk's. A copy of Horizon's chunk is made the first time one
 * is asked for in a call (its blocks, biomes and heightmaps at once: Natives.chunkBlocks), kept
 * for that call only (Natives.viewEpoch), and follows what the call sets. What is set on it
 * goes to the level (Horizon), as a mod's Level.setBlock does.
 */
public final class HorizonChunks {
    private HorizonChunks() {}

    private static final Map<ServerLevel, Map<Long, LevelChunk>> COPIES = new IdentityHashMap<>();
    private static long epoch = Long.MIN_VALUE;

    /** Horizon's chunk cx cz of a mirrored level as a LevelChunk; null where none is loaded. */
    public static LevelChunk chunk(ServerLevel level, int cx, int cz) {
        if (level.palettedContainerFactory() == null) return null;
        synchronized (COPIES) {
            long now = Natives.viewEpoch();
            if (now != epoch) {
                COPIES.clear();
                epoch = now;
            }
            Map<Long, LevelChunk> copies = COPIES.computeIfAbsent(level, l -> new HashMap<>());
            long key = ChunkPos.pack(cx, cz);
            LevelChunk copy = copies.get(key);
            if (copy == null && !copies.containsKey(key)) {
                copy = copy(level, cx, cz);
                copies.put(key, copy);
            }
            return copy;
        }
    }

    /** A block a call set on a mirrored level: the copy of its chunk (if made) follows. */
    static void set(ServerLevel level, BlockPos pos, BlockState state) {
        synchronized (COPIES) {
            if (Natives.viewEpoch() != epoch) return;
            Map<Long, LevelChunk> copies = COPIES.get(level);
            if (copies == null) return;
            LevelChunk copy = copies.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
            if (copy instanceof Copy c) c.follow(pos, state);
        }
    }

    private static LevelChunk copy(ServerLevel level, int cx, int cz) {
        String dimension = level.dimension().identifier().toString();
        int minY = level.getMinY();
        int height = level.getHeight();
        int[] blocks = new int[height * 256];
        String[] palette = Natives.chunkBlocks(dimension, cx, cz, minY, blocks);
        if (palette == null) return null;
        Copy chunk = new Copy(level, new ChunkPos(cx, cz));
        BlockState[] states = new BlockState[palette.length];
        for (int i = 0; i < palette.length; i++) {
            BlockState state = HorizonServer.parseState(palette[i]);
            states[i] = state != null ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < blocks.length; i++) {
            BlockState state = states[blocks[i]];
            if (state.isAir()) continue;
            int y = i >> 8;
            sections[y >> 4].setBlockState(i & 15, y & 15, (i >> 4) & 15, state, false);
        }
        int[] quarts = new int[height / 4 * 16];
        String[] biomes = Natives.chunkBiomes(dimension, cx, cz, minY, quarts);
        if (biomes != null) {
            var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
            @SuppressWarnings("unchecked")
            Holder<Biome>[] holders = new Holder[biomes.length];
            for (int i = 0; i < biomes.length; i++) {
                holders[i] = registry.get(ResourceKey.create(Registries.BIOME, Identifier.parse(biomes[i])))
                        .map(h -> (Holder<Biome>) h).orElse(null);
            }
            for (int s = 0; s < sections.length; s++) {
                final LevelChunkSection section = sections[s];
                section.fillBiomesFromNoise((qx, qy, qz) -> {
                    int i = (((qy - (minY >> 2)) * 4 + (qz & 3)) * 4) + (qx & 3);
                    Holder<Biome> h = i >= 0 && i < quarts.length ? holders[quarts[i]] : null;
                    return h != null ? h : section.getNoiseBiome(qx & 3, qy & 3, qz & 3);
                }, cx << 2, (minY >> 2) + s * 4, cz << 2);
            }
        }
        // The calls this copy takes from the level's own, made after the copy (writes so far).
        Map<Long, BlockState> written = HorizonServer.written(level);
        if (written != null) {
            written.forEach((at, state) -> {
                BlockPos pos = BlockPos.of(at);
                if (pos.getX() >> 4 == cx && pos.getZ() >> 4 == cz) chunk.follow(pos, state);
            });
        }
        Heightmap.primeHeightmaps(chunk, java.util.EnumSet.allOf(Heightmap.Types.class));
        return chunk;
    }

    /** A copied chunk: what is set on it goes to the level, as Level.setBlock. */
    private static final class Copy extends LevelChunk {
        Copy(ServerLevel level, ChunkPos pos) {
            super(level, pos);
        }

        void follow(BlockPos pos, BlockState state) {
            LevelChunkSection section = getSection(getSectionIndex(pos.getY()));
            section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
            for (var heightmap : getHeightmaps()) {
                heightmap.getValue().update(pos.getX() & 15, pos.getY(), pos.getZ() & 15, state);
            }
        }

        @Override
        public BlockState setBlockState(BlockPos pos, BlockState state, int flags) {
            BlockState old = getBlockState(pos);
            getLevel().setBlock(pos, state, flags);
            return old == state ? null : old;
        }
    }
}
