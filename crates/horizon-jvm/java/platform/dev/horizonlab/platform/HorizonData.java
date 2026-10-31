package dev.horizonlab.platform;

import com.mojang.serialization.Lifecycle;
import dev.horizonlab.bridge.Natives;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.Commands;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Util;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * The server's data, loaded as vanilla's game-test server loads it: the vanilla data pack
 * (and those Fabric mods add), giving the full registries with their tags bound (biomes,
 * dimension types, damage types...) and the reloadable resources (recipes, loot, advancements,
 * functions, and the command dispatcher, where Fabric API's command callback registers mods'
 * commands). Loaded once, after the mods' entrypoints, before Horizon's first tick.
 */
public final class HorizonData {
    private HorizonData() {}

    record Loaded(CloseableResourceManager resources, ReloadableServerResources reloadable,
                  LayeredRegistryAccess<RegistryLayer> registries,
                  net.minecraft.world.level.storage.PrimaryLevelData worldData) {}

    static Loaded loaded;

    /**
     * Loads the server's data; called by Horizon (horizon-jvm) once Fabric has started.
     *
     * @param datapacks a world data pack folder: its source is what makes Fabric API add the
     *     mods' own data packs (their recipes, tags, loot, translations) to the repository
     */
    public static void prepare(String datapacks) throws Exception {
        long started = System.nanoTime();
        // Vanilla finds its data pack through the context class loader; a thread Horizon
        // attached to the JVM has none.
        Thread.currentThread().setContextClassLoader(HorizonData.class.getClassLoader());
        java.nio.file.Path folder = java.nio.file.Paths.get(datapacks);
        java.nio.file.Files.createDirectories(folder);
        PackRepository packs = ServerPacksSource.createPackRepository(
                folder, new net.minecraft.world.level.validation.DirectoryValidator(path -> true));
        packs.reload();
        // A new world's packs, as vanilla picks them (MinecraftServer.configurePackRepository):
        // vanilla, then every pack whose source adds it on its own (Fabric's mod data and its
        // default-enabled built-in packs). Mods' optional built-in packs stay off: BetterEnd's
        // "flamboyant_extensions" holds recipes for a mod that may not be there, and enabling it
        // fails the whole registry load.
        WorldDataConfiguration configuration = net.minecraft.server.MinecraftServer.configurePackRepository(
                packs, new WorldDataConfiguration(DataPackConfig.DEFAULT, FeatureFlags.DEFAULT_FLAGS), false, false);
        Natives.log("data packs: " + configuration.dataPacks().getEnabled());
        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packs, configuration, false, true);
        WorldLoader.InitConfig init = new WorldLoader.InitConfig(
                packConfig, Commands.CommandSelection.DEDICATED, LevelBasedPermissionSet.OWNER);
        WorldLoader.WorldDataSupplier<net.minecraft.world.level.storage.PrimaryLevelData> world = context -> {
            MappedRegistry<net.minecraft.world.level.dimension.LevelStem> stems =
                    new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable());
            var dimensions = context.datapackWorldRegistries()
                    .lookupOrThrow(Registries.WORLD_PRESET)
                    .getOrThrow(WorldPresets.NORMAL)
                    .value()
                    .createWorldDimensions()
                    .bake(stems.freeze());
            // The world's settings, as the game-test server makes them. Horizon's own (game
            // mode, difficulty, rules) are mirrored over them as it hands them over.
            net.minecraft.world.level.LevelSettings settings = new net.minecraft.world.level.LevelSettings(
                    "Horizon", net.minecraft.world.level.GameType.SURVIVAL,
                    net.minecraft.world.level.LevelSettings.DifficultySettings.DEFAULT, true, configuration);
            var data = new net.minecraft.world.level.storage.PrimaryLevelData(
                    settings, dimensions.specialWorldProperty(), dimensions.lifecycle());
            return new WorldLoader.DataLoadOutput<>(data, dimensions.dimensionsRegistryAccess());
        };
        WorldLoader.ResultFactory<net.minecraft.world.level.storage.PrimaryLevelData, Loaded> result =
                (resources, reloadable, registries, data) -> new Loaded(resources, reloadable, registries, data);
        loaded = Util.blockUntilDone(executor ->
                WorldLoader.load(init, world, result, Util.backgroundExecutor(), executor)).get();
        // What MinecraftServer's constructor does next: recipe displays (recipe books) and the
        // recipe property sets (which items a furnace's or a smithing table's slots take).
        loaded.reloadable().getRecipeManager().finalizeRecipeLoading(configuration.enabledFeatures());
        Natives.log("Server data loaded in " + (System.nanoTime() - started) / 1_000_000 + " ms: "
                + loaded.reloadable().getRecipeManager().getRecipes().size() + " recipes, "
                + loaded.reloadable().getCommands().getDispatcher().getRoot().getChildren().size() + " commands");
    }
}
