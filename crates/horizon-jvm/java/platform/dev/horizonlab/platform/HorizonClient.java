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
            set(net.minecraft.client.player.LocalPlayer.class, local, "recipeBook", new net.minecraft.client.ClientRecipeBook());
            set(net.minecraft.client.player.LocalPlayer.class, local, "minecraft", minecraft);
            set(net.minecraft.world.entity.player.Player.class, local, "inventory", player.getInventory());
            set(net.minecraft.world.entity.player.Player.class, local, "inventoryMenu", player.inventoryMenu);
            // Its level, which tooltips ask (enabled features): the server player's.
            set(net.minecraft.world.entity.Entity.class, local, "level", player.level());
            PLAYERS.put(player.getUUID(), local);
        }
        local.containerMenu = player.containerMenu;
        // Its permissions (tooltips' operator warnings): the server player's.
        set(net.minecraft.client.player.LocalPlayer.class, local, "permissions", player.permissions());
        return local;
    }

    /** Runs {@code run} with the player's own client player as Minecraft.player. */
    static void as(net.minecraft.server.level.ServerPlayer player, ThrowingRunnable run) throws Throwable {
        var before = minecraft.player;
        set(Minecraft.class, minecraft, "player", localPlayer(player));
        try {
            run.run();
        } finally {
            set(Minecraft.class, minecraft, "player", before);
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
            var screen = (net.minecraft.client.gui.screens.Screen) create.invoke(constructor, menu, player.getInventory(), title);
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
        return graphics.ops().toString();
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
        for (var id : new java.util.ArrayList<>(SCREENS.keySet())) send(id);
    }

    private static void send(java.util.UUID id) {
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
                            in.get("key").getAsInt(), in.get("scancode").getAsInt(), mods));
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

    /** MultiPlayerGameMode.handleInventoryButtonClick: the menu's button (a stonecutter's recipe). */
    public static void buttonClick(int containerId, int button) {
        var player = acting;
        if (player == null || player.containerMenu == null || player.containerMenu.containerId != containerId) return;
        if (player.containerMenu.clickMenuButton(player, button)) player.containerMenu.broadcastChanges();
    }
}
