package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.lang.reflect.Method;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * A recipe viewer mod's screens (REI's), for {@code /recipes} and {@code /uses}: what its R and U
 * keys open over an inventory, which Horizon draws its own way. Reached by reflection: the
 * viewer is an optional mod.
 *
 * <p>REI's client fills its display registry when its plugins reload, which a real client does
 * as it joins (the recipe packets); the headless client never joins, so the first command
 * reloads them (on the calling thread: REI's own reload thread is turned off for it) once the
 * server's displays are there.
 */
public final class HorizonRecipeViewer {
    private HorizonRecipeViewer() {}

    private static boolean reloaded;

    public static int open(CommandSourceStack source, ItemStack stack, boolean uses) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can open the recipe viewer"));
            return 0;
        }
        ItemStack item = stack != null ? stack : player.getMainHandItem().copyWithCount(1);
        if (item.isEmpty()) {
            source.sendFailure(Component.literal("Hold an item or name one: /" + (uses ? "uses" : "recipes") + " <item>"));
            return 0;
        }
        ClassLoader loader = HorizonRecipeViewer.class.getClassLoader();
        try {
            Class.forName("me.shedaniel.rei.api.client.view.ViewSearchBuilder", false, loader);
        } catch (ClassNotFoundException e) {
            source.sendFailure(Component.literal("No recipe viewer: add Roughly Enough Items to the Java mods"));
            return 0;
        }
        boolean[] opened = new boolean[1];
        int[] displays = new int[1];
        try {
            HorizonClient.as(player, () -> {
                if (!reloaded) reload(loader);
                Class<?> registry = Class.forName("me.shedaniel.rei.api.client.registry.display.DisplayRegistry", true, loader);
                Object instance = registry.getMethod("getInstance").invoke(null);
                displays[0] = (int) registry.getMethod("size").invoke(instance);
                Class<?> entryStacks = Class.forName("me.shedaniel.rei.api.common.util.EntryStacks", true, loader);
                Object entry = entryStacks.getMethod("of", ItemStack.class).invoke(null, item);
                Class<?> builders = Class.forName("me.shedaniel.rei.api.client.view.ViewSearchBuilder", true, loader);
                Object builder = builders.getMethod("builder").invoke(null);
                Class<?> entryStack = Class.forName("me.shedaniel.rei.api.common.entry.EntryStack", true, loader);
                Method add = builders.getMethod(uses ? "addUsagesFor" : "addRecipesFor", entryStack);
                builder = add.invoke(builder, entry);
                opened[0] = (boolean) builders.getMethod("open").invoke(builder);
            });
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's recipe viewer failed: " + root);
            StackTraceElement[] frames = root.getStackTrace();
            for (int f = 0; f < Math.min(frames.length, 20); f++) Natives.log("    at " + frames[f]);
            source.sendFailure(Component.literal("The recipe viewer failed: " + root));
            return 0;
        }
        Natives.log("Horizon's recipe viewer: " + (uses ? "uses" : "recipes") + " of " + item + ": "
                + (opened[0] ? "opened" : "nothing to show") + " (" + displays[0] + " displays)");
        if (!opened[0]) {
            source.sendFailure(Component.literal("No " + (uses ? "uses" : "recipes") + " for " + item.getHoverName().getString()
                    + " (" + displays[0] + " recipes known)"));
            return 0;
        }
        return 1;
    }

    /** REI's plugins, client and common, reloaded at once on this thread. */
    private static void reload(ClassLoader loader) throws ReflectiveOperationException {
        long started = System.nanoTime();
        Class<?> manager = Class.forName("me.shedaniel.rei.impl.common.plugins.ReloadManagerImpl", true, loader);
        Class<?> interruption = Class.forName("me.shedaniel.rei.impl.common.plugins.ReloadInterruptionContext", true, loader);
        Class<?> stage = Class.forName("me.shedaniel.rei.api.common.registry.ReloadStage", true, loader);
        Object never = interruption.getMethod("ofNever").invoke(null);
        // REI reloads its client on a thread of its own when its config says so (the default);
        // the command waits for it instead.
        Method reload = manager.getMethod("reloadPlugins", stage, interruption);
        // The server REI fills its displays from (GameInstance.getServer: on a client, the
        // client's single-player server): the mirror, while the reload runs.
        var mc = net.minecraft.client.Minecraft.getInstance();
        java.lang.reflect.Field single = net.minecraft.client.Minecraft.class.getDeclaredField("singleplayerServer");
        single.setAccessible(true);
        Object singleBefore = single.get(mc);
        if (HorizonServer.server() instanceof net.minecraft.client.server.IntegratedServer integrated) single.set(mc, integrated);
        try {
            reload.invoke(null, null, never);
            Method running = manager.getMethod("countRunningReloadTasks");
            long deadline = System.currentTimeMillis() + 60_000;
            while ((int) running.invoke(null) > 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            single.set(mc, singleBefore);
        }
        // What the server's display sync packet (DisplaySyncPacket, SET) does on a client that
        // joins: the server's displays (crafting, smelting, the mods' machines) into the
        // client's registry, as synced ones.
        Class<?> serverRegistry = Class.forName("me.shedaniel.rei.api.common.registry.display.ServerDisplayRegistry", true, loader);
        Object server = serverRegistry.getMethod("getInstance").invoke(null);
        @SuppressWarnings("unchecked")
        var all = (java.util.Map<Object, java.util.List<Object>>) serverRegistry.getMethod("getAll").invoke(server);
        Class<?> clientImpl = Class.forName("me.shedaniel.rei.impl.client.registry.display.DisplayRegistryImpl", true, loader);
        Object client = Class.forName("me.shedaniel.rei.api.client.registry.display.DisplayRegistry", true, loader)
                .getMethod("getInstance").invoke(null);
        Object synced = clientImpl.getField("SYNCED").get(null);
        clientImpl.getMethod("removeSyncedRecipes").invoke(client);
        Class<?> display = Class.forName("me.shedaniel.rei.api.common.display.Display", true, loader);
        Method add = clientImpl.getMethod("add", display, Object.class);
        int count = 0;
        for (var list : all.values()) {
            for (Object d : list) {
                if ((boolean) add.invoke(client, d, synced)) count++;
            }
        }
        Natives.log("Horizon's recipe viewer: " + count + " of the server's displays synced to REI's client");
        reloaded = true;
        Natives.log("Horizon's recipe viewer: REI's plugins reloaded in " + (System.nanoTime() - started) / 1_000_000 + " ms");
    }
}
