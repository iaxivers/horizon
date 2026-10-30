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
        // What Minecraft's constructor sets before Fabric's client entrypoints run, which
        // mods' client initializers read. Its task queue (Minecraft.execute: a mod's client
        // receiver, or initializer, hands work to the client's thread), which the server
        // thread runs: tasks().
        set(net.minecraft.util.thread.BlockableEventLoop.class, mc, "pendingRunnables",
                new java.util.concurrent.ConcurrentLinkedQueue<Runnable>());
        // The signed-in user (JEI keys its config folders by the profile id): an offline one,
        // as the window's player is to the mirror.
        String name = "Player";
        set(Minecraft.class, mc, "user", new net.minecraft.client.User(name,
                net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name), "", java.util.Optional.empty(), java.util.Optional.empty()));
        HorizonServer.client = true;
    }

    /**
     * After the client entrypoints, as Minecraft's constructor goes on after Fabric's hook at
     * its head: the options (with the key bindings mods registered).
     */
    public static void finish() throws ReflectiveOperationException {
        Minecraft mc = minecraft;
        // options.txt is brought up to date by the game's data fixer, as the server's saves are.
        set(Minecraft.class, mc, "fixerUpper", net.minecraft.util.datafix.DataFixers.getDataFixer());
        set(Minecraft.class, mc, "options", new net.minecraft.client.Options(mc, mc.gameDirectory));
        set(Minecraft.class, mc, "font", font());
        // A mouse, which screens focus their widgets for.
        set(Minecraft.class, mc, "lastInputType", net.minecraft.client.InputType.MOUSE);
        // A narrator that says nothing (Narrator.EMPTY).
        var narrator = (net.minecraft.client.GameNarrator) Natives.allocate(net.minecraft.client.GameNarrator.class);
        set(net.minecraft.client.GameNarrator.class, narrator, "minecraft", mc);
        set(net.minecraft.client.GameNarrator.class, narrator, "narrator", com.mojang.text2speech.Narrator.EMPTY);
        set(Minecraft.class, mc, "narrator", narrator);
        set(Minecraft.class, mc, "atlasManager", atlases());
        // The in-game GUI's object (screens reach its HUD), made without its constructor.
        var gui = (net.minecraft.client.gui.Gui) Natives.allocate(net.minecraft.client.gui.Gui.class);
        set(net.minecraft.client.gui.Gui.class, gui, "hud", Natives.allocate(net.minecraft.client.gui.Hud.class));
        set(Minecraft.class, mc, "gui", gui);
        // Screens click through the game mode (handleContainerInput): GameModeClientMixin.
        set(Minecraft.class, mc, "gameMode", Natives.allocate(net.minecraft.client.multiplayer.MultiPlayerGameMode.class));
        // Text boxes' typing area, for a window it has none of (TextInputManagerMixin).
        set(Minecraft.class, mc, "textInputManager", Natives.allocate(com.mojang.blaze3d.platform.TextInputManager.class));
        // Buttons click with a sound: a manager without an engine (SoundManagerMixin).
        set(Minecraft.class, mc, "soundManager", Natives.allocate(net.minecraft.client.sounds.SoundManager.class));
        Natives.log("Horizon's headless client is up");
    }

    /** Whether this JVM's client is Horizon's headless one. */
    public static boolean headless() {
        return minecraft != null;
    }

    public static Minecraft minecraft() {
        return minecraft;
    }

    static void set(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * The atlases screens' graphics are made with (GuiGraphicsExtractor takes the GUI sprites'):
     * empty ones, without a GPU. Horizon draws sprites from its own atlas, by name.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.minecraft.client.resources.model.sprite.AtlasManager atlases() throws ReflectiveOperationException {
        var manager = (net.minecraft.client.resources.model.sprite.AtlasManager)
                Natives.allocate(net.minecraft.client.resources.model.sprite.AtlasManager.class);
        Class<?> entryClass = Class.forName("net.minecraft.client.resources.model.sprite.AtlasManager$AtlasEntry");
        Class<?> configClass = Class.forName("net.minecraft.client.resources.model.sprite.AtlasManager$AtlasConfig");
        var make = entryClass.getDeclaredConstructor(net.minecraft.client.renderer.texture.TextureAtlas.class, configClass);
        make.setAccessible(true);
        java.util.Map byId = new java.util.HashMap();
        for (var id : new net.minecraft.resources.Identifier[] {net.minecraft.data.AtlasIds.GUI}) {
            var atlas = (net.minecraft.client.renderer.texture.TextureAtlas)
                    Natives.allocate(net.minecraft.client.renderer.texture.TextureAtlas.class);
            byId.put(id, make.newInstance(atlas, null));
        }
        Class<?> owner = net.minecraft.client.resources.model.sprite.AtlasManager.class;
        set(owner, manager, "atlasById", byId);
        set(owner, manager, "atlasByTexture", new java.util.HashMap<>());
        set(owner, manager, "spriteLookup", new java.util.HashMap<>());
        return manager;
    }

    // ---- Text widths ------------------------------------------------------------------------

    /**
     * The font screens measure text with: vanilla's default font's advances (read from its
     * ascii.png as BitmapProvider reads them), 6 for what it lacks. Horizon draws the text.
     */
    static net.minecraft.client.gui.Font font() {
        float[] advances = asciiAdvances();
        net.minecraft.client.gui.font.glyphs.BakedGlyph[] glyphs = new net.minecraft.client.gui.font.glyphs.BakedGlyph[256];
        net.minecraft.client.gui.font.glyphs.BakedGlyph wide = glyph(6);
        for (int i = 0; i < 256; i++) glyphs[i] = glyph(advances[i]);
        net.minecraft.client.gui.GlyphSource source = new net.minecraft.client.gui.GlyphSource() {
            @Override
            public net.minecraft.client.gui.font.glyphs.BakedGlyph getGlyph(int codePoint) {
                return codePoint >= 0 && codePoint < 256 ? glyphs[codePoint] : wide;
            }

            @Override
            public net.minecraft.client.gui.font.glyphs.BakedGlyph getRandomGlyph(net.minecraft.util.RandomSource random, int width) {
                return wide;
            }
        };
        return new net.minecraft.client.gui.Font(new net.minecraft.client.gui.Font.Provider() {
            @Override
            public net.minecraft.client.gui.GlyphSource glyphs(net.minecraft.network.chat.FontDescription description) {
                return source;
            }

            @Override
            public net.minecraft.client.gui.font.glyphs.EffectGlyph effect() {
                return (x0, y0, x1, y1, depth, color, shadow, offset) -> null;
            }
        });
    }

    private static net.minecraft.client.gui.font.glyphs.BakedGlyph glyph(float advance) {
        com.mojang.blaze3d.font.GlyphInfo info = com.mojang.blaze3d.font.GlyphInfo.simple(advance);
        return new net.minecraft.client.gui.font.glyphs.BakedGlyph() {
            @Override
            public com.mojang.blaze3d.font.GlyphInfo info() {
                return info;
            }

            @Override
            public net.minecraft.client.gui.font.TextRenderable.Styled createGlyph(float x, float y, int color, int shadow,
                                                                                net.minecraft.network.chat.Style style, float bold, float offset) {
                return null;
            }
        };
    }

    /** Each character's advance in ascii.png's 16 by 16 grid: its last drawn column, plus one. */
    private static float[] asciiAdvances() {
        float[] out = new float[256];
        java.util.Arrays.fill(out, 6);
        try (var in = Minecraft.class.getClassLoader().getResourceAsStream("assets/minecraft/textures/font/ascii.png")) {
            if (in == null) return out;
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(in);
            int cell = image.getWidth() / 16;
            for (int c = 0; c < 256; c++) {
                int cx = (c % 16) * cell, cy = (c / 16) * cell;
                int last = -1;
                for (int x = cell - 1; x >= 0 && last < 0; x--) {
                    for (int y = 0; y < cell; y++) {
                        if ((image.getRGB(cx + x, cy + y) >>> 24) != 0) {
                            last = x;
                            break;
                        }
                    }
                }
                if (last >= 0) out[c] = (last + 1) * 8f / cell + 1;
            }
        } catch (Exception e) {
            Natives.log("Horizon's client: ascii.png could not be read: " + e);
        }
        out[' '] = 4;
        return out;
    }

    // ---- Mods' screens ----------------------------------------------------------------------

    /** The size Horizon's GUI has (scaled pixels), which screens lay out in. */
    static int width = 427, height = 240;

    private static final java.util.Map<java.util.UUID, net.minecraft.client.gui.screens.Screen> SCREENS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, net.minecraft.client.player.LocalPlayer> PLAYERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The client's own player for a server player (Minecraft.player, which screens read: the
     * recipe book, the open menu): made without its constructor, on the server player's
     * inventory and menus. Each player's screen runs with theirs as Minecraft.player.
     */
    static net.minecraft.client.player.LocalPlayer localPlayer(net.minecraft.server.level.ServerPlayer player)
            throws ReflectiveOperationException {
        var local = PLAYERS.get(player.getUUID());
        if (local == null) {
            local = (net.minecraft.client.player.LocalPlayer) Natives.allocate(net.minecraft.client.player.LocalPlayer.class);
            set(net.minecraft.client.player.LocalPlayer.class, local, "recipeBook", recipeBook(player));
            set(net.minecraft.client.player.LocalPlayer.class, local, "minecraft", minecraft);
            set(net.minecraft.world.entity.player.Player.class, local, "inventory", player.getInventory());
            set(net.minecraft.world.entity.player.Player.class, local, "inventoryMenu", player.inventoryMenu);
            // Its level, which tooltips ask (enabled features): the server player's.
            set(net.minecraft.world.entity.Entity.class, local, "level", player.level());
            // Its synced data (health: isAlive, which a screen's tick asks): the server player's.
            set(net.minecraft.world.entity.Entity.class, local, "entityData", player.getEntityData());
            PLAYERS.put(player.getUUID(), local);
        }
        SERVER_PLAYERS.put(local, player);
        // The client's menu: its own copy where its screen has one, else the server's.
        var client = CLIENT_MENUS.get(player.getUUID());
        local.containerMenu = client != null && player.containerMenu != null
                && client.containerId == player.containerMenu.containerId ? client : player.containerMenu;
        // Its permissions (tooltips' operator warnings): the server player's.
        set(net.minecraft.client.player.LocalPlayer.class, local, "permissions", player.permissions());
        return local;
    }

    /**
     * The client's recipe book (what ClientboundRecipeBookAddPacket fills): every recipe the
     * server shows in recipe books, as Horizon's player unlocks recipes on its side.
     */
    static net.minecraft.client.ClientRecipeBook recipeBook(net.minecraft.server.level.ServerPlayer player) {
        var book = new net.minecraft.client.ClientRecipeBook();
        var recipes = player.level().getServer().getRecipeManager();
        for (var holder : recipes.getRecipes()) {
            recipes.listDisplaysForRecipe(holder.id(), book::add);
        }
        book.rebuildCollections();
        searchTrees(book, player.level());
        return book;
    }

    /** A connection that is only its search trees (the recipe book's search): never sends. */
    private static net.minecraft.client.multiplayer.ClientPacketListener searchConnection;

    public static synchronized net.minecraft.client.multiplayer.ClientPacketListener searchConnection() {
        return searchConnection;
    }

    /** Recipe search, over the recipe book every player's client has (they all know all). */
    private static synchronized void searchTrees(net.minecraft.client.ClientRecipeBook book,
                                                 net.minecraft.world.level.Level level) {
        if (searchConnection != null) return;
        try {
            var connection = (net.minecraft.client.multiplayer.ClientPacketListener)
                    Natives.allocate(net.minecraft.client.multiplayer.ClientPacketListener.class);
            var trees = new net.minecraft.client.multiplayer.SessionSearchTrees();
            trees.updateRecipes(book, level);
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, connection, "searchTrees", trees);
            searchConnection = connection;
        } catch (Throwable t) {
            Natives.log("Horizon's client: no recipe search: " + t);
        }
    }

    /** Runs {@code run} with the player's own client player as Minecraft.player. */
    static void as(net.minecraft.server.level.ServerPlayer player, ThrowingRunnable run) throws Throwable {
        var before = minecraft.player;
        var screenBefore = minecraft.gui.screen();
        set(Minecraft.class, minecraft, "player", localPlayer(player));
        // Their open screen is the GUI's (Minecraft.gui.screen()), which mods' client code looks at (a message
        // for the screen that is open: Tom's Storage's terminal's item list).
        var screen = SCREENS.get(player.getUUID());
        if (screen != null) set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", screen);
        try {
            run.run();
        } finally {
            set(Minecraft.class, minecraft, "player", before);
            set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", screenBefore);
        }
    }

    interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final java.util.Map<java.util.UUID, net.minecraft.server.level.ServerPlayer> OWNERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A mod's menu opened for {@code player}: its own screen (MenuScreens' constructor for the
     * menu's type, which its mod registered in its client code), made on the server's menu
     * itself (the screen reads what the menu holds as it is). False when it has none.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean openScreen(net.minecraft.server.level.ServerPlayer player,
                                     net.minecraft.world.inventory.AbstractContainerMenu menu,
                                     net.minecraft.network.chat.Component title) {
        if (minecraft == null || minecraft.font == null) return false;
        try {
            Field field = net.minecraft.client.gui.screens.MenuScreens.class.getDeclaredField("SCREENS");
            field.setAccessible(true);
            Object constructor = ((java.util.Map) field.get(null)).get(menu.getType());
            if (constructor == null) return false;
            var create = constructor.getClass().getMethod("create",
                    net.minecraft.world.inventory.AbstractContainerMenu.class,
                    net.minecraft.world.entity.player.Inventory.class, net.minecraft.network.chat.Component.class);
            create.setAccessible(true);
            // The client's own menu where its type makes one (MenuType.create, as a client does on
            // the open-screen packet), kept as the server's is (syncClientMenu): a mod whose
            // menu keeps state of its own on each side (Tom's Storage's terminal's item list)
            // needs both. Else (a Fabric extended menu, whose client constructor reads the
            // client's level) the screen shows the server's menu itself.
            net.minecraft.world.inventory.AbstractContainerMenu[] made = {null};
            as(player, () -> made[0] = clientMenu(menu, player));
            var shown = made[0] != null ? made[0] : menu;
            if (made[0] != null) CLIENT_MENUS.put(player.getUUID(), made[0]);
            else CLIENT_MENUS.remove(player.getUUID());
            var screen = (net.minecraft.client.gui.screens.Screen) create.invoke(constructor, shown, player.getInventory(), title);
            as(player, () -> screen.init(width, height));
            SCREENS.put(player.getUUID(), screen);
            OWNERS.put(player.getUUID(), player);
            return true;
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the screen of " + net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(menu.getType())
                    + " could not open: " + root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
            return false;
        }
    }

    public static void closeScreen(java.util.UUID player) {
        CLIENT_MENUS.remove(player);
        SCREENS.remove(player);
        OWNERS.remove(player);
        SENT.remove(player);
        MOUSE.remove(player);
    }

    /** One frame of a player's mod screen, as Horizon draws it (HorizonGraphics' operations). */
    public static String frame(java.util.UUID player, int mouseX, int mouseY) {
        var screen = SCREENS.get(player);
        if (screen == null) return null;
        HorizonGraphics graphics = new HorizonGraphics(width, height);
        try {
            as(OWNERS.get(player), () -> screen.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, 0));
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: a screen failed to draw: " + root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
        }
        var ops = graphics.ops();
        if (typing(screen)) {
            var op = new com.google.gson.JsonObject();
            op.addProperty("op", "typing");
            ops.add(op);
        }
        return ops.toString();
    }

    /**
     * Whether a text box of the screen has the keyboard (its focused widget, or a recipe book's
     * search): then it takes every key, the inventory key's letter too, as vanilla's do.
     */
    static boolean typing(net.minecraft.client.gui.screens.Screen screen) {
        net.minecraft.client.gui.components.events.GuiEventListener at = screen;
        while (at instanceof net.minecraft.client.gui.components.events.ContainerEventHandler parent
                && parent.getFocused() != null) {
            at = parent.getFocused();
        }
        if (at instanceof net.minecraft.client.gui.components.EditBox box && box.isFocused()) return true;
        if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen<?>) {
            try {
                var book = field(net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen.class, screen, "recipeBookComponent");
                var search = field(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class, book, "searchBox");
                return book instanceof net.minecraft.client.gui.screens.recipebook.RecipeBookComponent<?> component
                        && component.isVisible()
                        && search instanceof net.minecraft.client.gui.components.EditBox box && box.isFocused();
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
        return false;
    }

    static Object field(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    // ---- Frames to Horizon, input from it -------------------------------------------------------

    /** Each player's mouse over their screen, in GUI pixels. */
    private static final java.util.Map<java.util.UUID, double[]> MOUSE = new java.util.concurrent.ConcurrentHashMap<>();
    /** The last frame sent to each player. */
    private static final java.util.Map<java.util.UUID, String> SENT = new java.util.concurrent.ConcurrentHashMap<>();
    /** The player whose input runs now (their screen's clicks are theirs). */
    private static net.minecraft.server.level.ServerPlayer acting;

    /** Each open mod screen's frame, to its player when it changed (the server's tick end). */
    public static void sendFrames() {
        tasks();
        for (var id : new java.util.ArrayList<>(SCREENS.keySet())) {
            tickScreen(id);
            send(id);
        }
    }

    private static final java.util.Set<Object> TICK_FAILED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Screen.tick, as the client's tick runs it (a container screen's containerTick). */
    private static void tickScreen(java.util.UUID id) {
        var screen = SCREENS.get(id);
        var player = OWNERS.get(id);
        if (screen == null || player == null) return;
        try {
            syncClientMenu(id);
            as(player, screen::tick);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            // Once a screen: a failing tick fails each tick.
            if (TICK_FAILED.add(screen)) {
                Natives.log("Horizon's client: a screen's tick failed: " + root);
                for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
            }
        }
    }

    private static java.lang.reflect.Method runAllTasks;

    /**
     * The client's thread is the server thread (where Horizon runs every screen and message):
     * what is handed to it there runs at once, and what other threads hand it runs here, each
     * tick, as the client's frame would run it.
     */
    static void tasks() {
        try {
            if (minecraft.getRunningThread() == null) {
                set(Minecraft.class, minecraft, "gameThread", Thread.currentThread());
            }
            if (runAllTasks == null) {
                runAllTasks = net.minecraft.util.thread.BlockableEventLoop.class.getDeclaredMethod("runAllTasks");
                runAllTasks.setAccessible(true);
            }
            runAllTasks.invoke(minecraft);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: a client task failed: " + root);
        }
    }

    private static void send(java.util.UUID id) {
        syncClientMenu(id);
        double[] mouse = MOUSE.getOrDefault(id, new double[] {-1, -1});
        String frame = frame(id, (int) mouse[0], (int) mouse[1]);
        if (frame == null || frame.equals(SENT.get(id))) return;
        SENT.put(id, frame);
        Natives.screenFrame(id.toString(), frame);
    }

    /**
     * Input to a player's mod screen, from Horizon's window (JSON): {@code size} (its GUI
     * width and height), {@code move}, {@code press}, {@code release}, {@code drag}, {@code
     * scroll}, {@code key} and {@code char}, with the mouse in GUI pixels.
     */
    public static void input(String uuid, String json) {
        Thread.currentThread().setContextClassLoader(HorizonClient.class.getClassLoader());
        java.util.UUID id = java.util.UUID.fromString(uuid);
        var screen = SCREENS.get(id);
        var player = OWNERS.get(id);
        if (screen == null || player == null) return;
        var in = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        String kind = in.get("kind").getAsString();
        double x = in.has("x") ? in.get("x").getAsDouble() : MOUSE.getOrDefault(id, new double[2])[0];
        double y = in.has("y") ? in.get("y").getAsDouble() : MOUSE.getOrDefault(id, new double[2])[1];
        int button = in.has("button") ? in.get("button").getAsInt() : 0;
        int mods = in.has("mods") ? in.get("mods").getAsInt() : 0;
        MOUSE.put(id, new double[] {x, y});
        var event = new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(button, mods));
        try {
            acting = player;
            as(player, () -> {
                switch (kind) {
                    case "size" -> {
                        width = in.get("width").getAsInt();
                        height = in.get("height").getAsInt();
                        screen.resize(width, height);
                    }
                    case "move" -> screen.mouseMoved(x, y);
                    case "press" -> screen.mouseClicked(event, in.has("double") && in.get("double").getAsBoolean());
                    case "release" -> screen.mouseReleased(event);
                    case "drag" -> screen.mouseDragged(event,
                            in.has("dx") ? in.get("dx").getAsDouble() : 0, in.has("dy") ? in.get("dy").getAsDouble() : 0);
                    case "scroll" -> screen.mouseScrolled(x, y, in.get("h").getAsDouble(), in.get("v").getAsDouble());
                    case "key" -> screen.keyPressed(new net.minecraft.client.input.KeyEvent(
                            in.get("key").getAsInt(), in.has("keycode") ? in.get("keycode").getAsInt() : in.get("key").getAsInt(), mods));
                    case "char" -> screen.charTyped(new net.minecraft.client.input.CharacterEvent(in.get("char").getAsInt()));
                    default -> {}
                }
            });
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: a screen's " + kind + " failed: " + root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
        } finally {
            acting = null;
        }
        HorizonServer.afterScreenInput();
        send(id);
    }

    /** MultiPlayerGameMode.handleContainerInput from a mod's screen: the server's menu clicked. */
    public static void containerInput(int containerId, int slot, int button, net.minecraft.world.inventory.ContainerInput input) {
        var player = acting;
        if (player == null || player.containerMenu == null || player.containerMenu.containerId != containerId) return;
        var menu = player.containerMenu;
        if (!menu.isValidSlotIndex(slot) && slot != -999 && slot != -1) return;
        if (menu.stillValid(player)) {
            menu.clicked(slot, button, input, player);
            menu.broadcastChanges();
        } else {
            player.closeContainer();
        }
    }

    /** Each player's client menu, where the screen has one of its own (openScreen). */
    private static final java.util.Map<java.util.UUID, net.minecraft.world.inventory.AbstractContainerMenu> CLIENT_MENUS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The client's menu for a server's, made by its type; null when it can't be made apart. */
    private static net.minecraft.world.inventory.AbstractContainerMenu clientMenu(
            net.minecraft.world.inventory.AbstractContainerMenu menu, net.minecraft.server.level.ServerPlayer player) {
        var type = menu.getType();
        // Fabric's extended menus need their opening data (and often the client's level).
        if (type.getClass() != net.minecraft.world.inventory.MenuType.class) return null;
        try {
            var made = type.create(menu.containerId, player.getInventory());
            if (made == null || made.slots.size() != menu.slots.size()) return null;
            syncClientMenu(menu, made);
            return made;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The server's menu as its client copy shows it (the slots, the data slots and the carried
     * stack, as its synchronizer's packets carry them). The player's own inventory is one object
     * on both sides here: its slots are left as they are.
     */
    @SuppressWarnings("unchecked")
    private static void syncClientMenu(net.minecraft.world.inventory.AbstractContainerMenu server,
                                       net.minecraft.world.inventory.AbstractContainerMenu client) {
        int state = server.getStateId();
        for (int i = 0; i < server.slots.size() && i < client.slots.size(); i++) {
            var mine = client.slots.get(i);
            if (mine.container instanceof net.minecraft.world.entity.player.Inventory) continue;
            var want = server.slots.get(i).getItem();
            if (!net.minecraft.world.item.ItemStack.matches(mine.getItem(), want)) client.setItem(i, state, want.copy());
        }
        try {
            Field field = net.minecraft.world.inventory.AbstractContainerMenu.class.getDeclaredField("dataSlots");
            field.setAccessible(true);
            var theirs = (java.util.List<net.minecraft.world.inventory.DataSlot>) field.get(server);
            var ours = (java.util.List<net.minecraft.world.inventory.DataSlot>) field.get(client);
            for (int i = 0; i < theirs.size() && i < ours.size(); i++) {
                int value = theirs.get(i).get();
                if (ours.get(i).get() != value) client.setData(i, value);
            }
        } catch (ReflectiveOperationException e) {
            // No data slots to copy.
        }
        if (!net.minecraft.world.item.ItemStack.matches(client.getCarried(), server.getCarried())) {
            client.setCarried(server.getCarried().copy());
        }
    }

    /** A player's client menu brought up to the server's (before each frame). */
    private static void syncClientMenu(java.util.UUID id) {
        var client = CLIENT_MENUS.get(id);
        var player = OWNERS.get(id);
        if (client == null || player == null) return;
        var server = player.containerMenu;
        if (server == null || server.containerId != client.containerId) return;
        try {
            as(player, () -> syncClientMenu(server, client));
        } catch (Throwable t) {
            Natives.log("Horizon's client: a client menu could not follow its server's: " + t);
        }
    }

    /** Each client player's server player (its game mode, for one). */
    private static final java.util.Map<Object, net.minecraft.server.level.ServerPlayer> SERVER_PLAYERS =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /** A client player's server player (who a mod's client message is from). */
    public static net.minecraft.server.level.ServerPlayer serverPlayer(Object local) {
        return local == null ? null : SERVER_PLAYERS.get(local);
    }

    /** AbstractClientPlayer.gameMode, which reads the tab list's entry: the server player's. */
    public static net.minecraft.world.level.GameType gameMode(Object local) {
        var player = SERVER_PLAYERS.get(local);
        return player == null ? net.minecraft.world.level.GameType.SURVIVAL : player.gameMode();
    }

    /**
     * MultiPlayerGameMode.handlePlaceRecipe, as ServerGamePacketListenerImpl.handlePlaceRecipe
     * answers it: the recipe book's click fills the menu's grid, or shows the recipe as a ghost
     * when the ingredients are missing (ClientboundPlaceGhostRecipePacket, straight to the screen).
     */
    public static void placeRecipe(int containerId, net.minecraft.world.item.crafting.display.RecipeDisplayId id,
                                   boolean useMaxItems) {
        var player = acting;
        if (player == null || player.isSpectator() || player.containerMenu == null
                || player.containerMenu.containerId != containerId) return;
        if (!player.containerMenu.stillValid(player)) {
            player.closeContainer();
            return;
        }
        var info = player.level().getServer().getRecipeManager().getRecipeFromDisplay(id);
        if (info == null) return;
        var holder = info.parent();
        if (!(player.containerMenu instanceof net.minecraft.world.inventory.RecipeBookMenu menu)) return;
        if (holder.value().placementInfo().isImpossibleToPlace()) return;
        var after = menu.handlePlacement(useMaxItems, player.isCreative(), holder, player.level(), player.getInventory());
        menu.broadcastChanges();
        if (after == net.minecraft.world.inventory.RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE
                && SCREENS.get(player.getUUID()) instanceof net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener screen) {
            screen.fillGhostRecipe(info.display().display());
        }
    }

    /** MultiPlayerGameMode.handleInventoryButtonClick: the menu's button (a stonecutter's recipe). */
    public static void buttonClick(int containerId, int button) {
        var player = acting;
        if (player == null || player.containerMenu == null || player.containerMenu.containerId != containerId) return;
        if (player.containerMenu.clickMenuButton(player, button)) player.containerMenu.broadcastChanges();
    }
}
