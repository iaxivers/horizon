package dev.horizonlab.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2f;

/**
 * What a mod's screen draws, recorded for Horizon to draw: the screen extracts into this as
 * into vanilla's GuiGraphicsExtractor, and each image, sprite, rectangle, text and item becomes
 * an operation (with the pose it was drawn with), in drawing order. Nothing reaches a GPU.
 */
public final class HorizonGraphics extends GuiGraphicsExtractor {
    private final JsonArray ops = new JsonArray();

    public HorizonGraphics(int width, int height) {
        this(width, height, new Capture());
    }

    private HorizonGraphics(int width, int height, Capture state) {
        super(HorizonClient.minecraft(), state, width, height);
        state.owner = this;
    }

    /**
     * The render state GUI elements go to when mods add them themselves (Jade's float
     * rectangles: GuiRenderState.addGuiElement), or through a vanilla method this class doesn't
     * record: their vertices, built as vanilla would build them, become rectangles. Untextured
     * quads are fills (two colours down: gradients); textured ones have no texture name here.
     */
    private static final class Capture extends GuiRenderState {
        HorizonGraphics owner;

        @Override
        public void addGuiElement(net.minecraft.client.renderer.state.gui.GuiElementRenderState element) {
            if (owner == null) return;
            java.util.List<float[]> vertices = new java.util.ArrayList<>();
            try {
                element.buildVertices(new Vertices(vertices));
            } catch (RuntimeException e) {
                return;
            }
            var texture = element.textureSetup();
            boolean textured = texture != null && texture.texure0() != null;
            for (int i = 0; i + 3 < vertices.size(); i += 4) {
                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                int top = 0, bottom = 0;
                for (int k = 0; k < 4; k++) {
                    float[] v = vertices.get(i + k);
                    x0 = Math.min(x0, v[0]);
                    x1 = Math.max(x1, v[0]);
                    if (v[1] < y0) {
                        y0 = v[1];
                        top = (int) v[2];
                    }
                    if (v[1] >= y1) {
                        y1 = v[1];
                        bottom = (int) v[2];
                    }
                }
                if (textured) continue;
                owner.quad(x0, y0, x1, y1, top, bottom);
            }
        }
    }

    /** Collects a GUI element's vertices: x, y (posed) and colour. */
    private record Vertices(java.util.List<float[]> out) implements com.mojang.blaze3d.vertex.VertexConsumer {
        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            out.add(new float[] {x, y, -1});
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            return setColor((a << 24) | (r << 16) | (g << 8) | b);
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int argb) {
            if (!out.isEmpty()) out.get(out.size() - 1)[2] = argb;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    /** A captured rectangle (already posed): a fill, or a gradient from top to bottom. */
    private void quad(float x0, float y0, float x1, float y1, int top, int bottom) {
        pose().pushMatrix();
        pose().identity();
        try {
            JsonObject o = op(top == bottom ? "fill" : "gradient");
            o.addProperty("x0", x0);
            o.addProperty("y0", y0);
            o.addProperty("x1", x1);
            o.addProperty("y1", y1);
            if (top == bottom) {
                o.addProperty("color", top);
            } else {
                o.addProperty("top", top);
                o.addProperty("bottom", bottom);
            }
        } finally {
            pose().popMatrix();
        }
    }

    /** Horizon's GUI size (the screen has no window to ask; read in the constructor too). */
    @Override
    public int guiWidth() {
        return HorizonClient.width;
    }

    @Override
    public int guiHeight() {
        return HorizonClient.height;
    }

    /** The frame's operations, in order. */
    public JsonArray ops() {
        return ops;
    }

    /**
     * Only what mods' code draws is kept (the HUD: vanilla's own parts are Horizon's, drawn by
     * Horizon). An operation is the mods' when, going out from the drawing call, a mod's frame
     * comes before Horizon's driver: a class of a mod, or a mod's mixin handler merged into a
     * vanilla class (Fabric's own are vanilla's here: its HUD wrapper draws vanilla parts).
     */
    boolean modsOnly;
    /** Operations left out as vanilla's (modsOnly). */
    int dropped;

    private static final java.util.Map<String, Boolean> MOD_FRAMES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static boolean drawnByMod() {
        return WALKER.walk(frames -> {
            var it = frames.iterator();
            while (it.hasNext()) {
                var frame = it.next();
                Class<?> owner = frame.getDeclaringClass();
                String name = owner.getName();
                if (name.startsWith("dev.horizonlab.platform.HorizonGraphics")
                        || owner == GuiGraphicsExtractor.class || name.startsWith("net.minecraft.client.gui.ActiveTextCollector")) {
                    continue;
                }
                if (name.startsWith("dev.horizonlab.")) return false;
                Boolean mod = MOD_FRAMES.computeIfAbsent(name + "#" + frame.getMethodName() + frame.getDescriptor(),
                        k -> isModFrame(owner, frame));
                if (mod) return true;
            }
            return false;
        });
    }

    private static boolean isModFrame(Class<?> owner, StackWalker.StackFrame frame) {
        String name = owner.getName();
        boolean platform = name.startsWith("net.minecraft.") || name.startsWith("com.mojang.") || name.startsWith("net.fabricmc.")
                || name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith("org.joml.")
                || name.startsWith("com.google.") || name.startsWith("it.unimi.") || name.startsWith("org.spongepowered.");
        if (!platform) return true;
        if (!frame.getMethodName().contains("$")) return false;
        try {
            var method = owner.getDeclaredMethod(frame.getMethodName(), frame.getMethodType().parameterArray());
            var merged = method.getAnnotation(org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
            return merged != null && !merged.mixin().startsWith("net.fabricmc.") && !merged.mixin().startsWith("dev.horizonlab.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private JsonObject op(String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("op", kind);
        if (modsOnly && !drawnByMod()) {
            dropped++;
            return o;
        }
        Matrix3x2f m = new Matrix3x2f(pose());
        if (!m.equals(new Matrix3x2f())) {
            JsonArray pose = new JsonArray();
            pose.add(m.m00);
            pose.add(m.m01);
            pose.add(m.m10);
            pose.add(m.m11);
            pose.add(m.m20);
            pose.add(m.m21);
            o.add("pose", pose);
        }
        ops.add(o);
        return o;
    }

    private static void rect(JsonObject o, int x0, int y0, int x1, int y1) {
        o.addProperty("x0", x0);
        o.addProperty("y0", y0);
        o.addProperty("x1", x1);
        o.addProperty("y1", y1);
    }

    // ---- Images ---------------------------------------------------------------------------

    private void image(Identifier texture, int x, int y, float u, float v, int w, int h,
                       int uw, int vh, int texW, int texH, int color) {
        JsonObject o = op("blit");
        o.addProperty("texture", texture.toString());
        rect(o, x, y, x + w, y + h);
        o.addProperty("u0", u / texW);
        o.addProperty("u1", (u + uw) / texW);
        o.addProperty("v0", v / texH);
        o.addProperty("v1", (v + vh) / texH);
        if (color != -1) o.addProperty("color", color);
    }

    @Override
    public void blit(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h,
                     int texW, int texH, int color) {
        image(texture, x, y, u, v, w, h, w, h, texW, texH, color);
    }

    @Override
    public void blit(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h,
                     int texW, int texH) {
        image(texture, x, y, u, v, w, h, w, h, texW, texH, -1);
    }

    @Override
    public void blit(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h,
                     int uw, int vh, int texW, int texH) {
        image(texture, x, y, u, v, w, h, uw, vh, texW, texH, -1);
    }

    @Override
    public void blit(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h,
                     int uw, int vh, int texW, int texH, int color) {
        image(texture, x, y, u, v, w, h, uw, vh, texW, texH, color);
    }

    /**
     * Vanilla's private innerBlit(pipeline, texture, x0, x1, y0, y1, u0, u1, v0, v1, colour),
     * which mods widen and call themselves (REI's textured widgets): what a wrapper made by
     * HorizonGraphicsWrappers forwards it to.
     */
    public void horizon$innerBlit(RenderPipeline pipeline, Identifier texture, int x0, int x1, int y0, int y1,
                                  float u0, float u1, float v0, float v1, int color) {
        blit(texture, x0, y0, x1, y1, u0, u1, v0, v1);
        if (color != -1) ops.get(ops.size() - 1).getAsJsonObject().addProperty("color", color);
    }

    @Override
    public void blit(Identifier texture, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1) {
        JsonObject o = op("blit");
        o.addProperty("texture", texture.toString());
        rect(o, x0, y0, x1, y1);
        o.addProperty("u0", u0);
        o.addProperty("u1", u1);
        o.addProperty("v0", v0);
        o.addProperty("v1", v1);
    }

    // ---- GUI atlas sprites (textures/gui/sprites/, with their nine-slice metadata) ----------

    private JsonObject sprite(Identifier sprite, int x, int y, int w, int h) {
        JsonObject o = op("sprite");
        o.addProperty("sprite", sprite.toString());
        rect(o, x, y, x + w, y + h);
        return o;
    }

    @Override
    public void blitSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
        sprite(sprite, x, y, w, h);
    }

    @Override
    public void blitSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, float alpha) {
        sprite(sprite, x, y, w, h).addProperty("alpha", alpha);
    }

    @Override
    public void blitSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int color) {
        sprite(sprite, x, y, w, h).addProperty("color", color);
    }

    /** A part of a sprite: the sprite is texW by texH; (u, v) w by h of it, drawn at x y. */
    @Override
    public void blitSprite(RenderPipeline pipeline, Identifier sprite, int texW, int texH, int u, int v, int x, int y,
                           int w, int h) {
        JsonObject o = sprite(sprite, x, y, w, h);
        o.addProperty("u0", (float) u / texW);
        o.addProperty("u1", (float) (u + w) / texW);
        o.addProperty("v0", (float) v / texH);
        o.addProperty("v1", (float) (v + h) / texH);
    }

    @Override
    public void blitSprite(RenderPipeline pipeline, Identifier sprite, int texW, int texH, int u, int v, int x, int y,
                           int w, int h, int color) {
        blitSprite(pipeline, sprite, texW, texH, u, v, x, y, w, h);
        ops.get(ops.size() - 1).getAsJsonObject().addProperty("color", color);
    }

    /** A sprite mods looked up themselves (an atlas's TextureAtlasSprite): drawn by its name. */
    @Override
    public void blitSprite(RenderPipeline pipeline, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
                           int x, int y, int w, int h) {
        sprite(sprite.contents().name(), x, y, w, h);
    }

    @Override
    public void blitSprite(RenderPipeline pipeline, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
                           int x, int y, int w, int h, int color) {
        sprite(sprite.contents().name(), x, y, w, h).addProperty("color", color);
    }

    // ---- Rectangles -----------------------------------------------------------------------

    @Override
    public void fill(int x0, int y0, int x1, int y1, int color) {
        JsonObject o = op("fill");
        rect(o, Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
        o.addProperty("color", color);
    }

    @Override
    public void fill(RenderPipeline pipeline, int x0, int y0, int x1, int y1, int color) {
        fill(x0, y0, x1, y1, color);
    }

    /**
     * A text box's selection, which vanilla draws by inverting the text over it (a logic-op
     * pipeline): a see-through blue over the text here.
     */
    @Override
    public void textHighlight(int x0, int y0, int x1, int y1, boolean invert) {
        fill(x0, y0, x1, y1, invert ? 0x800000FF : 0x80FFFFFF);
    }

    @Override
    public void fillGradient(int x0, int y0, int x1, int y1, int top, int bottom) {
        JsonObject o = op("gradient");
        rect(o, x0, y0, x1, y1);
        o.addProperty("top", top);
        o.addProperty("bottom", bottom);
    }

    @Override
    public void enableScissor(int x0, int y0, int x1, int y1) {
        super.enableScissor(x0, y0, x1, y1);
        rect(op("scissor"), x0, y0, x1, y1);
    }

    @Override
    public void disableScissor() {
        super.disableScissor();
        op("unscissor");
    }

    // ---- Text -----------------------------------------------------------------------------

    private void text(JsonObject o, int x, int y, int color, boolean shadow) {
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("color", color);
        if (shadow) o.addProperty("shadow", true);
    }

    @Override
    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        if (text == null) return;
        JsonObject o = op("text");
        o.addProperty("text", text);
        text(o, x, y, color, shadow);
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (text == null) return;
        JsonObject o = op("text");
        o.addProperty("json", HorizonServer.json(text));
        text(o, x, y, color, shadow);
    }

    @Override
    public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        if (text == null) return;
        StringBuilder plain = new StringBuilder();
        text.accept((index, style, codePoint) -> {
            plain.appendCodePoint(codePoint);
            return true;
        });
        JsonObject o = op("text");
        o.addProperty("text", plain.toString());
        text(o, x, y, color, shadow);
    }

    /**
     * Widgets' text (a button's label, a list's lines: AbstractWidget's scrolling strings) goes
     * through a text collector, which vanilla's adds to the render state directly: here each
     * line is a text op, in the collector's pose, with its opacity. Long labels scroll as
     * vanilla's do (defaultScrollingHelper), unclipped.
     */
    private final class Collector implements net.minecraft.client.gui.ActiveTextCollector {
        private Parameters parameters = new Parameters(new Matrix3x2f(pose()));

        @Override
        public Parameters defaultParameters() {
            return parameters;
        }

        @Override
        public void defaultParameters(Parameters parameters) {
            this.parameters = parameters;
        }

        @Override
        public void accept(net.minecraft.client.gui.TextAlignment alignment, int x, int y, Parameters at,
                           FormattedCharSequence text) {
            Font font = HorizonClient.minecraft().font;
            int left = alignment.calculateLeft(x, font, text);
            pose().pushMatrix();
            pose().set(at.pose());
            try {
                HorizonGraphics.this.text(font, text, left, y, net.minecraft.util.ARGB.white(at.opacity()), true);
            } finally {
                pose().popMatrix();
            }
        }

        @Override
        public void acceptScrolling(Component text, int centerX, int left, int right, int top, int bottom, Parameters at) {
            Font font = HorizonClient.minecraft().font;
            defaultScrollingHelper(text, centerX, left, right, top, bottom, font.width(text), font.lineHeight, at);
        }
    }

    @Override
    public net.minecraft.client.gui.ActiveTextCollector textRenderer() {
        return new Collector();
    }

    @Override
    public net.minecraft.client.gui.ActiveTextCollector textRenderer(HoveredTextEffects effects) {
        return new Collector();
    }

    @Override
    public net.minecraft.client.gui.ActiveTextCollector textRenderer(HoveredTextEffects effects,
                                                                      java.util.function.Consumer<net.minecraft.network.chat.Style> hovered) {
        return new Collector();
    }

    @Override
    public net.minecraft.client.gui.ActiveTextCollector textRendererForWidget(net.minecraft.client.gui.components.AbstractWidget widget,
                                                                               HoveredTextEffects effects) {
        return new Collector();
    }

    // ---- Items ----------------------------------------------------------------------------

    private void item(String kind, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) return;
        JsonObject o = op(kind);
        o.addProperty("item", HorizonServer.snbt(stack));
        o.addProperty("x", x);
        o.addProperty("y", y);
    }

    @Override
    public void item(ItemStack stack, int x, int y) {
        item("item", stack, x, y);
    }

    @Override
    public void item(ItemStack stack, int x, int y, int seed) {
        item("item", stack, x, y);
    }

    @Override
    public void fakeItem(ItemStack stack, int x, int y) {
        item("item", stack, x, y);
    }

    @Override
    public void fakeItem(ItemStack stack, int x, int y, int seed) {
        item("item", stack, x, y);
    }

    @Override
    public void itemDecorations(Font font, ItemStack stack, int x, int y, String count) {
        if (stack == null || stack.isEmpty()) return;
        JsonObject o = op("decorations");
        o.addProperty("item", HorizonServer.snbt(stack));
        o.addProperty("x", x);
        o.addProperty("y", y);
        if (count != null) o.addProperty("count", count);
    }

    @Override
    public void itemDecorations(Font font, ItemStack stack, int x, int y) {
        itemDecorations(font, stack, x, y, null);
    }

    // ---- Tooltips: one operation, its lines as text components ---------------------------

    private void tooltip(java.util.List<net.minecraft.network.chat.Component> lines, int x, int y) {
        if (lines == null || lines.isEmpty()) return;
        JsonObject o = op("tooltip");
        JsonArray out = new JsonArray();
        for (var line : lines) out.add(HorizonServer.json(line));
        o.add("lines", out);
        o.addProperty("x", x);
        o.addProperty("y", y);
    }

    /** A styled character sequence as a component (its runs of one style). */
    private static Component component(FormattedCharSequence text) {
        var out = Component.empty();
        StringBuilder run = new StringBuilder();
        net.minecraft.network.chat.Style[] style = {null};
        text.accept((index, s, codePoint) -> {
            if (style[0] != null && !s.equals(style[0])) {
                out.append(Component.literal(run.toString()).withStyle(style[0]));
                run.setLength(0);
            }
            style[0] = s;
            run.appendCodePoint(codePoint);
            return true;
        });
        if (style[0] != null) out.append(Component.literal(run.toString()).withStyle(style[0]));
        return out;
    }

    private static java.util.List<Component> components(java.util.List<? extends FormattedCharSequence> lines) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        for (var line : lines) out.add(component(line));
        return out;
    }

    @Override
    public void setTooltipForNextFrame(Font font, ItemStack stack, int x, int y) {
        JsonObject o = op("item_tooltip");
        o.addProperty("item", HorizonServer.snbt(stack));
        o.addProperty("x", x);
        o.addProperty("y", y);
    }

    @Override
    public void setTooltipForNextFrame(Component text, int x, int y) {
        tooltip(java.util.List.of(text), x, y);
    }

    @Override
    public void setTooltipForNextFrame(java.util.List<FormattedCharSequence> lines, int x, int y) {
        tooltip(components(lines), x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<Component> lines,
                                       java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image, int x, int y) {
        tooltip(lines, x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<Component> lines,
                                       java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image, int x, int y,
                                       Identifier style) {
        tooltip(lines, x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<Component> lines,
                                       java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image, int x, int y,
                                       Identifier style, boolean focused) {
        tooltip(lines, x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<FormattedCharSequence> lines,
                                       java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image,
                                       net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
                                       int x, int y, boolean focused, Identifier style) {
        tooltip(components(lines), x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, Component text, int x, int y) {
        tooltip(java.util.List.of(text), x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, Component text, int x, int y, Identifier style) {
        tooltip(java.util.List.of(text), x, y);
    }

    @Override
    public void setComponentTooltipForNextFrame(Font font, java.util.List<Component> lines, int x, int y) {
        tooltip(lines, x, y);
    }

    @Override
    public void setComponentTooltipForNextFrame(Font font, java.util.List<Component> lines, int x, int y, Identifier style) {
        tooltip(lines, x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<? extends FormattedCharSequence> lines, int x, int y) {
        tooltip(components(lines), x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<? extends FormattedCharSequence> lines, int x, int y,
                                       Identifier style) {
        tooltip(components(lines), x, y);
    }

    @Override
    public void setTooltipForNextFrame(Font font, java.util.List<FormattedCharSequence> lines,
                                       net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
                                       int x, int y, boolean focused) {
        tooltip(components(lines), x, y);
    }

    /** A tooltip drawn at once: Horizon draws tooltips (the deferred ones above). */
    @Override
    public void tooltip(Font font, java.util.List<net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent> lines,
                        int x, int y, net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
                        Identifier style, boolean focused) {
    }
}
