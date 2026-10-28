package dev.horizonlab.bridge.fabric;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import dev.horizonlab.bridge.Natives;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.knot.Knot;

/**
 * Starts Fabric Loader inside Horizon's JVM (called from Rust, horizon-jvm): Knot with
 * {@link HorizonGameProvider}, mod discovery in mods-java/, Mixin on the facade, then the mods'
 * {@code main} and {@code client} (or {@code server}) entrypoints.
 */
public final class FabricBoot {
    private FabricBoot() {}

    private static ClassLoader gameLoader;

    /**
     * @param side "client" or "server"
     * @param version the game version Horizon plays ("26.3")
     * @param facade the facade jar
     * @param libraries the game's libraries, on Knot's class path beside the facade
     * @param modsDir the folder of mod jars (mods-java/)
     * @param gameDir the game folder (config/ goes there)
     * @return one line per loaded mod, "id version"
     */
    public static String[] start(String side, String version, String facade, String[] libraries, String modsDir, String gameDir) {
        EnvType env = "server".equals(side) ? EnvType.SERVER : EnvType.CLIENT;
        System.setProperty("fabric.skipMcProvider", "true");
        System.setProperty("fabric.modsFolder", modsDir);
        System.setProperty("fabric.noGui", "true");
        HorizonGameProvider.version = version;
        HorizonGameProvider.facade = Paths.get(facade).toAbsolutePath();
        HorizonGameProvider.gameDir = Paths.get(gameDir).toAbsolutePath();
        List<Path> libs = new ArrayList<>();
        for (String library : libraries) libs.add(Paths.get(library).toAbsolutePath());
        HorizonGameProvider.libraries = libs;
        // Knot may load from the system class path only what is there: the loader, its
        // libraries and this bridge.
        List<Path> parent = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isEmpty()) parent.add(Paths.get(entry).toAbsolutePath().normalize());
        }
        HorizonGameProvider.parentClassPath = parent;

        Knot knot = new Knot(env);
        gameLoader = knot.init(new String[0]);
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        loader.invokeEntrypoints("main", ModInitializer.class, ModInitializer::onInitialize);
        if (env == EnvType.CLIENT) {
            loader.invokeEntrypoints("client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
        } else {
            loader.invokeEntrypoints("server", DedicatedServerModInitializer.class, DedicatedServerModInitializer::onInitializeServer);
        }
        List<String> mods = new ArrayList<>();
        for (ModContainer mod : loader.getAllMods()) {
            mods.add(mod.getMetadata().getId() + " " + mod.getMetadata().getVersion().getFriendlyString());
        }
        Natives.log("Fabric Loader started: " + mods.size() + " mods");
        return mods.toArray(new String[0]);
    }

    /** A game (facade) or mod class, loaded and initialized by Knot, so Mixins apply to it. */
    public static Class<?> gameClass(String name) throws ClassNotFoundException {
        if (gameLoader == null) throw new IllegalStateException("Fabric Loader is not started");
        return Class.forName(name.replace('/', '.'), true, gameLoader);
    }
}
