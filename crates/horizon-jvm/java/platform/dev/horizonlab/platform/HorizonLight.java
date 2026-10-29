package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * A mirrored level's light (Level.getLightEngine): Horizon's, read where a mod asks (a crop's
 * check for light to grow). It propagates nothing; Horizon lights its own world.
 */
public final class HorizonLight extends LevelLightEngine {
    private final String dimension;

    HorizonLight(Level level) {
        super(new LightChunkGetter() {
            @Override
            public LightChunk getChunkForLighting(int x, int z) {
                return null;
            }

            @Override
            public BlockGetter getLevel() {
                return level;
            }
        }, false, false);
        this.dimension = level.dimension().identifier().toString();
    }

    /** Horizon's light there: sky light in the high four bits, block light in the low. */
    private int packed(BlockPos pos) {
        int light = Natives.light(dimension, pos.getX(), pos.getY(), pos.getZ());
        return light < 0 ? 0xF0 : light;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int skyDarken) {
        int light = packed(pos);
        return Math.max((light >> 4) - skyDarken, light & 15);
    }

    @Override
    public LayerLightEventListener getLayerListener(LightLayer layer) {
        return new LayerLightEventListener() {
            @Override
            public DataLayer getDataLayerData(SectionPos section) {
                return null;
            }

            @Override
            public int getLightValue(BlockPos pos) {
                int light = packed(pos);
                return layer == LightLayer.SKY ? light >> 4 : light & 15;
            }

            @Override
            public void checkBlock(BlockPos pos) {}

            @Override
            public boolean hasLightWork() {
                return false;
            }

            @Override
            public int runLightUpdates() {
                return 0;
            }

            @Override
            public void updateSectionStatus(SectionPos section, boolean empty) {}

            @Override
            public void setLightEnabled(ChunkPos chunk, boolean enabled) {}

            @Override
            public void propagateLightSources(ChunkPos chunk) {}
        };
    }

    @Override
    public void checkBlock(BlockPos pos) {}

    @Override
    public boolean hasLightWork() {
        return false;
    }

    @Override
    public int runLightUpdates() {
        return 0;
    }
}
