package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.io.File;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;

/**
 * Horizon's headless Minecraft client: no window, no GPU. Mods' client code (their client
 * entrypoints, screens) runs against it; what a screen draws is captured for Horizon to draw.
 * Made before the client entrypoints, with what they reach (options, key bindings...).
 */
public final class HorizonClient {
    private HorizonClient() {}

    private static Minecraft minecraft;

    /** Called by FabricBoot on the client side, before the client entrypoints (finish after). */
    public static void boot(String gameDir) throws ReflectiveOperationException {
        Thread.currentThread().setContextClassLoader(HorizonClient.class.getClassLoader());
        Minecraft mc = (Minecraft) Natives.allocate(Minecraft.class);
        set(Minecraft.class, null, "instance", mc);
        minecraft = mc;
        File dir = new File(gameDir);
        set(Minecraft.class, mc, "gameDirectory", dir);
    }

    /**
     * After the client entrypoints, as Minecraft's constructor goes on after Fabric's hook at
     * its head: the options (with the key bindings mods registered).
     */
    public static void finish() throws ReflectiveOperationException {
        Minecraft mc = minecraft;
        set(Minecraft.class, mc, "options", new net.minecraft.client.Options(mc, mc.gameDirectory));
        Natives.log("Horizon's headless client is up");
    }

    public static Minecraft minecraft() {
        return minecraft;
    }

    static void set(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
