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
    /**
     * The game's start, around Fabric's entrypoints (Minecraft's constructor at its first
     * Thread.currentThread(): EntrypointPatch): the mods' injections there, before (FabricBoot,
     * just before the main entrypoints) or after (after the client ones).
     */
    public static void gameStart(boolean after) {
        HorizonHooks.scanNow();
        HorizonHooks.fireClient(minecraft, after ? HorizonHooks.START_AFTER : HorizonHooks.START_BEFORE);
    }

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
        // Its textures: named, never uploaded (TextureManagerMixin, HorizonTextures).
        set(Minecraft.class, mc, "textureManager", Natives.allocate(net.minecraft.client.renderer.texture.TextureManager.class));
        // Its skins: each profile's default (SkinManagerMixin).
        set(Minecraft.class, mc, "skinManager", Natives.allocate(net.minecraft.client.resources.SkinManager.class));
        // The in-game GUI's object (screens reach its HUD), made without its constructor.
        var gui = (net.minecraft.client.gui.Gui) Natives.allocate(net.minecraft.client.gui.Gui.class);
        var hud = (net.minecraft.client.gui.Hud) Natives.allocate(net.minecraft.client.gui.Hud.class);
        // What the HUD's parts mods draw over read (HorizonClient.hud): the client, a random
        // (the hearts' jitter).
        set(net.minecraft.client.gui.Hud.class, hud, "minecraft", mc);
        set(net.minecraft.client.gui.Hud.class, hud, "random", net.minecraft.util.RandomSource.create());
        // Its overlays HUD mods ask about (Jade hides under an open tab list or a boss bar):
        // none showing.
        set(net.minecraft.client.gui.Hud.class, hud, "tabList",
                Natives.allocate(net.minecraft.client.gui.components.PlayerTabOverlay.class));
        var bosses = Natives.allocate(net.minecraft.client.gui.components.BossHealthOverlay.class);
        set(net.minecraft.client.gui.components.BossHealthOverlay.class, bosses, "events", new java.util.LinkedHashMap<>());
        set(net.minecraft.client.gui.Hud.class, hud, "bossOverlay", bosses);
        set(net.minecraft.client.gui.Gui.class, gui, "hud", hud);
        set(Minecraft.class, mc, "gui", gui);
        // Screens click through the game mode (handleContainerInput): GameModeClientMixin.
        set(Minecraft.class, mc, "gameMode", Natives.allocate(net.minecraft.client.multiplayer.MultiPlayerGameMode.class));
        // Text boxes' typing area, for a window it has none of (TextInputManagerMixin).
        set(Minecraft.class, mc, "textInputManager", Natives.allocate(com.mojang.blaze3d.platform.TextInputManager.class));
        // Buttons click with a sound: a manager without an engine (SoundManagerMixin).
        set(Minecraft.class, mc, "soundManager", Natives.allocate(net.minecraft.client.sounds.SoundManager.class));
        // A window of Horizon's GUI size (mods' screens lay out by getWindow()'s scaled size and
        // scale: Xaero's settings), with no SDL window under it.
        // The debug screen's entries (the crosshair's part asks whether the axes show): the
        // game folder's, none shown.
        try {
            set(Minecraft.class, mc, "debugEntries", new net.minecraft.client.gui.components.debug.DebugScreenEntryList(
                    mc.gameDirectory, net.minecraft.util.datafix.DataFixers.getDataFixer()));
        } catch (Throwable t) {
            Natives.log("Horizon's client: no debug screen entries: " + t);
        }
        // A game renderer that is only its main camera (CameraMixin: the player's eyes), which
        // HUD mods aim with (Jade).
        var renderer = Natives.allocate(net.minecraft.client.renderer.GameRenderer.class);
        set(net.minecraft.client.renderer.GameRenderer.class, renderer, "mainCamera",
                Natives.allocate(net.minecraft.client.Camera.class));
        // Its render state (mods read the light map's and the options' state from it).
        try {
            set(net.minecraft.client.renderer.GameRenderer.class, renderer, "gameRenderState",
                    new net.minecraft.client.renderer.state.GameRenderState());
        } catch (Throwable t) {
            Natives.log("Horizon's client: no game render state: " + t);
        }
        set(Minecraft.class, mc, "gameRenderer", renderer);
        set(Minecraft.class, mc, "window", Natives.allocate(com.mojang.blaze3d.platform.Window.class));
        windowSize();
        registerCommands();
        // The language manager (mods' translation hooks ask it which language is chosen:
        // Server Translations API's Language.inject), with no resource reloads behind it.
        try {
            String code = mc.options.languageCode == null ? "en_us" : mc.options.languageCode;
            set(Minecraft.class, mc, "languageManager",
                    new net.minecraft.client.resources.language.LanguageManager(mc, code, lang -> { }));
        } catch (Throwable t) {
            Natives.log("Horizon's client: no language manager: " + t);
        }
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
        // /recipes [item] and /uses [item]: the recipe viewer's screen for an item (REI's, as
        // its R and U keys open it over an inventory), or the held item without one.
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> {
            for (String name : new String[] {"recipes", "uses"}) {
                boolean uses = name.equals("uses");
                dispatcher.register(net.minecraft.commands.Commands.literal(name)
                        .executes(c -> HorizonRecipeViewer.open(c.getSource(), null, uses))
                        .then(net.minecraft.commands.Commands.argument("item",
                                        net.minecraft.commands.arguments.item.ItemArgument.item(context))
                                .executes(c -> HorizonRecipeViewer.open(c.getSource(),
                                        net.minecraft.commands.arguments.item.ItemArgument.getItem(c, "item").createItemStack(1), uses))));
            }
        });
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
            // Its texture's name (sprites of the GUI atlas are textures/gui/sprites/: HorizonSprites).
            set(net.minecraft.client.renderer.texture.TextureAtlas.class, atlas, "location",
                    net.minecraft.resources.Identifier.withDefaultNamespace("textures/atlas/gui.png"));
            byId.put(id, make.newInstance(atlas, null));
        }
        Class<?> owner = net.minecraft.client.resources.model.sprite.AtlasManager.class;
        set(owner, manager, "atlasById", byId);
        set(owner, manager, "atlasByTexture", new java.util.HashMap<>());
        set(owner, manager, "spriteLookup", new java.util.HashMap<>());
        return manager;
    }

    /** A sprite that is only its name (and the whole of its texture's UVs). */
    static net.minecraft.client.renderer.texture.TextureAtlasSprite sprite(net.minecraft.resources.Identifier atlas,
                                                                         net.minecraft.resources.Identifier name) {
        try {
            var contents = Natives.allocate(net.minecraft.client.renderer.texture.SpriteContents.class);
            set(net.minecraft.client.renderer.texture.SpriteContents.class, contents, "name", name);
            set(net.minecraft.client.renderer.texture.SpriteContents.class, contents, "width", 16);
            set(net.minecraft.client.renderer.texture.SpriteContents.class, contents, "height", 16);
            set(net.minecraft.client.renderer.texture.SpriteContents.class, contents, "additionalMetadata", java.util.List.of());
            var sprite = Natives.allocate(net.minecraft.client.renderer.texture.TextureAtlasSprite.class);
            Class<?> owner = net.minecraft.client.renderer.texture.TextureAtlasSprite.class;
            set(owner, sprite, "atlasLocation", atlas);
            set(owner, sprite, "contents", contents);
            set(owner, sprite, "u1", 1f);
            set(owner, sprite, "v1", 1f);
            return (net.minecraft.client.renderer.texture.TextureAtlasSprite) sprite;
        } catch (ReflectiveOperationException e) {
            return null;
        }
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

    /**
     * The mouse, at a GUI position (screens that read it themselves rather than from their
     * arguments: REI's recipe view), in the window's pixels (its scale is 3).
     */
    static void mouseAt(double guiX, double guiY) {
        try {
            if (minecraft.mouseHandler == null) {
                var mouse = Natives.allocate(net.minecraft.client.MouseHandler.class);
                set(net.minecraft.client.MouseHandler.class, mouse, "minecraft", minecraft);
                set(Minecraft.class, minecraft, "mouseHandler", mouse);
            }
            set(net.minecraft.client.MouseHandler.class, minecraft.mouseHandler, "xpos", guiX * 3);
            set(net.minecraft.client.MouseHandler.class, minecraft.mouseHandler, "ypos", guiY * 3);
        } catch (ReflectiveOperationException e) {
            Natives.log("Horizon's client: no mouse: " + e);
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
            // Who it is (a screen checks a chunk's owner against it: Chunk Loaders): the
            // server player's id, UUID and profile.
            set(net.minecraft.world.entity.Entity.class, local, "uuid", player.getUUID());
            set(net.minecraft.world.entity.Entity.class, local, "stringUUID", player.getStringUUID());
            set(net.minecraft.world.entity.Entity.class, local, "id", player.getId());
            set(net.minecraft.world.entity.player.Player.class, local, "gameProfile", player.getGameProfile());
            // Its keys (isShiftKeyDown: a collision context's descending): none pressed.
            set(net.minecraft.client.player.LocalPlayer.class, local, "input", new net.minecraft.client.player.ClientInput());
            set(net.minecraft.world.entity.player.Player.class, local, "inventory", player.getInventory());
            // Its profile (its scoreboard name: the team whose name tag rules an entity
            // renderer asks): the server player's.
            set(net.minecraft.world.entity.player.Player.class, local, "gameProfile", player.getGameProfile());
            set(net.minecraft.world.entity.player.Player.class, local, "inventoryMenu", player.inventoryMenu);
            // Its level, which tooltips ask (enabled features): the server player's.
            set(net.minecraft.world.entity.Entity.class, local, "level", player.level());
            // Its synced data (health: isAlive, which a screen's tick asks): the server player's.
            set(net.minecraft.world.entity.Entity.class, local, "entityData", player.getEntityData());
            // What it is in (water over its eyes: the HUD's air bubbles): the server player's.
            set(net.minecraft.world.entity.Entity.class, local, "fluidInteraction",
                    field(net.minecraft.world.entity.Entity.class, player, "fluidInteraction"));
            // Its worn and off-hand items (getItemInHand(OFF_HAND), an item's client use): the
            // server player's.
            set(net.minecraft.world.entity.LivingEntity.class, local, "equipment",
                    field(net.minecraft.world.entity.LivingEntity.class, player, "equipment"));
            // Its attributes (max health, armour: the HUD's hearts) and effects: the server player's.
            for (String f : new String[] {"attributes", "activeEffects"}) {
                set(net.minecraft.world.entity.LivingEntity.class, local, f, field(net.minecraft.world.entity.LivingEntity.class, player, f));
            }
            PLAYERS.put(player.getUUID(), local);
        }
        SERVER_PLAYERS.put(local, player);
        // Its game mode as the client's game mode has it (Farmer's Delight's comfort overlay
        // reads gameMode.getPlayerMode()): the server player's. (Fields: setLocalMode would
        // also tell the player's abilities.)
        if (minecraft.gameMode != null) {
            set(net.minecraft.client.multiplayer.MultiPlayerGameMode.class, minecraft.gameMode, "localPlayerMode", player.gameMode());
            set(net.minecraft.client.multiplayer.MultiPlayerGameMode.class, minecraft.gameMode, "previousLocalPlayerMode", player.gameMode());
        }
        // Its food (what HUD mods read: AppleSkin): the server player's.
        set(net.minecraft.world.entity.player.Player.class, local, "foodData", player.getFoodData());
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
        // Its eyes' height and size (getEyePosition: the camera, Jade's ray; at 0 the ray
        // starts at the feet, inside the block stood on, and reads the one under it).
        set(entity, local, "eyeHeight", player.getEyeHeight());
        set(entity, local, "dimensions", field(entity, player, "dimensions"));
        local.xo = local.xOld = player.getX();
        local.yo = local.yOld = player.getY();
        local.zo = local.zOld = player.getZ();
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
            // A display without a category (a mod's recipe type that answers null) couldn't
            // cross vanilla's network codec, so a real client never sees it: skipped, or the
            // whole book fails to build.
            recipes.listDisplaysForRecipe(holder.id(), entry -> {
                if (entry.category() != null) book.add(entry);
            });
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
        var connection = connection(level);
        if (connection == null || connection.searchTrees() != null) return;
        try {
            var trees = new net.minecraft.client.multiplayer.SessionSearchTrees();
            trees.updateRecipes(book, level);
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, connection, "searchTrees", trees);
        } catch (Throwable t) {
            Natives.log("Horizon's client: no recipe search: " + t);
        }
    }

    /**
     * The client's connection (one for every player): one that never sends, with the server's
     * registries and features (what a client gets as it joins). Mods reach them through it:
     * through the client level's connection (REI's displays, once there is a level), else the
     * game mode's.
     */
    static synchronized net.minecraft.client.multiplayer.ClientPacketListener connection(net.minecraft.world.level.Level level) {
        if (searchConnection != null) return searchConnection;
        try {
            var connection = (net.minecraft.client.multiplayer.ClientPacketListener)
                    Natives.allocate(net.minecraft.client.multiplayer.ClientPacketListener.class);
            var server = HorizonServer.server();
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, connection, "registryAccess",
                    server != null ? server.registryAccess() : level.registryAccess());
            set(net.minecraft.client.multiplayer.ClientPacketListener.class, connection, "enabledFeatures",
                    level.enabledFeatures());
            if (minecraft.gameMode != null) {
                set(net.minecraft.client.multiplayer.MultiPlayerGameMode.class, minecraft.gameMode, "connection", connection);
            }
            searchConnection = connection;
        } catch (Throwable t) {
            Natives.log("Horizon's client: no client connection: " + t);
        }
        return searchConnection;
    }

    /** Runs {@code run} with the player's own client player as Minecraft.player. */
    static void as(net.minecraft.server.level.ServerPlayer player, ThrowingRunnable run) throws Throwable {
        var before = minecraft.player;
        var screenBefore = minecraft.gui.screen();
        var local = localPlayer(player);
        set(Minecraft.class, minecraft, "player", local);
        // The services (profiles by id: Chunk Loaders' owner names): the mirrored server's.
        if (minecraft.services() == null && player.level().getServer() != null) {
            set(Minecraft.class, minecraft, "services", player.level().getServer().services());
        }
        // Their client stands in their dimension's client level (Minecraft.level: what their
        // screens and client code read, Chunk Loaders' grid its loaders), as a client in a world.
        var levelBefore = minecraft.level;
        var localLevel = local.level();
        var level = clientLevel(player.level());
        minecraft.level = level;
        set(net.minecraft.world.entity.Entity.class, local, "level", level);
        // Their open screen is the GUI's (Minecraft.gui.screen()), which mods' client code looks at (a message
        // for the screen that is open: Tom's Storage's terminal's item list).
        var screen = SCREENS.get(player.getUUID());
        if (screen != null) set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", screen);
        try {
            run.run();
        } finally {
            set(Minecraft.class, minecraft, "player", before);
            set(net.minecraft.client.gui.Gui.class, minecraft.gui, "screen", screenBefore);
            minecraft.level = levelBefore;
            set(net.minecraft.world.entity.Entity.class, local, "level", localLevel);
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
        // ClientLifecycleEvents.CLIENT_STARTED: Minecraft's first frame, before any world (Jade
        // finishes registering its tooltip providers there).
        if (!started) {
            started = true;
            reloadClientResources();
            HorizonServer.fireEach("ClientLifecycleEvents.CLIENT_STARTED",
                    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED, l -> l.onClientStarted(minecraft));
        }
        // Each player's game is a client of its own: its tick runs as that player
        // (Minecraft.player, the open screen), with its own keys (bindings). With no player, a
        // client at its title screen.
        var players = HorizonServer.players();
        if (players.isEmpty()) {
            tickOnce();
            return;
        }
        // In a world: each player's client in turn, standing in their level and looking at what
        // they look at (the one client a vanilla game has), ticked, then its HUD drawn.
        for (var player : players) {
            try {
                inWorld(player, () -> withKeys(player.getUUID(), () -> {
                    tickOnce();
                    sendHud(player);
                }));
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                if (HUD_FAILURES.merge("tick", 1, Integer::sum) <= 3) {
                    Natives.log("Horizon's client: a player's client tick failed: " + root);
                    for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
                }
            }
        }
    }

    private static boolean started;

    /**
     * The client's resource reload (Minecraft's constructor starts it, before CLIENT_STARTED),
     * for the mods' own reload listeners only (Fabric's ResourceLoader, CLIENT_RESOURCES: Jade's
     * themes): over vanilla's jar and every mod's assets, run here and now, one listener at a
     * time. Vanilla's own listeners (textures, models, sounds) have nothing to load into.
     */
    @SuppressWarnings("unchecked")
    private static void reloadClientResources() {
        var type = net.minecraft.server.packs.PackType.CLIENT_RESOURCES;
        java.util.List<net.minecraft.server.packs.PackResources> packs = new java.util.ArrayList<>();
        try {
            Class<?> source = net.minecraft.client.resources.ClientPackSource.class;
            var metadata = (net.minecraft.server.packs.resources.ResourceMetadata) field(source, null, "BUILT_IN_METADATA");
            var info = (net.minecraft.server.packs.PackLocationInfo) field(source, null, "VANILLA_PACK_INFO");
            packs.add(new net.minecraft.server.packs.VanillaPackResourcesBuilder().setMetadata(metadata)
                    .exposeNamespace("minecraft", "realms").applyDevelopmentConfig().pushJarResources().build(info).fullResources());
        } catch (Throwable t) {
            Natives.log("Horizon's client: no vanilla resources for the mods' reload: " + t);
        }
        java.util.Collection<net.minecraft.server.packs.resources.PreparableReloadListener> listeners;
        try {
            packs.addAll(net.fabricmc.fabric.impl.resource.pack.ModPackResourcesUtil.getModResourcePacks(
                    net.fabricmc.loader.api.FabricLoader.getInstance(), type, null));
            var loader = net.fabricmc.fabric.impl.resource.ResourceLoaderImpl.get(type);
            listeners = ((java.util.Map<Object, net.minecraft.server.packs.resources.PreparableReloadListener>)
                    field(net.fabricmc.fabric.impl.resource.ResourceLoaderImpl.class, loader, "addedReloaders")).values();
        } catch (Throwable t) {
            Natives.log("Horizon's client: the mods' resources could not be loaded: " + t);
            return;
        }
        var manager = new net.minecraft.server.packs.resources.MultiPackResourceManager(type, packs);
        java.util.concurrent.Executor here = Runnable::run;
        int ran = 0;
        for (var listener : new java.util.ArrayList<>(listeners)) {
            try {
                net.minecraft.server.packs.resources.SimpleReloadInstance.of(manager, java.util.List.of(listener), here, here,
                        java.util.concurrent.CompletableFuture.completedFuture(net.minecraft.util.Unit.INSTANCE)).done().join();
                ran++;
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                Natives.log("Horizon's client: a mod's resource reload (" + listener.getClass().getName() + ") failed: " + root);
            }
        }
        Natives.log("Horizon's client: " + ran + " of the mods' resource reload listeners ran over " + packs.size() + " packs");
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

    // ---- The client in a world: its level, what it looks at, its HUD --------------------------

    /** Each dimension's client level: the mirror's blocks, seen from the client side. */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
            net.minecraft.client.multiplayer.ClientLevel> CLIENT_LEVELS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A client level for a mirrored level (Minecraft.level, which HUD mods read: Jade's block
     * under the crosshair): made without its constructor, as the mirror's levels are, so its
     * blocks, fluids, light and block entities are Horizon's (LevelMixin), isClientSide true.
     * No chunks, entities or renderer of its own.
     */
    static net.minecraft.client.multiplayer.ClientLevel clientLevel(net.minecraft.world.level.Level server) {
        return CLIENT_LEVELS.computeIfAbsent(server.dimension(), key -> {
            var level = HorizonServer.mirror(net.minecraft.client.multiplayer.ClientLevel.class);
            var access = (dev.horizonlab.platform.mixin.LevelAccess) level;
            access.horizon$setDimension(key);
            access.horizon$setClientSide(true);
            access.horizon$setRandom(net.minecraft.util.RandomSource.create());
            access.horizon$setSoundSeedGenerator(net.minecraft.util.RandomSource.create());
            access.horizon$setBlockEntityTickers(new java.util.ArrayList<>());
            access.horizon$setPendingBlockEntityTickers(new java.util.ArrayList<>());
            access.horizon$setRegistryAccess(server.registryAccess());
            access.horizon$setDimensionType(server.dimensionTypeRegistration());
            access.horizon$setDamageSources(server.damageSources());
            // Its client data (ClientboundLoginPacket's: hardcore, difficulty, flat), from the
            // server's; Horizon's world time is in the server's.
            var serverData = server.getLevelData();
            var clientData = new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(
                    server.getDifficulty(), serverData.isHardcore(), false);
            clientData.setGameTime(server.getGameTime());
            access.horizon$setLevelData(clientData);
            Class<?> owner = net.minecraft.client.multiplayer.ClientLevel.class;
            try {
                set(owner, level, "minecraft", minecraft);
                // Its connection (mods read the client's registries through it: REI).
                set(owner, level, "connection", connection(server));
                // Its chunks' storage (an empty chunk beyond Horizon's): the server level's.
                set(net.minecraft.world.level.Level.class, level, "palettedContainerFactory", server.palettedContainerFactory());
                set(owner, level, "clientLevelData", clientData);
                set(owner, level, "players", new java.util.ArrayList<>());
                set(owner, level, "dragonParts", new java.util.ArrayList<>());
                set(owner, level, "worldBorder", new net.minecraft.world.level.border.WorldBorder());
                set(owner, level, "environmentAttributes", server.environmentAttributes());
                set(owner, level, "seaLevel", server.getSeaLevel());
            } catch (ReflectiveOperationException e) {
                Natives.log("Horizon's client: a client level is missing a part: " + e);
            }
            HorizonServer.emptyCollections(level, owner);
            // What mods' injections at the end of its constructors give a level (Chunk
            // Loaders' per-level loaders, the client's copy its screen's grid reads).
            try {
                HorizonHooks.constructed(level);
            } catch (Throwable t) {
                Natives.log("Horizon's client: a client level's constructor injections failed: " + t);
            }
            return level;
        });
    }

    /** A client level's entities (ClientLevelMixin): its mirrored level's. */
    public static net.minecraft.world.level.entity.LevelEntityGetter<net.minecraft.world.entity.Entity> entities(
            net.minecraft.client.multiplayer.ClientLevel level) {
        var all = HorizonServer.entities(HorizonServer.level(level.dimension().identifier().toString()));
        // Not the client's own player's server copy: the client has its own player (LocalPlayer).
        var self = serverPlayer(minecraft.player);
        java.util.function.Predicate<net.minecraft.world.entity.Entity> other = e -> e != self;
        return new net.minecraft.world.level.entity.LevelEntityGetter<>() {
            @Override
            public net.minecraft.world.entity.Entity get(int id) {
                var e = all.get(id);
                return other.test(e) ? e : null;
            }

            @Override
            public net.minecraft.world.entity.Entity get(java.util.UUID id) {
                var e = all.get(id);
                return other.test(e) ? e : null;
            }

            @Override
            public Iterable<net.minecraft.world.entity.Entity> getAll() {
                java.util.List<net.minecraft.world.entity.Entity> out = new java.util.ArrayList<>();
                for (var e : all.getAll()) if (other.test(e)) out.add(e);
                return out;
            }

            @Override
            public <U extends net.minecraft.world.entity.Entity> void get(
                    net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, U> test,
                    net.minecraft.util.AbortableIterationConsumer<U> consumer) {
                all.get(test, e -> other.test(e) ? consumer.accept(e) : net.minecraft.util.Continuation.CONTINUE);
            }

            @Override
            public void get(net.minecraft.world.phys.AABB box, java.util.function.Consumer<net.minecraft.world.entity.Entity> consumer) {
                all.get(box, e -> {
                    if (other.test(e)) consumer.accept(e);
                });
            }

            @Override
            public <U extends net.minecraft.world.entity.Entity> void get(
                    net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, U> test,
                    net.minecraft.world.phys.AABB box, net.minecraft.util.AbortableIterationConsumer<U> consumer) {
                all.get(test, box, e -> other.test(e) ? consumer.accept(e) : net.minecraft.util.Continuation.CONTINUE);
            }
        };
    }

    /**
     * Runs {@code run} as the player's client in a world (as(): their client level), with
     * Minecraft.hitResult what they look at (Minecraft.pick: a block, or a mod's entity nearer).
     */
    static void inWorld(net.minecraft.server.level.ServerPlayer player, ThrowingRunnable run) throws Throwable {
        as(player, () -> {
            var hitBefore = minecraft.hitResult;
            var pickedBefore = minecraft.crosshairPickEntity;
            minecraft.hitResult = pick(player);
            minecraft.crosshairPickEntity = minecraft.hitResult instanceof net.minecraft.world.phys.EntityHitResult e ? e.getEntity() : null;
            try {
                run.run();
            } finally {
                minecraft.hitResult = hitBefore;
                minecraft.crosshairPickEntity = pickedBefore;
            }
        });
    }

    /** What the player looks at (GameRenderer.pick): their block, or a nearer entity of the mirror. */
    static net.minecraft.world.phys.HitResult pick(net.minecraft.server.level.ServerPlayer player) {
        double blockRange = player.blockInteractionRange(), entityRange = player.entityInteractionRange();
        var block = player.pick(blockRange, 1f, false);
        var eye = player.getEyePosition();
        var view = player.getViewVector(1f);
        double range = Math.max(blockRange, entityRange);
        double reach = block.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                ? block.getLocation().distanceToSqr(eye) : range * range;
        var box = player.getBoundingBox().expandTowards(view.scale(range)).inflate(1);
        var entity = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(player, eye, eye.add(view.scale(range)), box,
                e -> e != player && !e.isSpectator() && e.isPickable(), Math.min(reach, entityRange * entityRange));
        return entity != null ? entity : block;
    }

    /** HORIZON_JAVA_DEBUG=hud: each HUD frame's size is logged. */
    private static final boolean DEBUG_HUD = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("hud");
    private static final java.util.Map<String, Integer> HUD_FAILURES = new java.util.concurrent.ConcurrentHashMap<>();
    /** The last HUD sent to each player. */
    private static final java.util.Map<java.util.UUID, String> HUD_SENT = new java.util.concurrent.ConcurrentHashMap<>();
    /** Hud's parts under extractPlayerHealth (the hearts, food, armour, air: AppleSkin's). */
    private static final java.util.Set<String> HEALTH_PARTS = java.util.Set.of(
            "extractPlayerHealth", "extractHearts", "extractHeart", "extractFood", "extractArmor", "extractAirBubbles");

    /** The player's HUD as their client's mods draw it, to them when it changed. */
    private static void sendHud(net.minecraft.server.level.ServerPlayer player) {
        String frame = hud(player).toString();
        if (frame.equals(HUD_SENT.get(player.getUUID()))) return;
        HUD_SENT.put(player.getUUID(), frame);
        Natives.hudFrame(player.getUUID().toString(), frame);
    }

    /**
     * What mods draw on the HUD (Gui's HUD extraction, with vanilla's own parts left out: they are
     * Horizon's): Fabric's HUD elements of mods (Jade's tooltip), then the vanilla parts mods
     * inject into, run with only the mods' drawing kept (AppleSkin's food overlay over the food
     * bar), then mods' handlers at the head and end of the whole HUD.
     */
    static com.google.gson.JsonArray hud(net.minecraft.server.level.ServerPlayer player) {
        HorizonGraphics graphics = new HorizonGraphics(width, height);
        graphics.modsOnly = true;
        graphics.imagesSent = imagesSent(player.getUUID());
        var delta = net.minecraft.client.DeltaTracker.ONE;
        fabricHudElements(graphics, delta);
        Object hud;
        try {
            hud = field(net.minecraft.client.gui.Gui.class, minecraft.gui, "hud");
        } catch (ReflectiveOperationException e) {
            return graphics.ops();
        }
        var hooked = HorizonHooks.hudMethods();
        // Hearts and food show where the player can be hurt (Hud.extractHotbarAndDecorations).
        boolean hurtable = player.gameMode().isSurvival();
        if (hurtable && !java.util.Collections.disjoint(hooked, HEALTH_PARTS)) {
            hudPart("extractPlayerHealth", () -> call(hud, "extractPlayerHealth", graphics));
        }
        for (String method : hooked) {
            if (HEALTH_PARTS.contains(method) || method.equals("extractRenderState")) continue;
            hudPart(method, () -> {
                for (var m : net.minecraft.client.gui.Hud.class.getDeclaredMethods()) {
                    if (!m.getName().equals(method) || m.isSynthetic()) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1 && params[0] == net.minecraft.client.gui.GuiGraphicsExtractor.class) {
                        m.setAccessible(true);
                        m.invoke(hud, graphics);
                    } else if (params.length == 2 && params[0] == net.minecraft.client.gui.GuiGraphicsExtractor.class
                            && params[1] == net.minecraft.client.DeltaTracker.class) {
                        m.setAccessible(true);
                        m.invoke(hud, graphics, delta);
                    }
                }
            });
        }
        if (hooked.contains("extractRenderState")) {
            hudPart("extractRenderState", () -> HorizonHooks.fireHud(hud, "extractRenderState", graphics, delta));
        }
        if (DEBUG_HUD) {
            Natives.log("hud: " + graphics.ops().size() + " ops kept, " + graphics.dropped + " vanilla ones left out; parts "
                    + hooked + "; looking at " + (minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult b
                    ? b.getBlockPos().toShortString() + " " + minecraft.level.getBlockState(b.getBlockPos())
                    : String.valueOf(minecraft.hitResult)));
        }
        return graphics.ops();
    }

    private static void call(Object target, String name, Object... args) throws Throwable {
        for (var m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                m.invoke(target, args);
                return;
            }
        }
    }

    /** One part of the HUD; one failing (each frame) is told a few times and the rest still draw. */
    private static void hudPart(String part, ThrowingRunnable run) {
        try {
            run.run();
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            if (HUD_FAILURES.merge(part, 1, Integer::sum) <= 3) {
                Natives.log("Horizon's client: the HUD's " + part + " failed: " + root);
                StackTraceElement[] frames = root.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 10); f++) Natives.log("    at " + frames[f]);
            }
        }
    }

    /**
     * Fabric's HUD elements that mods added (HudElementRegistry: addFirst, attachElement*,
     * addLast, replaceElement), in the HUD's order; vanilla's own elements are left out.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void fabricHudElements(HorizonGraphics graphics, net.minecraft.client.DeltaTracker delta) {
        java.util.List<Object> roots = new java.util.ArrayList<>();
        Class<?> impl;
        try {
            impl = Class.forName("net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl");
            Object first = field(impl, null, "FIRST");
            var vanilla = (java.util.List<Object>) field(impl, null, "VANILLA_ELEMENT_IDS");
            var byId = (java.util.Map<Object, Object>) field(impl, null, "ROOT_ELEMENTS");
            Object last = field(impl, null, "LAST");
            roots.add(first);
            for (Object id : vanilla) if (byId.get(id) != null) roots.add(byId.get(id));
            for (Object root : byId.values()) if (!roots.contains(root)) roots.add(root);
            if (!roots.contains(last)) roots.add(last);
        } catch (Throwable t) {
            if (HUD_FAILURES.merge("fabric", 1, Integer::sum) == 1) Natives.log("Horizon's client: no Fabric HUD elements: " + t);
            return;
        }
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement none = (g, d) -> {};
        for (Object root : roots) {
            if (root == null) continue;
            java.util.List<Object> layers;
            try {
                var accessor = root.getClass().getDeclaredMethod("layers");
                accessor.setAccessible(true);
                layers = (java.util.List<Object>) accessor.invoke(root);
            } catch (ReflectiveOperationException e) {
                continue;
            }
            for (Object o : new java.util.ArrayList<>(layers)) {
                var layer = (net.fabricmc.fabric.impl.client.rendering.hud.HudLayer) o;
                if (layer.isRemoved()) continue;
                var element = layer.element(none);
                if (element == none) continue;
                int before = graphics.ops().size();
                hudPart(String.valueOf(layer.id()), () -> element.extractRenderState(graphics, delta));
                if (DEBUG_HUD) Natives.log("hud element " + layer.id() + ": " + (graphics.ops().size() - before) + " ops");
            }
        }
    }

    /** What each player's game has of mods' dynamic textures (name to version). */
    private static final java.util.Map<java.util.UUID, java.util.Map<String, Integer>> IMAGES_SENT =
            new java.util.concurrent.ConcurrentHashMap<>();

    static java.util.Map<String, Integer> imagesSent(java.util.UUID player) {
        return IMAGES_SENT.computeIfAbsent(player, p -> new java.util.concurrent.ConcurrentHashMap<>());
    }

    /** One frame of a player's mod screen, as Horizon draws it (HorizonGraphics' operations). */
    public static String frame(java.util.UUID player, int mouseX, int mouseY) {
        var screen = SCREENS.get(player);
        if (screen == null) return null;
        HorizonGraphics graphics = new HorizonGraphics(width, height);
        graphics.imagesSent = imagesSent(player);
        try {
            as(OWNERS.get(player), () -> {
                mouseAt(mouseX, mouseY);
                screen.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, 0);
            });
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
        if (DEBUG_INPUT) Natives.log("screen frame " + (frame.length() > 4000 ? frame.substring(0, 4000) + "..." : frame));
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
        // The window's HUD size (window -2: no screen), which the HUD lays out in.
        if (in.has("window") && in.get("window").getAsInt() == -2) {
            if ("size".equals(in.get("kind").getAsString())) {
                width = in.get("width").getAsInt();
                height = in.get("height").getAsInt();
                windowSize();
            }
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
        double[] last = MOUSE.get(id);
        MOUSE.put(id, new double[] {x, y});
        // A click where the mouse wasn't at the last frame: a frame there first, as the window
        // draws before it clicks (widgets that keep their hover from drawing, SuperMartijn's,
        // take the click only over what they drew as hovered).
        boolean moved = last == null || last[0] != x || last[1] != y;
        if (moved && (kind.equals("press") || kind.equals("release") || kind.equals("scroll"))) {
            frame(id, (int) x, (int) y);
        }
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
