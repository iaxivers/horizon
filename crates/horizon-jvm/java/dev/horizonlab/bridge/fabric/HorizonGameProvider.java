package dev.horizonlab.bridge.fabric;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;

/**
 * Fabric Loader's view of Horizon: the game is "minecraft" at Horizon's version, its jar is
 * the facade (Minecraft's classes with stub bodies, made by horizon-jvm's facade.rs), and
 * nothing in it is patched: Horizon itself calls the mods' entrypoints ({@link #launch}).
 *
 * <p>Found by Fabric Loader's ServiceLoader (META-INF/services in the bridge jar) once
 * {@code fabric.skipMcProvider} turns off its own Minecraft provider. {@link FabricBoot} sets
 * the static configuration below before Knot starts.
 */
public final class HorizonGameProvider implements GameProvider {
    static String version = "26.3";
    static Path facade;
    static Path gameDir = Paths.get(".");
    static List<Path> parentClassPath = Collections.emptyList();
    static List<Path> libraries = Collections.emptyList();

    private final GameTransformer transformer = new GameTransformer();
    private final Arguments arguments = new Arguments();

    @Override
    public String getGameId() {
        return "minecraft";
    }

    @Override
    public String getGameName() {
        return "Minecraft (Horizon)";
    }

    @Override
    public String getRawGameVersion() {
        return version;
    }

    @Override
    public String getNormalizedGameVersion() {
        return version;
    }

    @Override
    public Collection<BuiltinMod> getBuiltinMods() {
        BuiltinModMetadata.Builder metadata = new BuiltinModMetadata.Builder(getGameId(), version)
                .setName("Minecraft")
                .setDescription("Minecraft " + version + ", run by Horizon");
        return Collections.singletonList(new BuiltinMod(Collections.singletonList(facade), metadata.build()));
    }

    @Override
    public String getEntrypoint() {
        return "dev.horizonlab.bridge.fabric.FabricBoot";
    }

    @Override
    public Path getLaunchDirectory() {
        return gameDir;
    }

    @Override
    public boolean requiresUrlClassLoader() {
        return false;
    }

    @Override
    public Set<BuiltinTransform> getBuiltinTransforms(String className) {
        boolean game = className.startsWith("net.minecraft.") || className.startsWith("com.mojang.");
        return game
                ? EnumSet.of(BuiltinTransform.WIDEN_ALL_PACKAGE_ACCESS, BuiltinTransform.CLASS_TWEAKS)
                : EnumSet.of(BuiltinTransform.STRIP_ENVIRONMENT);
    }

    @Override
    public boolean isEnabled() {
        return facade != null;
    }

    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        arguments.parse(args);
        return facade != null && facade.toFile().isFile();
    }

    @Override
    public void initialize(FabricLauncher launcher) {
        launcher.setValidParentClassPath(parentClassPath);
        // No patches: an empty table, so the transformer answers "unchanged" for every class.
        transformer.locateEntrypoints(launcher, new ArrayList<>(Collections.singletonList(facade)));
    }

    @Override
    public GameTransformer getEntrypointTransformer() {
        return transformer;
    }

    @Override
    public void unlockClassPath(FabricLauncher launcher) {
        launcher.addToClassPath(facade);
        for (Path library : libraries) {
            launcher.addToClassPath(library);
        }
    }

    @Override
    public void launch(ClassLoader loader) {
        // Horizon is the game: there is no main class to start. FabricBoot runs the
        // entrypoints itself once Knot is ready.
    }

    @Override
    public boolean canOpenErrorGui() {
        return false;
    }

    @Override
    public boolean hasAwtSupport() {
        return false;
    }

    @Override
    public Arguments getArguments() {
        return arguments;
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        return new String[0];
    }
}
