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
    }

    private static List<String> mixinConfigs(ModContainer mod) {
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
                    if (!"client".equals(env) && o.has("config")) out.add(o.get("config").getAsString());
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
                    Object[] call = new Object[params.length];
                    for (int i = 0; i < params.length - 1; i++) call[i] = zero(params[i]);
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
