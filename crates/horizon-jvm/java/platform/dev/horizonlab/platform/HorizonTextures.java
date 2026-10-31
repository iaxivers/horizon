package dev.horizonlab.platform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * The headless client's textures (TextureManager.getTexture: TextureManagerMixin): none is
 * uploaded; each is a view that only knows its texture's name and size, so what a mod draws with
 * it (a quad of its own, GuiRenderState.addGuiElement) is a blit of that texture to Horizon.
 */
public final class HorizonTextures {
    private HorizonTextures() {}

    /** A texture view naming its texture. */
    public record View(String name, int width, int height) implements com.mojang.renderpearl.api.textures.GpuTextureView {
        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public com.mojang.renderpearl.api.textures.GpuTexture texture() {
            return null;
        }

        @Override
        public int baseMipLevel() {
            return 0;
        }

        @Override
        public int mipLevels() {
            return 1;
        }

        @Override
        public int getWidth(int mip) {
            return width >> mip;
        }

        @Override
        public int getHeight(int mip) {
            return height >> mip;
        }

        @Override
        public void close() {}
    }

    private static final class Named extends AbstractTexture {
        Named(View view) {
            this.textureView = view;
        }
    }

    private static final Map<Identifier, AbstractTexture> TEXTURES = new ConcurrentHashMap<>();

    /** Textures mods registered under a name (TextureManager.register: their dynamic ones). */
    private static final Map<Identifier, AbstractTexture> REGISTERED = new ConcurrentHashMap<>();

    public static void register(Identifier id, AbstractTexture texture) {
        REGISTERED.put(id, texture);
    }

    public static void release(Identifier id) {
        REGISTERED.remove(id);
    }

    public static AbstractTexture get(Identifier id) {
        var registered = REGISTERED.get(id);
        if (registered != null) return registered;
        return TEXTURES.computeIfAbsent(id, i -> {
            int width = 256, height = 256;
            try (var in = HorizonTextures.class.getClassLoader().getResourceAsStream("assets/" + i.getNamespace() + "/" + i.getPath())) {
                if (in != null) {
                    var image = javax.imageio.ImageIO.read(in);
                    if (image != null) {
                        width = image.getWidth();
                        height = image.getHeight();
                    }
                }
            } catch (java.io.IOException e) {
                // A texture that can't be read keeps a default size.
            }
            return new Named(new View(i.toString(), width, height));
        });
    }

    // ---- Mods' dynamic textures (an image drawn in memory: Chunk Loaders' chunk maps) --------

    private static final Map<String, net.minecraft.client.renderer.texture.DynamicTexture> DYNAMIC = new ConcurrentHashMap<>();
    private static final Map<String, Integer> VERSIONS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger();
    /** The prefix of dynamic textures' names. */
    public static final String DYNAMIC_PREFIX = "horizon:dynamic/";

    /** DynamicTexture.createTexture in the headless client: a named view of its pixels. */
    public static com.mojang.renderpearl.api.textures.GpuTextureView dynamic(net.minecraft.client.renderer.texture.DynamicTexture texture) {
        String name = DYNAMIC_PREFIX + NEXT.getAndIncrement();
        DYNAMIC.put(name, texture);
        VERSIONS.put(name, 1);
        var pixels = texture.getPixels();
        return new View(name, pixels != null ? pixels.getWidth() : 0, pixels != null ? pixels.getHeight() : 0);
    }

    /** DynamicTexture.upload: its pixels changed (they go to Horizon again where drawn). */
    public static void uploaded(net.minecraft.client.renderer.texture.DynamicTexture texture) {
        if (texture.getTextureView() instanceof View view) VERSIONS.merge(view.name(), 1, Integer::sum);
    }

    /** DynamicTexture.close: forgotten. */
    public static void closed(net.minecraft.client.renderer.texture.DynamicTexture texture) {
        if (texture.getTextureView() instanceof View view) {
            DYNAMIC.remove(view.name());
            VERSIONS.remove(view.name());
        }
    }

    /**
     * The image op for a dynamic texture a frame draws, when its receiver hasn't the current
     * pixels yet ({@code sent}: name to version): {@code {"op":"image","name","width","height",
     * "rgba"}} (base64), else null.
     */
    static com.google.gson.JsonObject imageOp(String name, Map<String, Integer> sent) {
        Integer version = VERSIONS.get(name);
        var texture = DYNAMIC.get(name);
        if (version == null || texture == null || sent == null || version.equals(sent.get(name))) return null;
        var pixels = texture.getPixels();
        if (pixels == null) return null;
        int width = pixels.getWidth(), height = pixels.getHeight();
        byte[] rgba = new byte[width * height * 4];
        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = pixels.getPixel(x, y);
                rgba[i++] = (byte) (argb >> 16);
                rgba[i++] = (byte) (argb >> 8);
                rgba[i++] = (byte) argb;
                rgba[i++] = (byte) (argb >>> 24);
            }
        }
        sent.put(name, version);
        var op = new com.google.gson.JsonObject();
        op.addProperty("op", "image");
        op.addProperty("name", name);
        op.addProperty("width", width);
        op.addProperty("height", height);
        op.addProperty("rgba", java.util.Base64.getEncoder().encodeToString(rgba));
        return op;
    }
}
