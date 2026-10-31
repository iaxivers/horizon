package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * The headless client's atlas sprites (TextureAtlas.getSprite on its atlases, which hold no
 * textures: TextureAtlasMixin). Each sprite knows its name, its size and its GUI metadata (a
 * nine-slice button's borders) from the mods' and vanilla's PNGs, and has its own square of UV
 * space: sprite k spans u from k to k+1. What is drawn with it (a blit, a nine-slice, a mod's own
 * quads) carries those UVs, from which HorizonGraphics finds the sprite and the part of it.
 */
public final class HorizonSprites {
    private HorizonSprites() {}

    /** A sprite: its texture's resource path (namespace:textures/...png). */
    record Sprite(String texture, TextureAtlasSprite atlasSprite) {}

    private static final Map<String, TextureAtlasSprite> BY_NAME = new ConcurrentHashMap<>();
    private static final List<Sprite> BY_INDEX = java.util.Collections.synchronizedList(new ArrayList<>());

    /** The atlas's sprite named {@code id}: made the first time it is asked for. */
    public static TextureAtlasSprite get(Identifier atlas, Identifier id) {
        String texture = texture(atlas, id);
        return BY_NAME.computeIfAbsent(texture, t -> make(atlas, id, t));
    }

    /** An atlas's sources (assets/{@literal *}/atlases/<name>.json of vanilla and every mod), read once. */
    private static final Map<String, List<com.google.gson.JsonObject>> SOURCES = new ConcurrentHashMap<>();

    private static List<com.google.gson.JsonObject> sources(String atlas) {
        return SOURCES.computeIfAbsent(atlas, name -> {
            List<com.google.gson.JsonObject> out = new ArrayList<>();
            try {
                var files = HorizonSprites.class.getClassLoader().getResources("assets/minecraft/atlases/" + name + ".json");
                while (files.hasMoreElements()) {
                    try (var in = files.nextElement().openStream()) {
                        var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
                        if (json.has("sources")) for (var source : json.getAsJsonArray("sources")) out.add(source.getAsJsonObject());
                    } catch (RuntimeException e) {
                        // A broken atlas file adds nothing.
                    }
                }
            } catch (java.io.IOException e) {
                // No atlas files: the default layout below.
            }
            return out;
        });
    }

    /**
     * The texture of an atlas's sprite, as the atlas's sources lay it out (SpriteSources: a
     * "single" resource is textures/<resource>.png, a "directory" source's sprites are
     * textures/<source>/<path>.png under its prefix); else the GUI atlas's gui/sprites/.
     */
    private static String texture(Identifier atlas, Identifier id) {
        String name = atlas == null ? "gui" : atlas.getPath().replace("textures/atlas/", "").replace(".png", "");
        ClassLoader loader = HorizonSprites.class.getClassLoader();
        for (var source : sources(name)) {
            String type = source.has("type") ? source.get("type").getAsString().replace("minecraft:", "") : "";
            if (type.equals("single")) {
                String resource = source.get("resource").getAsString();
                String sprite = source.has("sprite") ? source.get("sprite").getAsString() : resource;
                if (Identifier.parse(sprite).equals(id)) {
                    Identifier r = Identifier.parse(resource);
                    return r.getNamespace() + ":textures/" + r.getPath() + ".png";
                }
            } else if (type.equals("directory")) {
                String prefix = source.has("prefix") ? source.get("prefix").getAsString() : "";
                String dir = source.get("source").getAsString();
                if (!id.getPath().startsWith(prefix)) continue;
                String candidate = "assets/" + id.getNamespace() + "/textures/" + dir + "/" + id.getPath().substring(prefix.length()) + ".png";
                if (loader.getResource(candidate) != null) {
                    return id.getNamespace() + ":textures/" + dir + "/" + id.getPath().substring(prefix.length()) + ".png";
                }
            }
        }
        boolean gui = name.equals("gui");
        return id.getNamespace() + ":textures/" + (gui ? "gui/sprites/" : "") + id.getPath() + ".png";
    }

    /** The texture of a GUI sprite (as the GUI atlas's sources lay it out). */
    static String guiTexture(Identifier id) {
        return texture(null, id);
    }

    /** The sprite whose UV square holds u (null outside every sprite's). */
    static Sprite at(float u) {
        int k = (int) Math.floor(u);
        synchronized (BY_INDEX) {
            return k >= 0 && k < BY_INDEX.size() ? BY_INDEX.get(k) : null;
        }
    }

    private static TextureAtlasSprite make(Identifier atlas, Identifier id, String texture) {
        try {
            String path = "assets/" + texture.replace(':', '/');
            int width = 16, height = 16;
            try (var in = HorizonSprites.class.getClassLoader().getResourceAsStream(path)) {
                if (in != null) {
                    var image = javax.imageio.ImageIO.read(in);
                    if (image != null) {
                        width = image.getWidth();
                        height = image.getHeight();
                    }
                }
            }
            List<net.minecraft.server.packs.metadata.MetadataSectionType.WithValue<?>> metadata = new ArrayList<>();
            try (var in = HorizonSprites.class.getClassLoader().getResourceAsStream(path + ".mcmeta")) {
                if (in != null) {
                    var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
                    var type = net.minecraft.client.resources.metadata.gui.GuiMetadataSection.TYPE;
                    if (json.has(type.name())) {
                        type.codec().parse(com.mojang.serialization.JsonOps.INSTANCE, json.get(type.name())).result()
                                .ifPresent(v -> metadata.add(new net.minecraft.server.packs.metadata.MetadataSectionType.WithValue<>(type, v)));
                    }
                    // An animated strip: its first frame's height.
                    if (json.has("animation") && height > width) height = width;
                }
            }
            var contents = (SpriteContents) Natives.allocate(SpriteContents.class);
            HorizonClient.set(SpriteContents.class, contents, "name", id);
            HorizonClient.set(SpriteContents.class, contents, "width", width);
            HorizonClient.set(SpriteContents.class, contents, "height", height);
            HorizonClient.set(SpriteContents.class, contents, "additionalMetadata", metadata);
            var sprite = (TextureAtlasSprite) Natives.allocate(TextureAtlasSprite.class);
            int k;
            synchronized (BY_INDEX) {
                k = BY_INDEX.size();
                BY_INDEX.add(new Sprite(texture, sprite));
            }
            Class<?> owner = TextureAtlasSprite.class;
            HorizonClient.set(owner, sprite, "atlasLocation", atlas);
            HorizonClient.set(owner, sprite, "contents", contents);
            HorizonClient.set(owner, sprite, "u0", (float) k);
            HorizonClient.set(owner, sprite, "u1", (float) k + 1);
            HorizonClient.set(owner, sprite, "v0", 0f);
            HorizonClient.set(owner, sprite, "v1", 1f);
            return sprite;
        } catch (ReflectiveOperationException | java.io.IOException e) {
            Natives.log("Horizon's client: the sprite " + id + " could not be made: " + e);
            return null;
        }
    }
}
