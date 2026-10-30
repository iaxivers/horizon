package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * Mods' saved data (SavedData: Waystones' index, a mod's world-wide state). Vanilla keeps one
 * storage for the server (MinecraftServer.savedDataStorage, made by its constructor) and one per
 * level (its ServerChunkCache's); the mirror is made without either. Theirs are real
 * SavedDataStorages, in {@code horizon-java/data/} beside the world (the server's) and
 * {@code horizon-java/data/<namespace>/<dimension>/} (a level's), apart from Horizon's own
 * {@code data/} (vanilla's saved data is Horizon's). Saved every 10 s, as block entities are.
 */
public final class HorizonSavedData {
    private static final int SAVE_TICKS = 200;
    private static Path dir;
    private static SavedDataStorage server;
    private static final Map<ServerLevel, SavedDataStorage> LEVELS = new IdentityHashMap<>();
    private static int ticks;

    private HorizonSavedData() {}

    /** The world changed (or was first set): the last world's data saved, new storages made. */
    static synchronized void world(MinecraftServer mirror, Path worldDir) {
        Path now = worldDir == null ? null : worldDir.resolve("horizon-java").resolve("data");
        if (now == null || now.equals(dir)) return;
        save();
        dir = now;
        LEVELS.clear();
        HorizonTickets.reset();
        server = storage(now);
        try {
            java.lang.reflect.Field field = MinecraftServer.class.getDeclaredField("savedDataStorage");
            field.setAccessible(true);
            field.set(mirror, server);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Natives.log("Java mods: the server's saved data could not be set: " + e);
        }
    }

    /** Whether a world's folder is known: its levels' saved data can be read. */
    public static synchronized boolean ready() {
        return dir != null;
    }

    /** ServerLevel.getDataStorage on a mirrored level. */
    public static synchronized SavedDataStorage level(ServerLevel level) {
        if (dir == null) return server;
        return LEVELS.computeIfAbsent(level, l -> {
            var id = l.dimension().identifier();
            return storage(dir.resolve(id.getNamespace()).resolve(id.getPath()));
        });
    }

    private static SavedDataStorage storage(Path folder) {
        var registries = HorizonData.loaded != null
                ? HorizonData.loaded.registries().compositeAccess()
                : net.minecraft.core.RegistryAccess.EMPTY;
        try {
            java.nio.file.Files.createDirectories(folder);
        } catch (java.io.IOException e) {
            Natives.log("Java mods: " + folder + " could not be made: " + e);
        }
        return new SavedDataStorage(folder, net.minecraft.util.datafix.DataFixers.getDataFixer(), registries);
    }

    /** Each server tick: the changed data saved every 10 s. */
    static void tick() {
        if (++ticks % SAVE_TICKS == 0) save();
    }

    static synchronized void save() {
        try {
            if (server != null) server.saveAndJoin();
            for (SavedDataStorage storage : LEVELS.values()) storage.saveAndJoin();
        } catch (Throwable t) {
            Natives.log("Java mods: saved data could not be saved in " + dir + ": " + t);
        }
    }
}
