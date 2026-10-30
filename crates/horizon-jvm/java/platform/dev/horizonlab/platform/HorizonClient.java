package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.io.File;
import java.lang.reflect.Field;
import net.minecraft.client.KeyMapping;
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
        // A window of Horizon's GUI size (mods' screens lay out by getWindow()'s scaled size and
        // scale: Xaero's settings), with no SDL window under it.
        set(Minecraft.class, mc, "window", Natives.allocate(com.mojang.blaze3d.platform.Window.class));
        windowSize();
        registerCommands();
        language(mc.options.languageCode);
        Natives.log("Horizon's headless client is up");
    }

    /**
     * The client's language (LanguageManager's ClientLanguage, from the resource manager the
     * headless client lacks): vanilla's English, then every mod's {@code assets/<ns>/lang/}
     * English and the chosen language over it. Screens' text, which their code resolves here
     * (a mod's "string.naturescompass.selectBiome"), and the server's getString() read it.
     */
    private static void language(String code) {
        java.util.Map<String, String> storage = new java.util.HashMap<>();
        java.util.List<String> codes = code == null || code.equals("en_us") ? java.util.List.of("en_us") : java.util.List.of("en_us", code);
        for (String lang : codes) {
            try (var in = Minecraft.class.getClassLoader().getResourceAsStream("assets/minecraft/lang/" + lang + ".json")) {
                if (in != null) net.minecraft.locale.Language.loadFromJson(in, storage::put);
            } catch (Exception e) {
                Natives.log("Horizon's client: vanilla's " + lang + " could not be read: " + e);
            }
            for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                for (java.nio.file.Path root : mod.getRootPaths()) {
                    java.nio.file.Path assets = root.resolve("assets");
                    if (!java.nio.file.Files.isDirectory(assets)) continue;
                    try (var namespaces = java.nio.file.Files.list(assets)) {
                        for (java.nio.file.Path ns : (Iterable<java.nio.file.Path>) namespaces::iterator) {
                            java.nio.file.Path file = ns.resolve("lang/" + lang + ".json");
                            if (!java.nio.file.Files.isRegularFile(file)) continue;
                            try (var in = java.nio.file.Files.newInputStream(file)) {
                                net.minecraft.locale.Language.loadFromJson(in, storage::put);
                            } catch (Exception e) {
                                Natives.log("Horizon's client: " + mod.getMetadata().getId() + "'s " + file + " could not be read: " + e);
                            }
                        }
                    } catch (java.io.IOException e) {
                        // A mod whose assets can't be listed has no translations for us.
                    }
                }
            }
        }
        try {
            var make = net.minecraft.client.resources.language.ClientLanguage.class.getDeclaredConstructor(java.util.Map.class, boolean.class);
            make.setAccessible(true);
            net.minecraft.locale.Language.inject(make.newInstance(storage, false));
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's client: no language with mods' translations: " + e);
        }
    }

    // ---- Mods' settings screens ---------------------------------------------------------------

    /**
     * {@code /modconfig <mod>}: the mod's settings screen, as Mod Menu's list opens it (its
     * "modmenu" entrypoint's ConfigScreenFactory, with no parent screen), for the player who
     * runs it. Horizon's own menus have no list of Java mods yet; this is the way in.
     */
    private static void registerCommands() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) ->
                dispatcher.register(net.minecraft.commands.Commands.literal("modconfig")
                        .then(net.minecraft.commands.Commands.argument("mod", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(configScreens().keySet(), b))
                                .executes(c -> openConfig(c.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "mod"))))));
    }

    /** The mods with a settings screen, by id: what makes it from a parent screen. */
    static java.util.Map<String, java.util.function.UnaryOperator<net.minecraft.client.gui.screens.Screen>> configScreens() {
        java.util.Map<String, java.util.function.UnaryOperator<net.minecraft.client.gui.screens.Screen>> out = new java.util.TreeMap<>();
        java.lang.reflect.Method create;
        try {
            create = Class.forName("com.terraformersmc.modmenu.api.ConfigScreenFactory", true, HorizonClient.class.getClassLoader())
                    .getMethod("create", net.minecraft.client.gui.screens.Screen.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return out; // No Mod Menu: its entrypoints can't load.
        }
        for (var container : net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class)) {
            String id = container.getProvider().getMetadata().getId();
            try {
                Object api = container.getEntrypoint();
                Object factory = api.getClass().getMethod("getModConfigScreenFactory").invoke(api);
                if (factory != null) out.put(id, parent -> make(create, factory, parent));
                if (api.getClass().getMethod("getProvidedConfigScreenFactories").invoke(api) instanceof java.util.Map<?, ?> provided) {
                    provided.forEach((mod, f) -> out.putIfAbsent(String.valueOf(mod), parent -> make(create, f, parent)));
                }
            } catch (Throwable t) {
                Natives.log("Horizon's client: " + id + "'s settings screen can't be made: " + t);
            }
        }
        return out;
    }

    private static net.minecraft.client.gui.screens.Screen make(java.lang.reflect.Method create, Object factory,
                                                               net.minecraft.client.gui.screens.Screen parent) {
        try {
            return (net.minecraft.client.gui.screens.Screen) create.invoke(factory, parent);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
    }

    private static int openConfig(net.minecraft.commands.CommandSourceStack source, String mod) {
        var player = source.getPlayer();
        var factory = configScreens().get(mod);
        if (player == null || factory == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(player == null
                    ? "Only a player can open a mod's settings"
                    : "No settings screen for " + mod + " (Mod Menu installed? " + String.join(", ", configScreens().keySet()) + ")"));
            return 0;
        }
        try {
            as(player, () -> setScreen(factory.apply(null)));
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: " + mod + "'s settings screen failed: " + root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
            source.sendFailure(net.minecraft.network.chat.Component.literal(mod + "'s settings screen failed: " + root));
            return 0;
        }
        return 1;
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

    /** The headless window's size: Horizon's GUI size, at scale 3 (1280x720's). */
    private static void windowSize() {
        try {
            var window = minecraft.getWindow();
            if (window == null) return;
            Class<?> owner = com.mojang.blaze3d.platform.Window.class;
            int scale = 3;
            for (String f : new String[] {"width", "framebufferWidth", "windowedWidth"}) set(owner, window, f, width * scale);
            for (String f : new String[] {"height", "framebufferHeight", "windowedHeight"}) set(owner, window, f, height * scale);
            set(owner, window, "guiScaledWidth", width);
            set(owner, window, "guiScaledHeight", height);
            set(owner, window, "guiScale", scale);
            set(owner, window, "focused", true);
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's client: the window's size could not be set: " + e);
        }
    }

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
            // Its worn and off-hand items (getItemInHand(OFF_HAND), an item's client use): the
            // server player's.
            set(net.minecraft.world.entity.LivingEntity.class, local, "equipment",
                    field(net.minecraft.world.entity.LivingEntity.class, player, "equipment"));
            PLAYERS.put(player.getUUID(), local);
        }
        SERVER_PLAYERS.put(local, player);
        // The client's menu: its own copy where its screen has one, else the server's.
        var client = CLIENT_MENUS.get(player.getUUID());
        local.containerMenu = client != null && player.containerMenu != null
                && client.containerId == player.containerMenu.containerId ? client : player.containerMenu;
        // Its permissions (tooltips' operator warnings): the server player's.
        set(net.minecraft.client.player.LocalPlayer.class, local, "permissions", player.permissions());
        // Where it is and looks (Nature's Compass sends blockPosition() with its search): the
        // server player's, set as fields (setPosRaw reads the old position, which a player made
        // without its constructor has none of).
        Class<?> entity = net.minecraft.world.entity.Entity.class;
        set(entity, local, "position", player.position());
        set(entity, local, "blockPosition", player.blockPosition());
        set(entity, local, "chunkPosition", player.chunkPosition());
        set(entity, local, "bb", player.getBoundingBox());
        set(entity, local, "yRot", player.getYRot());
        set(entity, local, "xRot", player.getXRot());
        local.yRotO = player.getYRot();
        local.xRotO = player.getXRot();
        local.yHeadRot = player.getYHeadRot();
        local.yBodyRot = player.yBodyRot;
        // Its keys (LocalPlayer.isShiftKeyDown reads them): the sneak key as the server
        // player has it.
        if (local.input == null) local.input = new net.minecraft.client.player.ClientInput();
        local.input.keyPresses = new net.minecraft.world.entity.player.Input(
                false, false, false, false, false, player.isShiftKeyDown(), false);
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
        CLIENT_ONLY.remove(player);
        CLIENT_MENUS.remove(player);
        SCREENS.remove(player);
        OWNERS.remove(player);
        SENT.remove(player);
        MOUSE.remove(player);
    }

    /** Players whose open screen is a client-only one (no menu under it: Gui.setScreen's). */
    private static final java.util.Set<java.util.UUID> CLIENT_ONLY = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Gui.setScreen in the headless client (GuiMixin): a mod's client code opens a screen of its
     * own, with no menu (Nature's Compass's biome list, a config screen, a guide book), or
     * closes it (null). It is the screen of the player whose client runs now (Minecraft.player:
     * their item's use, a message to their client, their screen's input). Horizon's window shows
     * it as a menu's (Natives.clientScreen, then frames).
     */
    public static void setScreen(net.minecraft.client.gui.screens.Screen screen) {
        var player = serverPlayer(minecraft.player);
        if (player == null) {
            if (screen != null) {
                Natives.log("Horizon's client: " + screen.getClass().getName()
                        + " was opened with no player's client running; not shown");
            }
            return;
        }
        java.util.UUID id = player.getUUID();
        var current = SCREENS.get(id);
        if (screen == current) return;
        boolean clientOnly = CLIENT_ONLY.contains(id);
        if (current != null && !clientOnly && screen == null) {
            // A menu's screen closing itself (a Done button): the menu closes, as
            // LocalPlayer.closeContainer has the server do; the window follows.
            player.closeContainer();
            return;
        }
        try {
            if (current != null && clientOnly) current.removed();
            if (screen == null) {
                closeScreen(id);
                Natives.clientScreen(id.toString(), false);
                return;
            }
            // Over a menu's screen (a button that opens a mod's settings), the menu stays open
            // on the server, as vanilla's does until the client says otherwise.
            CLIENT_MENUS.remove(id);
            SCREENS.put(id, screen);
            OWNERS.put(id, player);
            CLIENT_ONLY.add(id);
            SENT.remove(id);
            // Vanilla's order: the GUI's screen is the new one, added, then laid out in the
            // GUI's size. (as() puts the GUI's screen back when the caller's run ends.)
            set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", screen);
            screen.added();
            screen.init(width, height);
            Natives.clientScreen(id.toString(), true);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: " + (screen == null ? "a screen could not close" : screen.getClass().getName()
                    + " could not open") + ": " + root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
            if (screen != null && SCREENS.get(id) == screen) {
                closeScreen(id);
                Natives.clientScreen(id.toString(), false);
            }
        }
    }

    /** A client-only screen shut from outside it (the window closed it): no screen. */
    private static void shut(java.util.UUID id, net.minecraft.server.level.ServerPlayer player) throws Throwable {
        if (!CLIENT_ONLY.contains(id)) return;
        as(player, () -> setScreen(null));
    }

    /** A client player's own message (LocalPlayerMessagesMixin): to its player's game. */
    public static boolean clientMessage(net.minecraft.client.player.LocalPlayer local,
                                        net.minecraft.network.chat.Component message, boolean overlay) {
        var player = SERVER_PLAYERS.get(local);
        if (player == null) return false;
        String json = HorizonServer.chatJson(message);
        if (overlay) Natives.overlay(player.getStringUUID(), json);
        else Natives.tell(player.getStringUUID(), json);
        return true;
    }

    /**
     * The client half of using a mod's block (MultiPlayerGameMode.performUseItemOn, after the
     * server's): Fabric's UseBlockCallback, then the block's useItemOn and, passed to an empty
     * hand, useWithoutItem, on the player's client player with the level answering
     * isClientSide() true. What it opens is the player's screen; what it would change in the
     * world is the client's prediction, dropped (HorizonServer.setBlock): the server's is
     * made. The item's own useOn is left out (a placement predicted on the shared inventory).
     */
    public static void useBlock(net.minecraft.server.level.ServerPlayer player,
                                net.minecraft.world.InteractionHand hand,
                                net.minecraft.world.phys.BlockHitResult hit) {
        if (minecraft == null || minecraft.font == null) return;
        try {
            as(player, () -> {
                var local = minecraft.player;
                var level = local.level();
                HorizonServer.clientHalf = Thread.currentThread();
                try {
                    var event = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT;
                    Object[] listeners = HorizonServer.listeners(event);
                    for (Object listener : listeners != null ? listeners : new Object[] {event.invoker()}) {
                        var r = ((net.fabricmc.fabric.api.event.player.UseBlockCallback) listener).interact(local, level, hand, hit);
                        if (r != net.minecraft.world.InteractionResult.PASS) return;
                    }
                    var state = level.getBlockState(hit.getBlockPos());
                    var stack = local.getItemInHand(hand);
                    boolean secondary = local.isSecondaryUseActive() && !(local.getMainHandItem().isEmpty() && local.getOffhandItem().isEmpty());
                    if (secondary) return;
                    var r = state.useItemOn(stack, level, local, hand, hit);
                    if (r.consumesAction()) return;
                    if (r instanceof net.minecraft.world.InteractionResult.TryEmptyHandInteraction
                            && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                        state.useWithoutItem(level, local, hit);
                    }
                } finally {
                    HorizonServer.clientHalf = null;
                }
            });
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the client side of using a block failed: " + root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
        }
    }

    /**
     * The client half of using an item on nothing (MultiPlayerGameMode.useItem, after the
     * server's): Fabric's UseItemCallback, then the item's own use, on the player's client
     * player, with mirrored levels answering isClientSide() true (LevelMixin). What it opens
     * (Gui.setScreen) is the player's screen.
     */
    public static net.minecraft.world.InteractionResult useItem(net.minecraft.server.level.ServerPlayer player,
                                                                net.minecraft.world.InteractionHand hand) {
        if (minecraft == null || minecraft.font == null) return null;
        net.minecraft.world.InteractionResult[] result = {net.minecraft.world.InteractionResult.PASS};
        try {
            as(player, () -> {
                var local = minecraft.player;
                var level = local.level();
                HorizonServer.clientHalf = Thread.currentThread();
                try {
                    var event = net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT;
                    Object[] listeners = HorizonServer.listeners(event);
                    for (Object listener : listeners != null ? listeners : new Object[] {event.invoker()}) {
                        var r = ((net.fabricmc.fabric.api.event.player.UseItemCallback) listener).interact(local, level, hand);
                        if (r != net.minecraft.world.InteractionResult.PASS) {
                            result[0] = r;
                            return;
                        }
                    }
                    result[0] = local.getItemInHand(hand).use(level, local, hand);
                } finally {
                    HorizonServer.clientHalf = null;
                }
            });
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Natives.log("Horizon's client: the client side of using "
                    + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(player.getItemInHand(hand).getItem())
                    + " failed: " + root);
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
        }
        return result[0];
    }

    // ---- The client's tick ------------------------------------------------------------------

    /**
     * One tick of the headless client, as Minecraft.runTick runs one per frame and
     * Minecraft.tick one per game tick: Fabric's ClientTickEvents (START, END) and mods'
     * injections into runTick and tick (HorizonHooks.fireClient: Xaero's lib finishes loading at
     * runTick's renderFrame). The client has no level and no player of its own (Horizon's window
     * plays), as a client at its title screen: mods' tick code checks for both. Before the
     * server's tick, so the first comes before SERVER_STARTING, as a client's frames come before
     * its integrated server starts.
     */
    public static void clientTick() {
        if (minecraft == null || minecraft.font == null) return;
        Thread.currentThread().setContextClassLoader(HorizonClient.class.getClassLoader());
        tasks();
        // Each player's game is a client of its own: its tick runs as that player
        // (Minecraft.player, the open screen), with its own keys (bindings). With no player, a
        // client at its title screen.
        var players = HorizonServer.players();
        if (players.isEmpty()) {
            tickOnce();
            return;
        }
        for (var player : players) {
            try {
                as(player, () -> withKeys(player.getUUID(), HorizonClient::tickOnce));
            } catch (Throwable t) {
                Natives.log("Horizon's client: a tick as " + player.getScoreboardName() + " failed: " + t);
            }
        }
    }

    private static void tickOnce() {
        HorizonHooks.fireClient(minecraft, "runTick");
        HorizonHooks.fireClient(minecraft, "tick");
        HorizonServer.fireEach("ClientTickEvents.START_CLIENT_TICK",
                net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK, l -> l.onStartTick(minecraft));
        HorizonServer.fireEach("ClientTickEvents.END_CLIENT_TICK",
                net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK, l -> l.onEndTick(minecraft));
    }

    // ---- Mods' key bindings ---------------------------------------------------------------------

    /** Each player's keys: which of the mods' KeyMappings are down, and their clicks not yet consumed. */
    private record Keys(java.util.Set<KeyMapping> down, java.util.Map<KeyMapping, Integer> clicks) {}

    private static final java.util.Map<java.util.UUID, Keys> KEYS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The mods' own KeyMappings (Fabric's KeyMappingHelper registry), vanilla's left to Horizon. */
    @SuppressWarnings("unchecked")
    static java.util.List<KeyMapping> modKeys() {
        try {
            Class<?> registry = Class.forName("net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl");
            Field list = registry.getDeclaredField("MODDED_KEY_BINDINGS");
            list.setAccessible(true);
            return new java.util.ArrayList<>((java.util.List<KeyMapping>) list.get(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return java.util.List.of();
        }
    }

    /** The keyboard keys (26.3 scancodes) the mods' KeyMappings are set to, as a JSON array. */
    public static String boundKeys() {
        Thread.currentThread().setContextClassLoader(HorizonClient.class.getClassLoader());
        var out = new com.google.gson.JsonArray();
        java.util.Set<Integer> seen = new java.util.TreeSet<>();
        for (KeyMapping mapping : modKeys()) {
            var key = key(mapping);
            if (key != null && key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD
                    && key.getValue() >= 0 && seen.add(key.getValue())) {
                out.add(key.getValue());
            }
        }
        return out.toString();
    }

    private static com.mojang.blaze3d.platform.InputConstants.Key key(KeyMapping mapping) {
        try {
            return (com.mojang.blaze3d.platform.InputConstants.Key) field(KeyMapping.class, mapping, "key");
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * A key of a player's game, pressed or released while playing (KeyboardHandler.keyPress:
     * KeyMapping.set, and click on a press), kept for that player's client tick.
     */
    static void bind(java.util.UUID player, int scancode, boolean down) {
        Keys keys = KEYS.computeIfAbsent(player, p -> new Keys(
                java.util.concurrent.ConcurrentHashMap.newKeySet(), new java.util.concurrent.ConcurrentHashMap<>()));
        var pressed = com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD.getOrCreate(scancode);
        for (KeyMapping mapping : modKeys()) {
            if (!pressed.equals(key(mapping))) continue;
            if (down) {
                keys.down().add(mapping);
                keys.clicks().merge(mapping, 1, Integer::sum);
            } else {
                keys.down().remove(mapping);
            }
        }
    }

    /**
     * Runs with the player's keys on the mods' KeyMappings (isDown, clicks; KeyMappings are the
     * client's, one set for every player here), then keeps the clicks the mods didn't consume.
     */
    static void withKeys(java.util.UUID player, ThrowingRunnable run) throws Throwable {
        Keys keys = KEYS.get(player);
        var mappings = modKeys();
        Field clickCount = KeyMapping.class.getDeclaredField("clickCount");
        clickCount.setAccessible(true);
        for (KeyMapping mapping : mappings) {
            mapping.setDown(keys != null && keys.down().contains(mapping));
            clickCount.setInt(mapping, keys == null ? 0 : keys.clicks().getOrDefault(mapping, 0));
        }
        try {
            run.run();
        } finally {
            for (KeyMapping mapping : mappings) {
                int left = clickCount.getInt(mapping);
                if (keys != null) {
                    if (left > 0) keys.clicks().put(mapping, left);
                    else keys.clicks().remove(mapping);
                }
                mapping.setDown(false);
                clickCount.setInt(mapping, 0);
            }
        }
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

    /** HORIZON_JAVA_DEBUG=screens: each input to a mod's screen is logged. */
    private static final boolean DEBUG_INPUT = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("screens");

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
        var in = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        String kind = in.get("kind").getAsString();
        if (kind.equals("bind")) {
            bind(id, in.get("key").getAsInt(), in.get("down").getAsBoolean());
            return;
        }
        var screen = SCREENS.get(id);
        var player = OWNERS.get(id);
        if (screen == null || player == null) return;
        if (DEBUG_INPUT) Natives.log("screen input " + json + " to " + screen.getClass().getName());
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
                        windowSize();
                        screen.resize(width, height);
                    }
                    case "move" -> screen.mouseMoved(x, y);
                    case "press" -> screen.mouseClicked(event, in.has("double") && in.get("double").getAsBoolean());
                    case "release" -> screen.mouseReleased(event);
                    case "drag" -> screen.mouseDragged(event,
                            in.has("dx") ? in.get("dx").getAsDouble() : 0, in.has("dy") ? in.get("dy").getAsDouble() : 0);
                    case "scroll" -> screen.mouseScrolled(x, y, in.get("h").getAsDouble(), in.get("v").getAsDouble());
                    case "key" -> {
                        int key = in.get("key").getAsInt();
                        screen.keyPressed(new net.minecraft.client.input.KeyEvent(
                                key, in.has("keycode") ? in.get("keycode").getAsInt() : key, mods));
                        // Escape (41) on a client-only screen: Screen.keyPressed closes it
                        // (onClose: setScreen(null), or its parent); one that took the key and
                        // stayed, though it closes on Escape, is closed, so the window never
                        // keeps a screen it can't leave.
                        if (key == 41 && CLIENT_ONLY.contains(id) && SCREENS.get(id) == screen && screen.shouldCloseOnEsc()) {
                            setScreen(null);
                        }
                    }
                    // The window left a client-only screen (it closed it itself).
                    case "close" -> shut(id, player);
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
