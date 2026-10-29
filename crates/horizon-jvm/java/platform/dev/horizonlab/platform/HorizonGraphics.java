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
        super(HorizonClient.minecraft(), new GuiRenderState(), width, height);
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

    private JsonObject op(String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("op", kind);
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
