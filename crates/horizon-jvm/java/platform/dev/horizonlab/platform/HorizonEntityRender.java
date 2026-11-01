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

    // ---- Block entities -------------------------------------------------------------------

    /** How far from a player a block entity is drawn (BlockEntityRenderer.getViewDistance's default). */
    private static final int BLOCK_RANGE = 64;
    private static net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context blockContext;
    private static final Map<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.client.renderer.blockentity.BlockEntityRenderer<?, ?>> BLOCK_RENDERERS = new IdentityHashMap<>();
    private static final Map<net.minecraft.world.level.block.entity.BlockEntityType<?>, Boolean> NO_BLOCK_RENDERER =
            new IdentityHashMap<>();
    /** A stable id a block entity's position (negative: never an entity's), for Horizon's tweening. */
    private static final Map<Long, Integer> BLOCK_IDS = new java.util.HashMap<>();

    /**
     * The block entity renderers' context, as BlockEntityRenderDispatcher makes it on a
     * resource reload: the entity renderers' item resolver and model set, the headless
     * client's font; no block models or sprites (renderers that need them fail alone).
     */
    private static net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context blockContext()
            throws ReflectiveOperationException {
        if (blockContext != null) return blockContext;
        var entities = context();
        var dispatcher = (net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher)
                Natives.allocate(net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher.class);
        blockContext = new net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context(
                dispatcher, null, entities.getItemModelResolver(), entities.getEntityRenderDispatcher(),
                entities.getModelSet(), entities.getFont(), null, null);
        return blockContext;
    }

    /** The renderer the mod registered for a block entity type (BlockEntityRenderers.PROVIDERS). */
    @SuppressWarnings("unchecked")
    private static net.minecraft.client.renderer.blockentity.BlockEntityRenderer<?, ?> blockRenderer(
            net.minecraft.world.level.block.entity.BlockEntityType<?> type) {
        var made = BLOCK_RENDERERS.get(type);
        if (made != null || NO_BLOCK_RENDERER.containsKey(type)) return made;
        try {
            Field field = net.minecraft.client.renderer.blockentity.BlockEntityRenderers.class.getDeclaredField("PROVIDERS");
            field.setAccessible(true);
            var providers = (Map<net.minecraft.world.level.block.entity.BlockEntityType<?>,
                    net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider<?, ?>>) field.get(null);
            var provider = providers.get(type);
            if (provider == null) {
                // Most block entities draw only their block's model: Horizon's already.
                NO_BLOCK_RENDERER.put(type, true);
                return null;
            }
            made = provider.create(blockContext());
            BLOCK_RENDERERS.put(type, made);
        } catch (Throwable t) {
            NO_BLOCK_RENDERER.put(type, true);
            Natives.log("Java mods: no renderer for " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type) + ": " + t);
            StackTraceElement[] frames = t.getStackTrace();
            for (int f = 0; f < Math.min(frames.length, 8); f++) Natives.log("    at " + frames[f]);
        }
        return made;
    }

    /** A level's mod block entities near its players, as their renderers submit them. */
    private static JsonArray blockEntities(ServerLevel level) {
        JsonArray out = new JsonArray();
        var players = level.players();
        if (players.isEmpty()) return out;
        for (var be : HorizonBlockEntities.live(level)) {
            if (be.isRemoved()) continue;
            var pos = be.getBlockPos();
            boolean near = false;
            for (var p : players) {
                if (p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < BLOCK_RANGE * BLOCK_RANGE) {
                    near = true;
                    break;
                }
            }
            if (!near) continue;
            var renderer = blockRenderer(be.getType());
            if (renderer == null) continue;
            JsonArray itemsHere = new JsonArray();
            JsonArray models = blockModels(renderer, be, itemsHere);
            if (models.isEmpty() && itemsHere.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", BLOCK_IDS.computeIfAbsent(pos.asLong(), k -> -1 - BLOCK_IDS.size()));
            o.addProperty("type", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
            o.addProperty("block", true);
            o.addProperty("x", pos.getX());
            o.addProperty("y", pos.getY());
            o.addProperty("z", pos.getZ());
            o.add("models", models);
            if (!itemsHere.isEmpty()) o.add("items", itemsHere);
            out.add(o);
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonArray blockModels(net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer,
                                         net.minecraft.world.level.block.entity.BlockEntity be, JsonArray heldItems) {
        JsonArray out = new JsonArray();
        items = heldItems;
        try {
            var state = renderer.createRenderState();
            renderer.extractRenderState(be, state, 1.0F, net.minecraft.world.phys.Vec3.ZERO, null);
            CameraRenderState camera = new CameraRenderState();
            camera.orientation = new org.joml.Quaternionf();
            camera.pos = net.minecraft.world.phys.Vec3.ZERO;
            renderer.submit(state, new PoseStack(), collector(out), camera);
        } catch (Throwable t) {
            String key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()) + " " + t;
            if (FAILURES.merge(key, 1, Integer::sum) == 1) {
                Natives.log("Java mods: drawing " + key);
                StackTraceElement[] frames = t.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 12); f++) Natives.log("    at " + frames[f]);
            }
        } finally {
            items = null;
        }
        return out;
    }

    /** Entities drawn at least once (a resting one's look is then kept by Horizon). */
    private static final java.util.Set<Entity> DRAWN = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** A float sent each tick (a matrix, a pose): to 1e-4, which is all a model needs. */
    static float round(float f) {
        return Math.round(f * 10000.0F) / 10000.0F;
    }

    /**
     * An entry of the snapshot as Horizon routes it: where it is and the model trees it draws
     * (which players are sent it, which trees go with it), then the entry itself ("e"), passed
     * on as it is.
     */
    private static JsonObject routed(JsonObject entry) {
        JsonObject out = new JsonObject();
        out.add("x", entry.get("x"));
        out.add("y", entry.get("y"));
        out.add("z", entry.get("z"));
        JsonArray keys = new JsonArray();
        if (entry.get("models") instanceof JsonArray models) {
            for (var m : models) {
                if (m instanceof JsonObject o && o.has("key")) keys.add(o.get("key"));
            }
        }
        if (!keys.isEmpty()) out.add("keys", keys);
        out.add("e", entry);
        return out;
    }

    /** The snapshot of every level's mods' entities (see the class), for Horizon. */
    public static String snapshot() {
        long phase = HorizonProfile.start();
        try {
            return snapshotNow();
        } finally {
            HorizonProfile.add("snapshot", phase);
        }
    }

    private static String snapshotNow() {
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
                // It didn't tick (far and idle, HorizonEntities.active): it looks as it did,
                // which the player's game keeps.
                if (HorizonEntities.resting(entity) && DRAWN.contains(entity)) {
                    o.addProperty("keep", true);
                    list.add(routed(o));
                    continue;
                }
                DRAWN.add(entity);
                o.addProperty("w", entity.getBbWidth());
                o.addProperty("h", entity.getBbHeight());
                JsonArray items = new JsonArray();
                long started = HorizonProfile.start();
                o.add("models", models(entity, items));
                if (HorizonProfile.ON) HorizonProfile.render(
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), System.nanoTime() - started);
                if (!items.isEmpty()) o.add("items", items);
                // Its custom name, when its renderer couldn't say (Horizon shows it as its own
                // mobs': visible, or looked at).
                if (!extracted && entity.getCustomName() != null) {
                    o.add("name", com.google.gson.JsonParser.parseString(HorizonServer.json(entity.getCustomName())));
                    o.addProperty("name_visible", entity.isCustomNameVisible());
                }
                // The name tag its renderer gives (a custom name, or a mod's own: a pet's
                // owner, health): shown always, or when looked at; above its box, or where
                // the renderer attaches it.
                if (lastTag != null) {
                    o.add("tag", com.google.gson.JsonParser.parseString(HorizonServer.json(lastTag)));
                    if (lastTagAlways) o.addProperty("tag_always", true);
                    if (lastTagY >= 0.0) o.addProperty("tag_y", lastTagY);
                }
                float shadow = lastShadow;
                if (shadow > 0.0F) o.addProperty("shadow", shadow);
                list.add(routed(o));
            }
            if (!list.isEmpty()) dims.add(level.dimension().identifier().toString(), list);
        }
        // Mods' block entities their renderers draw (a cutting board's item, a stove's food),
        // as entities marked "block" at the block's corner.
        for (ServerLevel level : HorizonServer.levels()) {
            JsonArray blocks = blockEntities(level);
            if (blocks.isEmpty()) continue;
            String key = level.dimension().identifier().toString();
            JsonArray list = dims.has(key) ? dims.getAsJsonArray(key) : new JsonArray();
            for (var b : blocks) list.add(routed(b.getAsJsonObject()));
            dims.add(key, list);
        }
        JsonObject out = new JsonObject();
        out.add("dims", dims);
        JsonObject trees = new JsonObject();
        for (var t : NEW_TREES.entrySet()) trees.add(t.getKey(), t.getValue());
        NEW_TREES.clear();
        out.add("models", trees);
        return HorizonJson.write(out);
    }

    /**
     * What the entity's renderer submits (EntityRenderDispatcher.submit without the camera's
     * translation: matrices are relative to the entity's position), at the tick's end.
     */
    // ---- Items (ItemModelResolverMixin, ItemStackRenderStateMixin) ----------------------

    /** Items render states hold for Horizon: the stack and how it is shown. */
    private record Kept(net.minecraft.world.item.ItemStack stack, net.minecraft.world.item.ItemDisplayContext context) {}

    private static final Map<Object, Kept> KEPT = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    /** Where the entity being drawn puts its items. */
    private static JsonArray items;

    public static void keepItem(Object state, net.minecraft.world.item.ItemStack stack,
                                net.minecraft.world.item.ItemDisplayContext context) {
        if (!stack.isEmpty()) KEPT.put(state, new Kept(stack.copy(), context));
    }

    public static boolean keptItem(Object state) {
        return KEPT.containsKey(state);
    }

    public static void forgetItem(Object state) {
        KEPT.remove(state);
    }

    /** Items' SNBT by their stack (a held item is the same each tick): the last 256. */
    private static final Map<Integer, Object[]> SNBT = new java.util.LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Object[]> eldest) {
            return size() > 256;
        }
    };

    private static String snbt(net.minecraft.world.item.ItemStack stack) {
        int hash = net.minecraft.world.item.ItemStack.hashItemAndComponents(stack) * 31 + stack.getCount();
        synchronized (SNBT) {
            Object[] kept = SNBT.get(hash);
            if (kept != null && net.minecraft.world.item.ItemStack.matches((net.minecraft.world.item.ItemStack) kept[0], stack)) {
                return (String) kept[1];
            }
        }
        String snbt = HorizonServer.snbt(stack);
        synchronized (SNBT) {
            SNBT.put(hash, new Object[] {stack.copy(), snbt});
        }
        return snbt;
    }

    /** A kept item submitted: its stack, context and the pose then. False for other items. */
    public static boolean submitItem(Object state, PoseStack pose) {
        Kept kept = KEPT.get(state);
        if (kept == null) return false;
        if (items == null) return true;
        String snbt = snbt(kept.stack());
        if (snbt == null) return true;
        JsonObject o = new JsonObject();
        o.addProperty("item", snbt);
        o.addProperty("ctx", kept.context().getSerializedName());
        JsonArray matrix = new JsonArray();
        float[] m = new float[16];
        pose.last().pose().get(m);
        for (float f : m) matrix.add(round(f));
        o.add("m", matrix);
        items.add(o);
        return true;
    }

    private static java.lang.reflect.Method shadowRadius;
    /** The shadow radius of the entity drawn last (EntityRenderer.getShadowRadius). */
    private static float lastShadow;

    private static float shadow(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        try {
            if (shadowRadius == null) {
                shadowRadius = EntityRenderer.class.getDeclaredMethod("getShadowRadius", EntityRenderState.class);
                shadowRadius.setAccessible(true);
            }
            return (Float) shadowRadius.invoke(renderer, state);
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    /** The name tag of the entity drawn last (null: none), where, and whether always shown. */
    private static net.minecraft.network.chat.Component lastTag;
    private static double lastTagY;
    private static boolean lastTagAlways;
    /** Whether the entity drawn last had its render state made (its renderer chose its name). */
    private static boolean extracted;
    private static java.lang.reflect.Method shouldShowName;

    /** EntityRenderer.shouldShowName with nothing under the crosshair, from close by. */
    private static boolean showsName(EntityRenderer<?, ?> renderer, Entity entity) {
        try {
            if (shouldShowName == null) {
                shouldShowName = EntityRenderer.class.getDeclaredMethod("shouldShowName", Entity.class, double.class);
                shouldShowName.setAccessible(true);
            }
            return (Boolean) shouldShowName.invoke(renderer, entity, 0.0);
        } catch (Throwable t) {
            return entity.isCustomNameVisible();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonArray models(Entity entity, JsonArray heldItems) {
        items = heldItems;
        lastShadow = 0.0F;
        lastTag = null;
        extracted = false;
        try {
            return modelsOf(entity);
        } finally {
            items = null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonArray modelsOf(Entity entity) {
        JsonArray out = new JsonArray();
        EntityRenderer renderer = renderer(entity.getType());
        if (renderer == null) return out;
        try {
            // Its name tag as a player looking at it from close by would see it
            // (EntityRenderer.extractRenderState: shouldShowName, getNameTag): the camera on it
            // and it under the crosshair; then whether it shows when not looked at.
            var dispatcher = context().getEntityRenderDispatcher();
            HorizonClient.set(net.minecraft.client.Camera.class, dispatcher.camera, "position", entity.position());
            dispatcher.crosshairPickEntity = entity;
            // The nearest player is the one looking (Minecraft.player: whether it is invisible to
            // them, their team's name tag rules).
            var viewer = entity.level() instanceof ServerLevel l
                    ? l.getNearestPlayer(entity, -1.0) instanceof net.minecraft.server.level.ServerPlayer p ? p : null
                    : null;
            EntityRenderState[] made = new EntityRenderState[1];
            boolean[] always = new boolean[1];
            HorizonClient.ThrowingRunnable extract = () -> {
                try {
                    made[0] = renderer.createRenderState(entity, 1.0F);
                } finally {
                    dispatcher.crosshairPickEntity = null;
                }
                always[0] = made[0].nameTag != null && showsName(renderer, entity);
            };
            try {
                if (viewer != null) HorizonClient.as(viewer, extract);
                else extract.run();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            EntityRenderState state = made[0];
            lastTag = state.nameTag;
            lastTagY = state.nameTagAttachment == null ? -1.0 : state.nameTagAttachment.y;
            lastTagAlways = always[0];
            extracted = true;
            lastShadow = shadow(renderer, state);
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
     * {@code submitModel}; the others are its defaults, run as they are) and custom geometry
     * (GeckoLib's models: {@code submitCustomGeometry}, vertices written by the renderer).
     * Flames, leashes and the rest are not kept yet.
     */
    private static SubmitNodeCollector collector(JsonArray out) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.isDefault() && !method.getName().equals("submitBlockModel")) return InvocationHandler.invokeDefault(proxy, method, args);
                switch (method.getName()) {
                    case "order" -> {
                        return proxy;
                    }
                    case "submitModel" -> {
                        if (args.length == 9) model(out, (Model) args[0], args[1], (PoseStack) args[2], (RenderType) args[3],
                                (Integer) args[4], (Integer) args[5], (Integer) args[6], args[7]);
                    }
                    case "submitCustomGeometry" -> mesh(out, (PoseStack) args[0], (RenderType) args[1],
                            (SubmitNodeCollector.CustomGeometryRenderer) args[2]);
                    // Vanilla's (pose, render type, parts, tints, ...) and Fabric's (pose, render
                    // types, translucent, parts, mesh, tints, ...), whose default drops the mesh.
                    case "submitBlockModel" -> {
                        if (args.length == 9) blockModel(out, (PoseStack) args[0], (List<?>) args[3], args[4], (int[]) args[5]);
                        else blockModel(out, (PoseStack) args[0], (List<?>) args[2], null, (int[]) args[3]);
                        return null;
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
        for (float f : m) matrix.add(round(f));
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

    /**
     * Custom geometry (SubmitNodeCollector.submitCustomGeometry: GeckoLib's bones, a mod's own
     * quads): what its renderer writes to the vertex consumer, kept as quads relative to the
     * entity (the pose stack is the renderer's to apply). Only quad render types (entities',
     * blocks'): lines and the rest are left out.
     */
    private static void mesh(JsonArray out, PoseStack pose, RenderType type,
                             SubmitNodeCollector.CustomGeometryRenderer renderer) throws ReflectiveOperationException {
        if (!String.valueOf(type.primitiveTopology()).contains("QUAD")) return;
        String texture = texture(type);
        if (texture == null) return;
        Recorder recorder = new Recorder();
        renderer.render(pose.last(), recorder);
        int n = recorder.vertices.size() / 4 * 4;
        if (n == 0) return;
        JsonObject o = new JsonObject();
        o.addProperty("tex", texture);
        o.addProperty("overlay", recorder.overlay);
        if (glows(type)) o.addProperty("glow", true);
        JsonArray v = new JsonArray();
        for (int i = 0; i < n; i++) {
            float[] x = recorder.vertices.get(i);
            for (int k = 0; k < 8; k++) v.add(Math.round(x[k] * 10000.0F) / 10000.0F);
            v.add((long) Float.floatToRawIntBits(x[8]) & 0xFFFFFFFFL);
        }
        o.add("v", v);
        out.add(o);
    }

    /**
     * A block model a renderer submits (BlockModelRenderState: Chunk Loaders' floating core,
     * through Fabric's mesh): its parts' baked quads, posed, kept as custom geometry, one mesh
     * per sprite with the sprite's own UVs (the client's block atlas isn't Horizon's): the
     * sprite's texture, `ns:textures/<path>.png`, which Horizon's GUI atlas packs.
     */
    private static void blockModel(JsonArray out, PoseStack pose, List<?> parts, Object mesh, int[] tints) {
        var matrix = pose.last().pose();
        Map<String, JsonArray> bySprite = new java.util.LinkedHashMap<>();
        var directions = new java.util.ArrayList<net.minecraft.core.Direction>(java.util.Arrays.asList(net.minecraft.core.Direction.values()));
        directions.add(null);
        org.joml.Vector3f p = new org.joml.Vector3f();
        org.joml.Vector3f n = new org.joml.Vector3f();
        float[][] xyz = new float[4][3];
        float[][] uv = new float[4][2];
        for (Object o : parts) {
            if (!(o instanceof net.minecraft.client.renderer.block.dispatch.BlockStateModelPart part)) continue;
            for (var side : directions) {
                for (var quad : part.getQuads(side)) {
                    var info = quad.materialInfo();
                    for (int i = 0; i < 4; i++) {
                        var at = quad.position(i);
                        xyz[i][0] = at.x();
                        xyz[i][1] = at.y();
                        xyz[i][2] = at.z();
                        long packed = quad.packedUV(i);
                        uv[i][0] = net.minecraft.client.model.geom.builders.UVPair.unpackU(packed);
                        uv[i][1] = net.minecraft.client.model.geom.builders.UVPair.unpackV(packed);
                    }
                    var dir = quad.direction();
                    n.set(dir.getStepX(), dir.getStepY(), dir.getStepZ());
                    int tint = info.isTinted() ? tint(tints, info.tintIndex()) : -1;
                    quad(bySprite, pose, matrix, info.sprite(), xyz, uv, null, n, tint, p);
                }
            }
        }
        // A Fabric mesh (QuadEmitter quads: atlas UVs, no sprite): the sprite holding each
        // quad's UVs, in its atlas.
        if (mesh instanceof net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView view) {
            int[] colors = new int[4];
            view.forEach(q -> {
                for (int i = 0; i < 4; i++) {
                    xyz[i][0] = q.x(i);
                    xyz[i][1] = q.y(i);
                    xyz[i][2] = q.z(i);
                    uv[i][0] = q.u(i);
                    uv[i][1] = q.v(i);
                    colors[i] = q.color(i);
                }
                var face = q.faceNormal();
                n.set(face.x(), face.y(), face.z());
                float mu = (uv[0][0] + uv[2][0]) / 2, mv = (uv[0][1] + uv[2][1]) / 2;
                var sprite = spriteAt(q.atlas() == net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas.ITEM
                        ? net.minecraft.data.AtlasIds.ITEMS : net.minecraft.data.AtlasIds.BLOCKS, mu, mv);
                int tint = q.tintIndex() >= 0 ? tint(tints, q.tintIndex()) : -1;
                quad(bySprite, pose, matrix, sprite, xyz, uv, colors, n, tint, p);
            });
        }
        bySprite.forEach((texture, v) -> {
            JsonObject o = new JsonObject();
            o.addProperty("tex", texture);
            o.addProperty("overlay", net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            o.add("v", v);
            out.add(o);
        });
    }

    /** A sprite's texture file: the headless sprite's (HorizonSprites), else by its name. */
    private static String textureOf(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        var headless = HorizonSprites.at(sprite.getU0() + 1e-4F);
        if (headless != null && headless.atlasSprite() == sprite) return headless.texture();
        var name = sprite.contents().name();
        return name.getNamespace() + ":textures/" + name.getPath() + ".png";
    }

    private static int tint(int[] tints, int index) {
        return tints != null && index >= 0 && index < tints.length ? 0xFF000000 | tints[index] : -1;
    }

    /** One quad of a block model, posed, its UVs made the sprite's own, into its sprite's mesh. */
    private static void quad(Map<String, JsonArray> bySprite, PoseStack pose,
                             org.joml.Matrix4f matrix, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
                             float[][] xyz, float[][] uv, int[] colors, org.joml.Vector3f normal, int tint, org.joml.Vector3f p) {
        if (sprite == null) return;
        org.joml.Vector3f n = pose.last().normal().transform(new org.joml.Vector3f(normal)).normalize();
        JsonArray v = bySprite.computeIfAbsent(textureOf(sprite), k -> new JsonArray());
        float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
        for (int i = 0; i < 4; i++) {
            matrix.transformPosition(p.set(xyz[i][0], xyz[i][1], xyz[i][2]));
            float u = du == 0 ? 0 : (uv[i][0] - sprite.getU0()) / du;
            float w = dv == 0 ? 0 : (uv[i][1] - sprite.getV0()) / dv;
            for (float f : new float[] {p.x, p.y, p.z, u, w, n.x, n.y, n.z}) v.add(Math.round(f * 10000.0F) / 10000.0F);
            int color = colors == null ? -1 : colors[i];
            if (tint != -1) color = net.minecraft.util.ARGB.multiply(color, tint);
            v.add((long) color & 0xFFFFFFFFL);
        }
    }

    /** Per atlas: its sprites, to find the one holding a UV (Fabric's mesh quads). */
    private static final Map<Object, java.util.Collection<net.minecraft.client.renderer.texture.TextureAtlasSprite>> ATLAS_SPRITES =
            new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private static net.minecraft.client.renderer.texture.TextureAtlasSprite spriteAt(net.minecraft.resources.Identifier atlasId, float u, float v) {
        var sprites = ATLAS_SPRITES.get(atlasId);
        if (sprites == null) {
            try {
                var atlas = net.minecraft.client.Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(atlasId);
                Field field = net.minecraft.client.renderer.texture.TextureAtlas.class.getDeclaredField("texturesByName");
                field.setAccessible(true);
                sprites = java.util.List.copyOf(((Map<?, net.minecraft.client.renderer.texture.TextureAtlasSprite>) field.get(atlas)).values());
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
            // (Not kept while the atlas is still empty: the models load after the first frames.)
            if (sprites.isEmpty()) return null;
            ATLAS_SPRITES.put(atlasId, sprites);
        }
        for (var sprite : sprites) {
            if (u >= sprite.getU0() && u <= sprite.getU1() && v >= sprite.getV0() && v <= sprite.getV1()) return sprite;
        }
        return null;
    }

    /**
     * A vertex consumer keeping what is written: per vertex its position, UV, normal and
     * colour (ARGB, as the float's bits); the overlay (UV1) of the last vertex for all.
     */
    private static final class Recorder implements com.mojang.blaze3d.vertex.VertexConsumer {
        final List<float[]> vertices = new java.util.ArrayList<>();
        int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
        private float[] last = new float[9];

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            last = new float[] {x, y, z, 0, 0, 0, 1, 0, Float.intBitsToFloat(-1)};
            vertices.add(last);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            last[8] = Float.intBitsToFloat(a << 24 | r << 16 | g << 8 | b);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int argb) {
            last[8] = Float.intBitsToFloat(argb);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            last[3] = u;
            last[4] = v;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            overlay = u | v << 16;
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
            last[5] = x;
            last[6] = y;
            last[7] = z;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    /** Emissive render types (eyes, GeckoLib's glow layers) draw at full light. */
    private static boolean glows(RenderType type) {
        String name = type.toString();
        return name.contains("eyes") || name.contains("emissive");
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
            for (float f : now) a.add(round(f));
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
