package dev.horizonlab.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The mods' block tints (BlockColors' tint sources, as mods register them through Fabric's
 * BlockColorRegistry: Biomes O' Plenty's flowering oak leaves take the foliage colour on their
 * base layer), for Horizon's mesher, which has vanilla's table of BlockColors.createDefault.
 * Each source is asked its colour in a world whose every biome colour is a marker, so what it
 * reads (grass, foliage, dry foliage, water) is known; a source that reads none is a constant.
 */
public final class HorizonTints {
    private HorizonTints() {}

    private static final int GRASS = 0x010203, FOLIAGE = 0x040506, DRY = 0x070809, WATER = 0x0A0B0C;

    /** Writes {block id: [one tint a tint index: "grass", "foliage", "dry_foliage", "water", "none" or "#rrggbb"]}. */
    public static void export(String out) throws IOException {
        JsonObject tints = new JsonObject();
        for (Map.Entry<Block, List<BlockTintSource>> e : sources().entrySet()) {
            var id = BuiltInRegistries.BLOCK.getKey(e.getKey());
            if (id.getNamespace().equals("minecraft") || e.getValue() == null) continue;
            BlockState state = e.getKey().defaultBlockState();
            JsonArray layers = new JsonArray();
            for (BlockTintSource source : e.getValue()) layers.add(kind(source, state));
            tints.add(id.toString(), layers);
        }
        Files.writeString(Path.of(out), tints.toString());
    }

    /** The tint sources mods registered: Fabric's pending ones, or the client's BlockColors'. */
    @SuppressWarnings("unchecked")
    private static Map<Block, List<BlockTintSource>> sources() {
        try {
            Class<?> impl = Class.forName("net.fabricmc.fabric.impl.client.rendering.BlockColorRegistryImpl",
                    true, HorizonTints.class.getClassLoader());
            var map = impl.getDeclaredField("map");
            map.setAccessible(true);
            Object pending = map.get(null);
            if (pending instanceof Map<?, ?> m && !m.isEmpty()) return (Map<Block, List<BlockTintSource>>) m;
            var colors = impl.getDeclaredField("blockColors");
            colors.setAccessible(true);
            Object blockColors = colors.get(null);
            if (blockColors == null) return Map.of();
            var all = net.minecraft.client.color.block.BlockColors.class.getDeclaredField("sources");
            all.setAccessible(true);
            return (Map<Block, List<BlockTintSource>>) all.get(blockColors);
        } catch (ReflectiveOperationException e) {
            return Map.of();
        }
    }

    private static String kind(BlockTintSource source, BlockState state) {
        if (source == null) return "none";
        int color;
        try {
            color = source.colorInWorld(state, world(state), BlockPos.ZERO);
        } catch (Throwable t) {
            try {
                color = source.color(state);
            } catch (Throwable u) {
                return "none";
            }
        }
        if (color == -1) return "none";
        return switch (color & 0xFFFFFF) {
            case GRASS -> "grass";
            case FOLIAGE -> "foliage";
            case DRY -> "dry_foliage";
            case WATER -> "water";
            default -> String.format("#%06x", color & 0xFFFFFF);
        };
    }

    /** A world of {@code state} everywhere whose biome colours are the markers. */
    private static BlockAndTintGetter world(BlockState state) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getBlockTint": {
                    ColorResolver resolver = (ColorResolver) args[1];
                    if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) return GRASS;
                    if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) return FOLIAGE;
                    if (resolver == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER) return DRY;
                    if (resolver == BiomeColors.WATER_COLOR_RESOLVER) return WATER;
                    return 0xFFFFFF;
                }
                case "getBlockState": return state;
                case "getFluidState": return state.getFluidState();
                case "getHeight": return 384;
                case "getMinY": return -64;
                default:
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                    Class<?> type = method.getReturnType();
                    if (type == int.class) return 0;
                    if (type == float.class) return 1f;
                    if (type == boolean.class) return false;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0d;
                    return null;
            }
        };
        return (BlockAndTintGetter) Proxy.newProxyInstance(HorizonTints.class.getClassLoader(),
                new Class<?>[] {BlockAndTintGetter.class}, handler);
    }
}
