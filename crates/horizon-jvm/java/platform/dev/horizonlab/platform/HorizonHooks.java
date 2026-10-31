package dev.horizonlab.platform;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.horizonlab.bridge.Natives;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/**
 * The mods' event-like Mixins: every {@code @Inject} at the HEAD, TAIL or RETURN of a vanilla
 * method, read from the mods' own Mixin configs (as the census does, here at start-up). Where
 * Horizon reaches such a method (a player joins, a tick, a death), it fires those injections
 * directly: the handler Mixin merged into the vanilla class is called with a real CallbackInfo,
 * and no vanilla code runs. A handler that cancels, or sets a return value, says so to Horizon.
 *
 * <p>Injections at other points (INVOKE, FIELD...), redirects and overwrites patch the inside
 * of vanilla's code: they take effect only where that code runs on the Java side.
 */
public final class HorizonHooks {
    private HorizonHooks() {}

    /** One @Inject of a mod: its target, where, and the handler as the mixin names it. */
    record Hook(String mod, String owner, String method, String desc, String at, String mixin, String handler,
                boolean cancellable) {}

    /** By "owner.method" (owner with slashes): the hooks there. */
    private static final Map<String, List<Hook>> HOOKS = new HashMap<>();
    /** The handlers resolved for a (owner, method, desc, at), in Mixin's priority order. */
    private static final Map<String, List<Method>> RESOLVED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static boolean scanned;
    /** HORIZON_JAVA_DEBUG=hooks: each firing is logged (ticks aside). */
    private static final boolean DEBUG = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("hooks");

    /** What a firing did: whether a handler cancelled, and the return value it left. */
    public record Result(boolean cancelled, Object returnValue) {
        static final Result NONE = new Result(false, null);
    }

    // ---- The census, at start-up ----------------------------------------------------------

    /** Reads every loaded mod's Mixin configs once. */
    public static synchronized void scan() {
        if (scanned) return;
        scanned = true;
        int injections = 0;
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            for (String config : mixinConfigs(mod)) {
                Optional<Path> path = mod.findPath(config);
                if (path.isEmpty()) continue;
                try {
                    JsonObject json = JsonParser.parseString(Files.readString(path.get())).getAsJsonObject();
                    String pkg = json.has("package") ? json.get("package").getAsString().replace('.', '/') : "";
                    for (String key : new String[] {"mixins", "server"}) {
                        if (!json.has(key) || !json.get(key).isJsonArray()) continue;
                        for (JsonElement name : json.getAsJsonArray(key)) {
                            String file = pkg + "/" + name.getAsString().replace('.', '/') + ".class";
                            Optional<Path> bytes = mod.findPath(file);
                            if (bytes.isPresent()) injections += read(id, Files.readAllBytes(bytes.get()));
                        }
                    }
                } catch (Throwable t) {
                    Natives.log("Mixin census: " + id + "'s " + config + ": " + t);
                }
            }
        }
        Natives.log("Mixin census: " + injections + " event-like injections into " + HOOKS.size() + " vanilla methods");
        scanClient();
    }

    // ---- The headless client's tick -------------------------------------------------------

    /** One injection of a mod into the client's frame or tick (Minecraft.runTick, .tick). */
    record ClientHook(String mod, String method, String mixin, String handler, boolean modifyArg) {}

    /** The client classes and methods whose injections run each headless client tick. */
    private static final String CLIENT_OWNER = "net/minecraft/client/Minecraft";
    private static final String HUD_OWNER = "net/minecraft/client/gui/Hud";
    /** The mods' injections into the HUD (Hud's methods): (method, mixin, handler). */
    private static final List<String[]> HUD_HOOKS = new ArrayList<>();

    private static void readHud(String mod, ClassNode node) {
        String mixin = node.name.replace('/', '.');
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                if (!a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                        && !a.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")) continue;
                if (!(get(a, "method") instanceof List<?> specs)) continue;
                for (Object spec : specs) HUD_HOOKS.add(new String[] {parseSpec((String) spec)[0], mixin, m.name, mod});
            }
        }
    }

    /** The Hud methods mods inject into (any injector: their HUD overlays draw there). */
    public static java.util.Set<String> hudMethods() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String[] h : HUD_HOOKS) out.add(h[0]);
        return out;
    }

    /**
     * Calls the mods' handlers merged into Hud for {@code method} whose parameters are the
     * method's own then a CallbackInfo (event-like injections at its head or end: what they
     * draw is theirs), for a part Horizon does not run (Hud.extractRenderState).
     */
    public static void fireHud(Object hud, String method, Object... args) {
        for (Method m : hud.getClass().getDeclaredMethods()) {
            MixinMerged merged = m.getAnnotation(MixinMerged.class);
            if (merged == null) continue;
            for (String[] h : HUD_HOOKS) {
                if (!h[0].equals(method) || !merged.mixin().equals(h[1])) continue;
                if (!m.getName().equals(h[2]) && !m.getName().endsWith("$" + h[2])) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != args.length + 1 || !CallbackInfo.class.isAssignableFrom(params[args.length])) continue;
                Object[] call = java.util.Arrays.copyOf(args, args.length + 1);
                call[args.length] = new CallbackInfo(method, true);
                try {
                    m.setAccessible(true);
                    m.invoke(Modifier.isStatic(m.getModifiers()) ? null : hud, call);
                } catch (Throwable t) {
                    Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                    if (FAILURES.merge(m.toString(), 1, Integer::sum) <= 3) {
                        Natives.log("A mod failed in the HUD's " + method + " (" + h[3] + "'s " + h[2] + "): " + cause);
                        StackTraceElement[] frames = cause.getStackTrace();
                        for (int f = 0; f < Math.min(frames.length, 8); f++) Natives.log("    at " + frames[f]);
                    }
                }
            }
        }
    }
    private static final java.util.Set<String> CLIENT_METHODS = java.util.Set.of("runTick", "tick");
    private static final List<ClientHook> CLIENT_HOOKS = new ArrayList<>();
    private static final Map<String, List<Method>> CLIENT_RESOLVED = new ConcurrentHashMap<>();

    /**
     * The mods' injections into Minecraft.runTick and Minecraft.tick, from their client Mixin
     * configs too: every @Inject (at any point: an INVOKE inside a frame is once a frame) and
     * every @ModifyArg there. The headless client runs no frame, so fireClient calls them.
     */
    private static void scanClient() {
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (id.equals("horizon") || id.startsWith("fabric-") || id.equals("fabric-api")) continue;
            for (String config : mixinConfigs(mod, true)) {
                Optional<Path> path = mod.findPath(config);
                if (path.isEmpty()) continue;
                try {
                    JsonObject json = JsonParser.parseString(Files.readString(path.get())).getAsJsonObject();
                    String pkg = json.has("package") ? json.get("package").getAsString().replace('.', '/') : "";
                    for (String key : new String[] {"mixins", "client"}) {
                        if (!json.has(key) || !json.get(key).isJsonArray()) continue;
                        for (JsonElement name : json.getAsJsonArray(key)) {
                            String file = pkg + "/" + name.getAsString().replace('.', '/') + ".class";
                            Optional<Path> bytes = mod.findPath(file);
                            if (bytes.isPresent()) readClient(id, Files.readAllBytes(bytes.get()));
                        }
                    }
                } catch (Throwable t) {
                    Natives.log("Mixin census: " + id + "'s " + config + " (client): " + t);
                }
            }
        }
        if (!CLIENT_HOOKS.isEmpty()) {
            Natives.log("Mixin census: " + CLIENT_HOOKS.size() + " injections into the client's tick, run each tick");
        }
    }

    private static void readClient(String mod, byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean minecraft = false, hud = false, frame = false;
        for (AnnotationNode a : annotations(node.invisibleAnnotations, node.visibleAnnotations)) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc)) continue;
            if (get(a, "value") instanceof List<?> list) {
                for (Object t : list) {
                    minecraft |= CLIENT_OWNER.equals(((org.objectweb.asm.Type) t).getInternalName());
                    hud |= HUD_OWNER.equals(((org.objectweb.asm.Type) t).getInternalName());
                    frame |= FRAME_OWNER.equals(((org.objectweb.asm.Type) t).getInternalName());
                }
            }
            if (get(a, "targets") instanceof List<?> list) {
                for (Object t : list) {
                    minecraft |= CLIENT_OWNER.equals(((String) t).replace('.', '/'));
                    hud |= HUD_OWNER.equals(((String) t).replace('.', '/'));
                }
            }
        }
        if (hud) readHud(mod, node);
        if (frame) readFrame(mod, node);
        if (!minecraft) return;
        String mixin = node.name.replace('/', '.');
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                boolean modifyArg = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;".equals(a.desc);
                if (!modifyArg && !"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc)) continue;
                if (!(get(a, "method") instanceof List<?> specs)) continue;
                for (Object spec : specs) {
                    String name = parseSpec((String) spec)[0];
                    if (CLIENT_METHODS.contains(name)) CLIENT_HOOKS.add(new ClientHook(mod, name, mixin, m.name, modifyArg));
                    // The game's start: Fabric starts the entrypoints in Minecraft's constructor at
                    // its first Thread.currentThread() (EntrypointPatch). Mods inject there to run
                    // just before them (shift BEFORE: SuperMartijn642's Core Lib gathers its
                    // registry fields) or just after (AFTER).
                    String start = name.equals("<init>") && !modifyArg ? startPoint(get(a, "at")) : null;
                    if (start != null) CLIENT_HOOKS.add(new ClientHook(mod, start, mixin, m.name, false));
                }
            }
        }
    }

    /** The game renderer, whose frame (extract) mods hook at its head and tail. */
    private static final String FRAME_OWNER = "net/minecraft/client/renderer/GameRenderer";
    /** The mods' injections at the head and tail of GameRenderer.extract: (at, mixin, handler). */
    private static final List<String[]> FRAME_HOOKS = new ArrayList<>();
    private static final Map<String, List<Method>> FRAME_RESOLVED = new ConcurrentHashMap<>();

    private static void readFrame(String mod, ClassNode node) {
        String mixin = node.name.replace('/', '.');
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc)) continue;
                if (!(get(a, "method") instanceof List<?> specs)) continue;
                List<?> ats = get(a, "at") instanceof List<?> l ? l : List.of();
                if (ats.size() != 1 || !(ats.get(0) instanceof AnnotationNode at)) continue;
                Object value = get(at, "value");
                if (!"HEAD".equals(value) && !"TAIL".equals(value) && !"RETURN".equals(value)) continue;
                for (Object spec : specs) {
                    if (!parseSpec((String) spec)[0].equals("extract")) continue;
                    FRAME_HOOKS.add(new String[] {"HEAD".equals(value) ? "HEAD" : "TAIL", mixin, m.name});
                }
            }
        }
    }

    /**
     * The mods' injections at the head ({@code head}) or tail of GameRenderer.extract, the
     * frame's start and end (Xaero's render tick: its minimap's chunk writing), with
     * (DeltaTracker.ONE, true) as the frame's arguments.
     */
    public static void fireFrame(Object gameRenderer, boolean head) {
        if (FRAME_HOOKS.isEmpty() || gameRenderer == null) return;
        String at = head ? "HEAD" : "TAIL";
        List<Method> handlers = FRAME_RESOLVED.computeIfAbsent(at, k -> {
            List<Method> found = new ArrayList<>();
            for (Method m : gameRenderer.getClass().getDeclaredMethods()) {
                MixinMerged merged = m.getAnnotation(MixinMerged.class);
                if (merged == null) continue;
                for (String[] h : FRAME_HOOKS) {
                    if (!h[0].equals(at) || !merged.mixin().equals(h[1])) continue;
                    if (!m.getName().equals(h[2]) && !m.getName().endsWith("$" + h[2])) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && p[0] == net.minecraft.client.DeltaTracker.class && p[1] == boolean.class
                            && CallbackInfo.class.isAssignableFrom(p[2])) {
                        m.setAccessible(true);
                        found.add(m);
                    }
                }
            }
            if (!found.isEmpty()) Natives.log("Horizon's client: " + found.size() + " mod injection(s) run at the " + at + " of each frame");
            return found;
        });
        for (Method handler : handlers) {
            try {
                handler.invoke(Modifier.isStatic(handler.getModifiers()) ? null : gameRenderer,
                        net.minecraft.client.DeltaTracker.ONE, true, new CallbackInfo("extract", true));
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                if (FAILURES.merge(handler.toString(), 1, Integer::sum) <= 3) {
                    Natives.log("A mod failed in the client's frame (" + handler.getName() + "): " + cause);
                    StackTraceElement[] frames = cause.getStackTrace();
                    for (int f = 0; f < Math.min(frames.length, 16); f++) Natives.log("    at " + frames[f]);
                }
            }
        }
    }

    /** The game's start point, before and after Fabric's entrypoints (fireClient's methods). */
    public static final String START_BEFORE = "<start before entrypoints>";
    public static final String START_AFTER = "<start after entrypoints>";

    /** START_BEFORE or START_AFTER for an @At at Minecraft.<init>'s first Thread.currentThread(); else null. */
    private static String startPoint(Object at) {
        List<?> ats = at instanceof List<?> l ? l : at == null ? List.of() : List.of(at);
        if (ats.size() != 1 || !(ats.get(0) instanceof AnnotationNode node)) return null;
        if (!"INVOKE".equals(get(node, "value"))) return null;
        if (!"Ljava/lang/Thread;currentThread()Ljava/lang/Thread;".equals(get(node, "target"))) return null;
        Object ordinal = get(node, "ordinal");
        if (ordinal instanceof Integer o && o != 0) return null;
        Object shift = get(node, "shift");
        boolean after = shift instanceof String[] e && e.length == 2 && "AFTER".equals(e[1]);
        return after ? START_AFTER : START_BEFORE;
    }

    /** Reads the mods' Mixin configs now (the census), if not yet: before the entrypoints run. */
    public static void scanNow() {
        scan();
    }

    /**
     * Runs the mods' injections into {@code minecraft}'s {@code method} (runTick or tick), as one
     * frame or game tick would reach them. An @Inject gets the method's arguments (runTick's
     * advanceGameTime: true) and a CallbackInfo; a @ModifyArg of a boolean gets true, its result
     * unused. Handlers taking locals, or other arguments, are left out. Failures are logged a
     * few times each (a handler failing once a tick fails every tick).
     */
    public static void fireClient(Object minecraft, String method) {
        if (CLIENT_HOOKS.isEmpty()) return;
        List<Method> handlers = CLIENT_RESOLVED.computeIfAbsent(method, k -> resolveClient(minecraft.getClass(), method));
        for (Method handler : handlers) {
            Class<?>[] params = handler.getParameterTypes();
            Object[] call = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                if (params[i] == boolean.class) call[i] = true;
                else if (CallbackInfo.class.isAssignableFrom(params[i])) call[i] = new CallbackInfo(method, true);
            }
            try {
                handler.invoke(Modifier.isStatic(handler.getModifiers()) ? null : minecraft, call);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                if (FAILURES.merge(handler.toString(), 1, Integer::sum) <= 3) {
                    Natives.log("A mod failed in the client's " + method + " (" + handler.getName() + "): " + cause);
                    StackTraceElement[] frames = cause.getStackTrace();
                    for (int f = 0; f < Math.min(frames.length, 8); f++) Natives.log("    at " + frames[f]);
                }
            }
        }
    }

    private static List<Method> resolveClient(Class<?> owner, String method) {
        List<Method> found = new ArrayList<>();
        for (Method m : owner.getDeclaredMethods()) {
            MixinMerged merged = m.getAnnotation(MixinMerged.class);
            if (merged == null) continue;
            for (ClientHook h : CLIENT_HOOKS) {
                if (!h.method().equals(method) || !merged.mixin().equals(h.mixin())) continue;
                if (!m.getName().equals(h.handler()) && !m.getName().endsWith("$" + h.handler())) continue;
                Class<?>[] params = m.getParameterTypes();
                boolean fits = h.modifyArg()
                        ? params.length == 1 && params[0] == boolean.class
                        : method.equals("runTick")
                                ? params.length == 2 && params[0] == boolean.class && CallbackInfo.class.isAssignableFrom(params[1])
                                : params.length == 1 && CallbackInfo.class.isAssignableFrom(params[0]);
                if (!fits) {
                    Natives.log("Mixin census: " + h.mod() + "'s " + h.handler() + " in the client's " + method
                            + " takes what Horizon can't give; not run");
                    continue;
                }
                m.setAccessible(true);
                found.add(m);
            }
        }
        if (!found.isEmpty()) Natives.log("Horizon's client: " + found.size() + " mod injection(s) run each client " + method);
        return found;
    }

    private static List<String> mixinConfigs(ModContainer mod) {
        return mixinConfigs(mod, false);
    }

    /** A mod's Mixin configs; {@code client}: those for the client only too. */
    private static List<String> mixinConfigs(ModContainer mod, boolean client) {
        List<String> out = new ArrayList<>();
        try {
            Optional<Path> meta = mod.findPath("fabric.mod.json");
            if (meta.isEmpty()) return out;
            JsonObject json = JsonParser.parseString(Files.readString(meta.get())).getAsJsonObject();
            if (!json.has("mixins")) return out;
            for (JsonElement m : json.getAsJsonArray("mixins")) {
                if (m.isJsonPrimitive()) out.add(m.getAsString());
                else if (m.isJsonObject()) {
                    JsonObject o = m.getAsJsonObject();
                    String env = o.has("environment") ? o.get("environment").getAsString() : "*";
                    if ((client || !"client".equals(env)) && o.has("config")) out.add(o.get("config").getAsString());
                }
            }
        } catch (Throwable ignored) {
            // A mod without readable metadata has no configs for us.
        }
        return out;
    }

    /** The event-like @Injects of one mixin class: how many. */
    private static int read(String mod, byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> targets = new ArrayList<>();
        for (AnnotationNode a : annotations(node.invisibleAnnotations, node.visibleAnnotations)) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc)) continue;
            Object value = get(a, "value");
            if (value instanceof List<?> list) {
                for (Object t : list) targets.add(((org.objectweb.asm.Type) t).getInternalName());
            }
            Object named = get(a, "targets");
            if (named instanceof List<?> list) {
                for (Object t : list) targets.add(((String) t).replace('.', '/'));
            }
        }
        if (targets.isEmpty()) return 0;
        String mixin = node.name.replace('/', '.');
        int n = 0;
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc)) continue;
                String at = eventPoint(get(a, "at"));
                if (at == null) continue;
                boolean cancellable = Boolean.TRUE.equals(get(a, "cancellable"));
                Object methods = get(a, "method");
                if (!(methods instanceof List<?> specs)) continue;
                for (Object spec : specs) {
                    String[] nameDesc = parseSpec((String) spec);
                    for (String owner : targets) {
                        HOOKS.computeIfAbsent(owner + "." + nameDesc[0], k -> new ArrayList<>())
                                .add(new Hook(mod, owner, nameDesc[0], nameDesc[1], at, mixin, m.name, cancellable));
                        n++;
                    }
                }
            }
        }
        return n;
    }

    @SafeVarargs
    private static List<AnnotationNode> annotations(List<AnnotationNode>... lists) {
        List<AnnotationNode> out = new ArrayList<>();
        for (List<AnnotationNode> l : lists) if (l != null) out.addAll(l);
        return out;
    }

    private static Object get(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (key.equals(a.values.get(i))) return a.values.get(i + 1);
        }
        return null;
    }

    /** HEAD, TAIL or RETURN when every @At of the injection is one of them; else null. */
    private static String eventPoint(Object at) {
        List<?> ats = at instanceof List<?> l ? l : at == null ? List.of() : List.of(at);
        String point = null;
        for (Object o : ats) {
            if (!(o instanceof AnnotationNode node)) return null;
            Object v = get(node, "value");
            if (!"HEAD".equals(v) && !"TAIL".equals(v) && !"RETURN".equals(v)) return null;
            if (point != null && !point.equals(v)) return null;
            point = (String) v;
        }
        return point;
    }

    /** "name", "name(desc)", "Lowner;name(desc)" or "owner.name" as {name, desc or null}. */
    private static String[] parseSpec(String spec) {
        String s = spec;
        int paren = s.indexOf('(');
        String desc = paren >= 0 ? s.substring(paren) : null;
        String head = paren >= 0 ? s.substring(0, paren) : s;
        int semi = head.indexOf(';');
        if (semi >= 0) head = head.substring(semi + 1);
        int dot = head.lastIndexOf('.');
        if (dot >= 0) head = head.substring(dot + 1);
        int colon = head.indexOf(':');
        if (colon >= 0) head = head.substring(0, colon);
        // "<init>*": every constructor (Cardinal Components' level components).
        if (head.endsWith("*")) head = head.substring(0, head.length() - 1);
        return new String[] {head, desc};
    }

    // ---- Firing -----------------------------------------------------------------------------

    /** Whether any mod has an event-like injection into owner.method (owner with slashes). */
    public static boolean hooked(String owner, String method) {
        return HOOKS.containsKey(owner + "." + method);
    }

    /** The vanilla methods mods hook at their HEAD, TAIL or RETURN, with how many injections. */
    public static Map<String, Integer> census() {
        Map<String, Integer> out = new TreeMap<>();
        HOOKS.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    /**
     * Fires the mods' injections at {@code at} (HEAD, TAIL or RETURN) of
     * {@code owner.method(desc)} for {@code self} (null for a static method) with the method's
     * arguments. {@code skipFabric}: leave Fabric API's own injections out (Horizon fires
     * Fabric's events there itself). For RETURN, {@code returnValue} is what the method returns.
     */
    public static Result fire(Object self, Class<?> owner, String method, String desc, String at, Object[] args,
                              Object returnValue, boolean skipFabric) {
        String key = owner.getName().replace('.', '/') + "." + method;
        List<Hook> hooks = HOOKS.get(key);
        if (hooks == null) return Result.NONE;
        List<Method> handlers = RESOLVED.computeIfAbsent(key + "|" + desc + "|" + at + "|" + skipFabric,
                k -> resolve(owner, hooks, method, desc, at, args.length, skipFabric));
        if (DEBUG && !method.equals("tick")) {
            Natives.log("hooks: " + at + " of " + owner.getSimpleName() + "." + method + ": " + handlers.size()
                    + " handler(s) " + handlers.stream().map(Method::getName).toList());
        }
        if (handlers.isEmpty()) return Result.NONE;
        CallbackInfo info = desc.endsWith(")V")
                ? new CallbackInfo(method, true)
                : new CallbackInfoReturnable<>(method, true, returnValue);
        for (Method handler : handlers) {
            Object[] call = new Object[args.length + 1];
            System.arraycopy(args, 0, call, 0, args.length);
            call[args.length] = info;
            try {
                handler.invoke(Modifier.isStatic(handler.getModifiers()) ? null : self, call);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                if (FAILURES.merge(handler.toString(), 1, Integer::sum) <= 3) {
                    Natives.log("A mod failed at " + at + " of " + owner.getSimpleName() + "." + method + " (" + handler.getName() + "): " + cause);
                    StackTraceElement[] frames = cause.getStackTrace();
                    for (int f = 0; f < Math.min(frames.length, 8); f++) Natives.log("    at " + frames[f]);
                }
            }
            if (info.isCancelled()) break;
        }
        Object value = info instanceof CallbackInfoReturnable<?> cir ? cir.getReturnValue() : null;
        return new Result(info.isCancelled(), value);
    }

    /**
     * The mods' injections at the end (TAIL, RETURN) of the constructors of {@code self}'s
     * classes, superclass first, as its constructors would run them: for an object the mirror
     * made without its constructor (a level), once it is set up. Mods give vanilla's objects
     * fields there (Chunk Loaders' per-level loaders). The constructor's own arguments aren't
     * there to give: a handler that takes them gets nulls (zeros), and may fail (logged).
     */
    public static void constructed(Object self) {
        // A level can be made before the census ran (the content export's).
        scan();
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = self.getClass(); c != null && c != Object.class; c = c.getSuperclass()) chain.add(0, c);
        for (Class<?> owner : chain) {
            List<Hook> hooks = HOOKS.get(owner.getName().replace('.', '/') + ".<init>");
            if (hooks == null) continue;
            for (String at : new String[] {"RETURN", "TAIL"}) {
                for (Method handler : constructorHandlers(owner, hooks, at)) {
                    Class<?>[] params = handler.getParameterTypes();
                    java.lang.reflect.Type[] types = handler.getGenericParameterTypes();
                    Object[] call = new Object[params.length];
                    for (int i = 0; i < params.length - 1; i++) call[i] = argument(owner, self, params[i], types[i]);
                    call[params.length - 1] = new CallbackInfo("<init>", false);
                    try {
                        handler.invoke(Modifier.isStatic(handler.getModifiers()) ? null : self, call);
                    } catch (Throwable t) {
                        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
                        Natives.log("A mod failed at the end of " + owner.getSimpleName() + "'s constructor ("
                                + handler.getName() + "): " + cause);
                    }
                }
            }
        }
    }

    /**
     * A constructor argument a handler takes, for an object made without its constructor: the
     * value of the owner's one field of that exact (generic) type, which the constructor kept
     * from it (Level's dimension type holder, which Lithium caches the height from at its end:
     * without it every level is 0 blocks tall and worldgen fails); otherwise null or zero.
     */
    private static Object argument(Class<?> owner, Object self, Class<?> type, java.lang.reflect.Type generic) {
        if (type.isPrimitive()) return zero(type);
        // A level's stem (ServerLevel's constructor: its generator, which BCLib's and BetterEnd's
        // handlers read), from the registries by the level's dimension.
        if (type == net.minecraft.world.level.dimension.LevelStem.class && self instanceof net.minecraft.world.level.Level level) {
            try {
                return level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM)
                        .getValue(net.minecraft.resources.Identifier.parse(level.dimension().identifier().toString()));
            } catch (RuntimeException e) {
                return null;
            }
        }
        // The owner's fields, then its superclasses' (ServerLevel's dimension key is Level's);
        // by generic type, then (a handler declared with a raw type) by class alone.
        for (boolean exact : new boolean[] {true, false}) {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                java.lang.reflect.Field match = null;
                boolean several = false;
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (exact ? !f.getGenericType().equals(generic) : f.getType() != type) continue;
                    if (match != null) several = true;
                    match = f;
                }
                if (several) break;
                if (match == null) continue;
                try {
                    match.setAccessible(true);
                    return match.get(self);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object zero(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }

    /** The merged constructor handlers at {@code at}, priority first; each ends with its CallbackInfo. */
    private static List<Method> constructorHandlers(Class<?> owner, List<Hook> hooks, String at) {
        return RESOLVED.computeIfAbsent(owner.getName() + ".<init>|" + at, k -> {
            List<Method> found = new ArrayList<>();
            Map<Method, Integer> priority = new HashMap<>();
            for (Method m : owner.getDeclaredMethods()) {
                MixinMerged merged = m.getAnnotation(MixinMerged.class);
                if (merged == null) continue;
                for (Hook h : hooks) {
                    if (!h.at().equals(at) || h.mod().equals("horizon")) continue;
                    if (!merged.mixin().equals(h.mixin())) continue;
                    if (!m.getName().equals(h.handler()) && !m.getName().endsWith("$" + h.handler())) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 0 || !CallbackInfo.class.isAssignableFrom(params[params.length - 1])) continue;
                    m.setAccessible(true);
                    found.add(m);
                    priority.put(m, merged.priority());
                }
            }
            found.sort(Comparator.comparingInt(priority::get));
            return found;
        });
    }

    /** The merged handlers of these hooks in the target class, priority first, for one call shape. */
    private static List<Method> resolve(Class<?> owner, List<Hook> hooks, String method, String desc, String at,
                                        int arguments, boolean skipFabric) {
        Map<String, Hook> wanted = new HashMap<>();
        for (Hook h : hooks) {
            if (!h.at().equals(at) || !h.method().equals(method)) continue;
            if (h.desc() != null && !h.desc().equals(desc)) continue;
            if (skipFabric && (h.mod().startsWith("fabric-") || h.mod().equals("fabric-api"))) continue;
            if (h.mod().equals("horizon")) continue;
            wanted.put(h.mixin() + "#" + h.handler(), h);
        }
        List<Method> found = new ArrayList<>();
        Map<Method, Integer> priority = new HashMap<>();
        for (Method m : owner.getDeclaredMethods()) {
            MixinMerged merged = m.getAnnotation(MixinMerged.class);
            if (merged == null) continue;
            for (Hook h : wanted.values()) {
                if (!merged.mixin().equals(h.mixin())) continue;
                if (!m.getName().equals(h.handler()) && !m.getName().endsWith("$" + h.handler())) continue;
                Class<?>[] params = m.getParameterTypes();
                // The method's arguments, then the CallbackInfo; locals captured after it can't
                // be given (no vanilla frame runs), so such a handler is left out.
                if (params.length != arguments + 1 || !CallbackInfo.class.isAssignableFrom(params[arguments])) {
                    if (FAILURES.merge("shape " + m, 1, Integer::sum) == 1) {
                        Natives.log("Mixin census: " + h.mod() + "'s " + h.handler() + " at " + at + " of "
                                + owner.getSimpleName() + "." + method + " takes locals; Horizon can't give them");
                    }
                    continue;
                }
                m.setAccessible(true);
                found.add(m);
                priority.put(m, merged.priority());
            }
        }
        found.sort(Comparator.comparingInt(priority::get));
        return found;
    }
}
