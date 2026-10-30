package dev.horizonlab.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.horizonlab.bridge.Natives;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Mods' entities as their own renderers draw them, for Horizon to draw. The headless client
 * has no GPU, so each tick each entity's renderer (the one the mod registered, made from an
 * {@code EntityRendererProvider.Context} of the headless client) extracts its render state and
 * submits it into a collector that keeps, for every model submitted, what the GPU would have
 * drawn: the model's part tree (sent once), its parts' poses after {@code setupAnim}, the
 * pose stack's matrix, the texture, tint, light and overlay. Horizon draws those with its own
 * entity renderer (game/java_entities.rs).
 *
 * <p>The snapshot is JSON: {@code {"dims": {dimension: [entity...]}, "models": {key: tree}}};
 * {@code models} holds the trees not sent before (Horizon keeps them). A tree is in the form of
 * Horizon's {@code entity-models.json} (VanillaEntityModelExporter's), its parts named by their
 * path ({@code /body/head}), so names are unique within a model.
 */
public final class HorizonEntityRender {
    private HorizonEntityRender() {}

    private static EntityRendererProvider.Context context;
    private static final Map<EntityType<?>, EntityRenderer<?, ?>> RENDERERS = new IdentityHashMap<>();
    /** Types whose renderer could not be made (logged once). */
    private static final Map<EntityType<?>, Boolean> NO_RENDERER = new IdentityHashMap<>();
    /** A key per model tree (a model's root part), and the trees made since the last snapshot. */
    private static final Map<ModelPart, String> KEYS = new IdentityHashMap<>();
    private static final Map<String, JsonObject> NEW_TREES = new TreeMap<>();
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static Field cubesField;
    private static Field childrenField;
    private static Field setupField;
    private static Field texturesField;

    /**
     * The renderers' context, as Minecraft.onResourceLoadFinished's EntityRenderDispatcher
     * makes it: the models of every layer (vanilla's and those mods registered through
     * Fabric API, which adds them to LayerDefinitions.createRoots), the font and atlases of
     * the headless client. What a GPU-less client lacks (item and block models, equipment
     * assets, skins) is null: renderers that reach for it fail alone.
     */
    private static EntityRendererProvider.Context context() throws ReflectiveOperationException {
        if (context != null) return context;
        var mc = HorizonClient.minecraft();
        var models = new net.minecraft.client.model.geom.EntityModelSet(
                net.minecraft.client.model.geom.LayerDefinitions.createRoots());
        var dispatcher = (net.minecraft.client.renderer.entity.EntityRenderDispatcher)
                Natives.allocate(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class);
        HorizonClient.set(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class, dispatcher, "options", mc.options);
        HorizonClient.set(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class, dispatcher, "font", mc.font);
        HorizonClient.set(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class, dispatcher, "entityModels",
                (java.util.function.Supplier<?>) () -> models);
        var camera = (net.minecraft.client.Camera) Natives.allocate(net.minecraft.client.Camera.class);
        HorizonClient.set(net.minecraft.client.Camera.class, camera, "position", net.minecraft.world.phys.Vec3.ZERO);
        dispatcher.camera = camera;
        // Item models: none (ItemModelResolverMixin), so held and worn items draw nothing.
        var items = new net.minecraft.client.renderer.item.ItemModelResolver(null);
        HorizonClient.set(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class, dispatcher, "itemModelResolver", items);
        context = new EntityRendererProvider.Context(dispatcher, null, items, null, null, models, null,
                mc.getAtlasManager(), mc.font, null, null);
        cubesField = ModelPart.class.getDeclaredField("cubes");
        cubesField.setAccessible(true);
        childrenField = ModelPart.class.getDeclaredField("children");
        childrenField.setAccessible(true);
        setupField = RenderType.class.getDeclaredField("state");
        setupField.setAccessible(true);
        texturesField = net.minecraft.client.renderer.rendertype.RenderSetup.class.getDeclaredField("textures");
        texturesField.setAccessible(true);
        return context;
    }

    /** The renderer the mod registered for this type (EntityRenderers.PROVIDERS), made once. */
    @SuppressWarnings("unchecked")
    private static EntityRenderer<?, ?> renderer(EntityType<?> type) {
        EntityRenderer<?, ?> made = RENDERERS.get(type);
        if (made != null || NO_RENDERER.containsKey(type)) return made;
        try {
            Field field = net.minecraft.client.renderer.entity.EntityRenderers.class.getDeclaredField("PROVIDERS");
            field.setAccessible(true);
            var providers = (Map<EntityType<?>, EntityRendererProvider<?>>) field.get(null);
            EntityRendererProvider<?> provider = providers.get(type);
            if (provider == null) throw new IllegalStateException("no renderer registered");
            made = provider.create(context());
            RENDERERS.put(type, made);
        } catch (Throwable t) {
            NO_RENDERER.put(type, true);
            Natives.log("Java mods: no renderer for " + BuiltInRegistries.ENTITY_TYPE.getKey(type) + ": " + t);
            StackTraceElement[] frames = t.getStackTrace();
            for (int f = 0; f < Math.min(frames.length, 8); f++) Natives.log("    at " + frames[f]);
        }
        return made;
    }

    /** The snapshot of every level's mods' entities (see the class), for Horizon. */
    public static String snapshot() {
        JsonObject dims = new JsonObject();
        for (var e : HorizonEntities.all().entrySet()) {
            ServerLevel level = e.getKey();
            JsonArray list = new JsonArray();
            for (Entity entity : e.getValue()) {
                if (entity.isRemoved()) continue;
                JsonObject o = new JsonObject();
                o.addProperty("id", entity.getId());
                o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                o.addProperty("x", entity.getX());
                o.addProperty("y", entity.getY());
                o.addProperty("z", entity.getZ());
                o.addProperty("w", entity.getBbWidth());
                o.addProperty("h", entity.getBbHeight());
                o.add("models", models(entity));
                list.add(o);
            }
            if (!list.isEmpty()) dims.add(level.dimension().identifier().toString(), list);
        }
        JsonObject out = new JsonObject();
        out.add("dims", dims);
        JsonObject trees = new JsonObject();
        for (var t : NEW_TREES.entrySet()) trees.add(t.getKey(), t.getValue());
        NEW_TREES.clear();
        out.add("models", trees);
        return out.toString();
    }

    /**
     * What the entity's renderer submits (EntityRenderDispatcher.submit without the camera's
     * translation: matrices are relative to the entity's position), at the tick's end.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonArray models(Entity entity) {
        JsonArray out = new JsonArray();
        EntityRenderer renderer = renderer(entity.getType());
        if (renderer == null) return out;
        try {
            EntityRenderState state = renderer.createRenderState(entity, 1.0F);
            PoseStack pose = new PoseStack();
            var offset = renderer.getRenderOffset(state);
            pose.translate(offset.x, offset.y, offset.z);
            CameraRenderState camera = new CameraRenderState();
            camera.orientation = new org.joml.Quaternionf();
            camera.pos = net.minecraft.world.phys.Vec3.ZERO;
            renderer.submit(state, pose, collector(out), camera);
        } catch (Throwable t) {
            String key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " " + t;
            if (FAILURES.merge(key, 1, Integer::sum) == 1) {
                Natives.log("Java mods: drawing " + key);
                StackTraceElement[] frames = t.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
            }
        }
        return out;
    }

    /**
     * A SubmitNodeCollector keeping models (every model submit ends in the abstract
     * {@code submitModel}; the others are its defaults, run as they are). Shadows, name tags,
     * flames, items and the rest are not kept yet.
     */
    private static SubmitNodeCollector collector(JsonArray out) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                switch (method.getName()) {
                    case "order" -> {
                        return proxy;
                    }
                    case "submitModel" -> {
                        if (args.length == 9) model(out, (Model) args[0], args[1], (PoseStack) args[2], (RenderType) args[3],
                                (Integer) args[4], (Integer) args[5], (Integer) args[6], args[7]);
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "toString" -> {
                        return "HorizonEntityRender.collector";
                    }
                    default -> { }
                }
                return null;
            }
        };
        return (SubmitNodeCollector) Proxy.newProxyInstance(HorizonEntityRender.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class}, handler);
    }

    /** One submitted model: posed as ModelFeatureRenderer does (setupAnim), then kept. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void model(JsonArray out, Model model, Object state, PoseStack pose, RenderType type,
                              int light, int overlay, int tint, Object uv) throws ReflectiveOperationException {
        // A sprite of an atlas (UvMapping: signs, banners) is not drawn yet.
        if (uv != null) return;
        String texture = texture(type);
        if (texture == null) return;
        model.setupAnim(state);
        ModelPart root = model.root();
        String key = KEYS.get(root);
        if (key == null) {
            key = "m" + KEYS.size();
            KEYS.put(root, key);
            NEW_TREES.put(key, tree(root));
        }
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("tex", texture);
        JsonArray matrix = new JsonArray();
        float[] m = new float[16];
        pose.last().pose().get(m);
        for (float f : m) matrix.add(f);
        o.add("m", matrix);
        o.addProperty("tint", tint);
        o.addProperty("overlay", overlay);
        o.addProperty("light", light);
        JsonObject poses = new JsonObject();
        JsonArray hidden = new JsonArray();
        JsonArray skipped = new JsonArray();
        poses(root, "root", poses, hidden, skipped);
        o.add("poses", poses);
        if (!hidden.isEmpty()) o.add("hidden", hidden);
        if (!skipped.isEmpty()) o.add("skip", skipped);
        out.add(o);
    }

    /** The texture a render type samples first (its Sampler0: the entity's texture). */
    @SuppressWarnings("unchecked")
    private static String texture(RenderType type) throws ReflectiveOperationException {
        Object setup = setupField.get(type);
        var textures = (Map<String, Object>) texturesField.get(setup);
        Object binding = textures.get("Sampler0");
        if (binding == null && !textures.isEmpty()) binding = textures.values().iterator().next();
        if (binding == null) return null;
        Method location = binding.getClass().getDeclaredMethod("location");
        location.setAccessible(true);
        return location.invoke(binding).toString();
    }

    private static float[] pose(ModelPart p) {
        return new float[] {p.x, p.y, p.z, p.xRot, p.yRot, p.zRot, p.xScale, p.yScale, p.zScale};
    }

    private static float[] pose(PartPose p) {
        return new float[] {p.x(), p.y(), p.z(), p.xRot(), p.yRot(), p.zRot(), p.xScale(), p.yScale(), p.zScale()};
    }

    /** Each part's pose that differs from its initial one, its hidden and skipped parts. */
    @SuppressWarnings("unchecked")
    private static void poses(ModelPart part, String path, JsonObject poses, JsonArray hidden, JsonArray skipped)
            throws ReflectiveOperationException {
        if (!part.visible) {
            hidden.add(path);
            return;
        }
        if (part.skipDraw) skipped.add(path);
        float[] now = pose(part);
        if (!java.util.Arrays.equals(now, pose(part.getInitialPose()))) {
            JsonArray a = new JsonArray();
            for (float f : now) a.add(f);
            poses.add(path, a);
        }
        String base = path.equals("root") ? "" : path;
        for (var child : ((Map<String, ModelPart>) childrenField.get(part)).entrySet()) {
            poses(child.getValue(), base + "/" + child.getKey(), poses, hidden, skipped);
        }
    }

    /** A part tree as VanillaEntityModelExporter writes one: initial poses, polygons, children. */
    @SuppressWarnings("unchecked")
    private static JsonObject tree(ModelPart part) throws ReflectiveOperationException {
        return tree(part, "");
    }

    @SuppressWarnings("unchecked")
    private static JsonObject tree(ModelPart part, String path) throws ReflectiveOperationException {
        JsonObject o = new JsonObject();
        JsonArray pose = new JsonArray();
        for (float f : pose(part.getInitialPose())) pose.add(f);
        o.add("pose", pose);
        JsonArray polygons = new JsonArray();
        JsonArray normals = new JsonArray();
        for (ModelPart.Cube cube : (List<ModelPart.Cube>) cubesField.get(part)) {
            for (ModelPart.Polygon polygon : cube.polygons) {
                JsonArray p = new JsonArray();
                for (ModelPart.Vertex v : polygon.vertices()) {
                    JsonArray vertex = new JsonArray();
                    // Degenerate (zero-size) faces have NaN UVs: written as 0.
                    for (float f : new float[] {v.x(), v.y(), v.z(), v.u(), v.v()}) vertex.add(Float.isFinite(f) ? f : 0.0F);
                    p.add(vertex);
                }
                polygons.add(p);
                JsonArray normal = new JsonArray();
                normal.add(polygon.normal().x());
                normal.add(polygon.normal().y());
                normal.add(polygon.normal().z());
                normals.add(normal);
            }
        }
        o.add("polygons", polygons);
        o.add("normals", normals);
        JsonObject kids = new JsonObject();
        for (var child : ((Map<String, ModelPart>) childrenField.get(part)).entrySet()) {
            String childPath = path + "/" + child.getKey();
            kids.add(childPath, tree(child.getValue(), childPath));
        }
        o.add("children", kids);
        return o;
    }
}
