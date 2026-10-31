package dev.horizonlab.platform;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.horizonlab.bridge.Natives;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Every vanilla method the mods' Mixins patch, with every kind of patch: injections at any
 * point (HEAD and RETURN, but INVOKE, FIELD, NEW... too), redirects, @Modify*, overwrites and
 * MixinExtras' wrappers. Read from the mods' own Mixin configs at start-up, as HorizonHooks'
 * census, but whole.
 *
 * <p>Horizon is the game: most of vanilla's code never runs on the Java side, so a patch into
 * it does nothing unless Horizon fires it or hands the action to the mirror. The table says for
 * each patched method where it runs ({@link #where}), and the actions Horizon routes to the
 * mirror (breaking a block, a random tick, using an item on a block, picking up an item or an
 * experience orb) ask it whether a mod changes their middle ({@link #patchedInside}).
 */
public final class HorizonMixinTable {
    private HorizonMixinTable() {}

    /** One patch of a mod: its target, what kind, where in it, and the handler. */
    public record Patch(String mod, String owner, String method, String desc, String kind, String at,
                        String atTarget, String mixin, String handler, boolean client) {
        /** A HEAD, TAIL or RETURN @Inject: an event Horizon can fire without vanilla's body. */
        public boolean eventLike() {
            return kind.equals("inject") && (at.equals("HEAD") || at.equals("TAIL") || at.equals("RETURN"));
        }

        /** Fabric API's (and Horizon's own): Horizon fires Fabric's events itself. */
        public boolean platform() {
            return mod.equals("horizon") || mod.equals("fabric-api") || mod.startsWith("fabric-")
                    || mod.equals("fabricloader") || mod.equals("mixinextras");
        }

        String describe() {
            String where = at.isEmpty() ? "" : " " + at + (atTarget.isEmpty() ? "" : " " + shortTarget(atTarget));
            return kind + where;
        }
    }

    /** By "owner.method" (owner with slashes): every patch there. */
    private static final Map<String, List<Patch>> PATCHES = new TreeMap<>();
    /** By owner: the methods patched in it (to walk a class chain). */
    private static final Map<String, Set<String>> BY_OWNER = new HashMap<>();
    private static boolean scanned;

    /** The injector annotations, by descriptor suffix (MixinExtras is shaded under other packages too). */
    private static final Map<String, String> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("/mixin/injection/Inject;", "inject");
        KINDS.put("/mixin/injection/Redirect;", "redirect");
        KINDS.put("/mixin/injection/ModifyArg;", "modify-arg");
        KINDS.put("/mixin/injection/ModifyArgs;", "modify-args");
        KINDS.put("/mixin/injection/ModifyVariable;", "modify-variable");
        KINDS.put("/mixin/injection/ModifyConstant;", "modify-constant");
        KINDS.put("/WrapOperation;", "wrap-operation");
        KINDS.put("/WrapWithCondition;", "wrap-with-condition");
        KINDS.put("/ModifyExpressionValue;", "modify-expression");
        KINDS.put("/ModifyReturnValue;", "modify-return");
        KINDS.put("/ModifyReceiver;", "modify-receiver");
        KINDS.put("/WrapMethod;", "wrap-method");
    }

    // ---- The census -------------------------------------------------------------------------

    /** Reads every loaded mod's Mixin configs once (server, common and client mixins). */
    public static synchronized void scan() {
        if (scanned) return;
        scanned = true;
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            for (String[] config : configs(mod)) {
                Optional<Path> path = mod.findPath(config[0]);
                if (path.isEmpty()) continue;
                try {
                    JsonObject json = JsonParser.parseString(Files.readString(path.get())).getAsJsonObject();
                    String pkg = json.has("package") ? json.get("package").getAsString().replace('.', '/') : "";
                    boolean clientConfig = config[1] != null;
                    for (String key : new String[] {"mixins", "server", "client"}) {
                        if (!json.has(key) || !json.get(key).isJsonArray()) continue;
                        boolean client = clientConfig || key.equals("client");
                        for (JsonElement name : json.getAsJsonArray(key)) {
                            String file = pkg + "/" + name.getAsString().replace('.', '/') + ".class";
                            Optional<Path> bytes = mod.findPath(file);
                            if (bytes.isPresent()) read(id, Files.readAllBytes(bytes.get()), client);
                        }
                    }
                } catch (Throwable t) {
                    Natives.log("Mixin table: " + id + "'s " + config[0] + ": " + t);
                }
            }
        }
        report();
    }

    /** A mod's Mixin configs: {path, "client" or null}. */
    private static List<String[]> configs(ModContainer mod) {
        List<String[]> out = new ArrayList<>();
        try {
            Optional<Path> meta = mod.findPath("fabric.mod.json");
            if (meta.isEmpty()) return out;
            JsonObject json = JsonParser.parseString(Files.readString(meta.get())).getAsJsonObject();
            if (!json.has("mixins")) return out;
            for (JsonElement m : json.getAsJsonArray("mixins")) {
                if (m.isJsonPrimitive()) out.add(new String[] {m.getAsString(), null});
                else if (m.isJsonObject() && m.getAsJsonObject().has("config")) {
                    JsonObject o = m.getAsJsonObject();
                    String env = o.has("environment") ? o.get("environment").getAsString() : "*";
                    out.add(new String[] {o.get("config").getAsString(), "client".equals(env) ? "client" : null});
                }
            }
        } catch (Throwable ignored) {
            // No readable metadata: no configs.
        }
        return out;
    }

    private static void read(String mod, byte[] bytes, boolean client) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> targets = new ArrayList<>();
        for (AnnotationNode a : annotations(node.invisibleAnnotations, node.visibleAnnotations)) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc)) continue;
            if (get(a, "value") instanceof List<?> list) {
                for (Object t : list) targets.add(((org.objectweb.asm.Type) t).getInternalName());
            }
            if (get(a, "targets") instanceof List<?> list) {
                for (Object t : list) targets.add(((String) t).replace('.', '/'));
            }
        }
        if (targets.isEmpty()) return;
        String mixin = node.name.replace('/', '.');
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : annotations(m.invisibleAnnotations, m.visibleAnnotations)) {
                if ("Lorg/spongepowered/asm/mixin/Overwrite;".equals(a.desc)) {
                    for (String owner : targets) add(new Patch(mod, owner, m.name, m.desc, "overwrite", "", "", mixin, m.name, client));
                    continue;
                }
                String kind = kind(a.desc);
                if (kind == null) continue;
                if (!(get(a, "method") instanceof List<?> specs)) continue;
                List<String[]> points = points(get(a, "at"));
                if (kind.equals("modify-constant") || kind.equals("wrap-method") || points.isEmpty()) {
                    points = List.<String[]>of(new String[] {kind.equals("modify-return") ? "RETURN" : "", ""});
                }
                for (Object spec : specs) {
                    String[] nameDesc = HorizonHooks.parseSpec((String) spec);
                    for (String owner : targets) {
                        for (String[] point : points) {
                            add(new Patch(mod, owner, nameDesc[0], nameDesc[1], kind, point[0], point[1], mixin, m.name, client));
                        }
                    }
                }
            }
        }
    }

    private static void add(Patch p) {
        PATCHES.computeIfAbsent(p.owner() + "." + p.method(), k -> new ArrayList<>()).add(p);
        BY_OWNER.computeIfAbsent(p.owner(), k -> new HashSet<>()).add(p.method());
    }

    private static String kind(String desc) {
        if (!desc.contains("/mixin/") && !desc.contains("mixinextras")) return null;
        for (var e : KINDS.entrySet()) if (desc.endsWith(e.getKey())) return e.getValue();
        return null;
    }

    /** Each @At of an injector as {value, target}. */
    private static List<String[]> points(Object at) {
        List<?> ats = at instanceof List<?> l ? l : at == null ? List.of() : List.of(at);
        List<String[]> out = new ArrayList<>();
        for (Object o : ats) {
            if (!(o instanceof AnnotationNode node)) continue;
            Object value = get(node, "value");
            Object target = get(node, "target");
            out.add(new String[] {value == null ? "" : value.toString(), target == null ? "" : target.toString()});
        }
        return out;
    }

    // ---- Where each patched method runs ---------------------------------------------------

    /**
     * Where a vanilla method runs in Horizon: "java" (the mirror or the headless client runs
     * it: whatever a mod patches there applies), "fired" (Horizon's own code fires the mods'
     * HEAD/TAIL/RETURN injections there), "routed" (the action is the mirror's when a mod
     * patches its middle), or "rust" (Horizon's Rust does it: a patch there does nothing).
     * {what} says which part of Horizon.
     */
    public record Where(String side, String what) {}

    /** The actions Horizon routes to the mirror: their vanilla methods, any class of the chain. */
    public static final Set<String> BREAK = Set.of("destroyBlock", "playerWillDestroy", "playerDestroy",
            "destroy", "dropResources", "getDrops", "spawnAfterBreak", "popExperience", "mineBlock",
            "canDestroyBlock", "onDestroyedByPlayer", "removeBlock");
    public static final Set<String> RANDOM_TICK = Set.of("randomTick", "growCrops", "getGrowthSpeed",
            "advanceTree", "isRandomlyTicking", "canGrow", "grow");
    public static final Set<String> USE_ON = Set.of("useItemOn", "useWithoutItem", "useOn", "place",
            "placeBlock", "updatePlacementContext", "getPlacementState", "growCrop", "performBonemeal",
            "isValidBonemealTarget", "isBonemealSuccess", "useOnRelease");
    public static final Set<String> PICKUP = Set.of("playerTouch", "take", "add");
    public static final Set<String> XP = Set.of("playerTouch", "repairPlayerItems", "giveExperiencePoints",
            "giveExperienceLevels", "award", "tryMergeToExisting", "canMerge", "merge", "getValue");

    private static final String GAME_MODE = "net/minecraft/server/level/ServerPlayerGameMode";
    private static final String ITEM_ENTITY = "net/minecraft/world/entity/item/ItemEntity";
    private static final String ORB = "net/minecraft/world/entity/ExperienceOrb";

    /** The points Horizon fires event-like injections at (HorizonServer's hook calls). */
    private static final Set<String> FIRED = Set.of(
            "net/minecraft/server/MinecraftServer.loadLevel", "net/minecraft/server/MinecraftServer.tickServer",
            "net/minecraft/server/MinecraftServer.<init>",
            "net/minecraft/server/level/ServerLevel.tick", "net/minecraft/server/level/ServerLevel.<init>",
            "net/minecraft/world/level/Level.<init>",
            "net/minecraft/server/level/ServerPlayer.tick", "net/minecraft/world/entity/player/Player.tick",
            "net/minecraft/server/level/ServerPlayer.die", "net/minecraft/world/entity/LivingEntity.die",
            "net/minecraft/world/entity/LivingEntity.dropAllDeathLoot",
            "net/minecraft/server/level/ServerPlayer.restoreFrom",
            "net/minecraft/server/players/PlayerList.placeNewPlayer", "net/minecraft/server/players/PlayerList.remove",
            "net/minecraft/server/level/ServerPlayer.hurtServer", "net/minecraft/world/entity/player/Player.hurtServer",
            "net/minecraft/world/entity/LivingEntity.hurtServer",
            "net/minecraft/client/Minecraft.runTick", "net/minecraft/client/Minecraft.tick",
            GAME_MODE + ".useItem", GAME_MODE + ".destroyBlock");

    public static Where where(String owner, String method) {
        String key = owner + "." + method;
        if (!owner.startsWith("net/minecraft/") && !owner.startsWith("com/mojang/")) return new Where("java", "a mod's or library's class");
        if (owner.startsWith("net/minecraft/client/") || owner.startsWith("com/mojang/blaze3d/")) {
            if (FIRED.contains(key)) return new Where("fired", "the headless client's tick");
            return new Where("java", "the headless client (screens, HUD, client ticks; not Horizon's rendering)");
        }
        // The actions Horizon routes.
        if (owner.equals(GAME_MODE) && method.equals("useItemOn")) return new Where("routed", "using a block");
        if (owner.equals(GAME_MODE) && method.equals("destroyBlock")) return new Where("routed", "breaking a block");
        if (owner.equals(ITEM_ENTITY) && PICKUP.contains(method)) return new Where("routed", "item pickup");
        if (owner.equals(ORB) && XP.contains(method)) return new Where("routed", "experience orbs");
        if (FIRED.contains(key)) return new Where("fired", "Horizon fires it");
        boolean block = owner.startsWith("net/minecraft/world/level/block/") && !owner.contains("/entity/");
        boolean item = owner.startsWith("net/minecraft/world/item/");
        if (block && RANDOM_TICK.contains(method)) return new Where("routed", "random ticks");
        if (block && BREAK.contains(method)) return new Where("routed", "breaking a block");
        if ((block || item) && USE_ON.contains(method)) return new Where("routed", "using an item on a block");
        if (item && method.equals("mineBlock")) return new Where("routed", "breaking a block");
        if (owner.equals("net/minecraft/world/entity/player/Inventory") || owner.equals("net/minecraft/world/entity/player/Player")) {
            if (method.equals("add") || method.equals("take")) return new Where("routed", "item pickup");
            if (method.startsWith("giveExperience")) return new Where("routed", "experience orbs");
        }
        // Parts the mirror runs.
        if (owner.startsWith("net/minecraft/world/level/levelgen/") || owner.startsWith("net/minecraft/world/level/chunk/")
                || owner.startsWith("net/minecraft/world/level/biome/") && !method.contains("Temperature")
                        && !method.contains("Snow") && !method.contains("Freeze") && !method.contains("Precipitation")) {
            return new Where("java", "worldgen (Java when mods change a dimension's generation)");
        }
        if (owner.startsWith("net/minecraft/world/inventory/")) return new Where("java", "menus (mods' menus; vanilla's are Horizon's)");
        if (owner.startsWith("net/minecraft/world/item/crafting/") || owner.startsWith("net/minecraft/server/packs/")
                || owner.startsWith("net/minecraft/resources/") || owner.startsWith("net/minecraft/core/")
                || owner.startsWith("net/minecraft/tags/") || owner.startsWith("net/minecraft/util/")
                || owner.startsWith("net/minecraft/nbt/") || owner.startsWith("net/minecraft/network/")
                || owner.startsWith("net/minecraft/commands/") || owner.startsWith("net/minecraft/data/")
                || owner.startsWith("net/minecraft/world/level/storage/loot/") || owner.startsWith("com/mojang/")
                || owner.equals("net/minecraft/server/ReloadableServerResources")
                || owner.startsWith("net/minecraft/server/commands/")) {
            return new Where("java", "data, registries, codecs (loaded in the mirror; Horizon has its own copy)");
        }
        if (owner.startsWith("net/minecraft/world/level/block/entity/")) {
            return new Where("java", "block entities (mods' own; vanilla's are Horizon's)");
        }
        if (owner.equals("net/minecraft/world/entity/player/Inventory")) {
            return new Where("rust", "the player's inventory (Horizon's; mirrored into Java for mods)");
        }
        if (owner.startsWith("net/minecraft/world/entity/")) {
            return new Where("rust", "entities (Horizon's; in Java for mods' own entities only)");
        }
        if (owner.startsWith("net/minecraft/world/level/block/")) return new Where("rust", "block behaviour (Horizon's; in Java for mods' blocks)");
        if (owner.startsWith("net/minecraft/world/item/")) return new Where("rust", "items (Horizon's; in Java for mods' items)");
        if (owner.startsWith("net/minecraft/server/level/ServerLevel") || owner.startsWith("net/minecraft/world/level/Level")) {
            return new Where("rust", "the level's tick (Horizon's; block reads and writes reach Horizon's world)");
        }
        if (owner.startsWith("net/minecraft/server/")) return new Where("rust", "the server (Horizon's)");
        return new Where("rust", "Horizon's");
    }

    /** Whether a patch takes effect: its method runs in Java, or Horizon fires or routes it. */
    public static String effect(Patch p) {
        Where w = where(p.owner(), p.method());
        return switch (w.side()) {
            case "java" -> "applies (Java)";
            case "fired" -> p.eventLike() ? "fired by Horizon" : "not applied (Horizon runs this part)";
            case "routed" -> p.eventLike() && FIRED.contains(p.owner() + "." + p.method())
                    ? "fired by Horizon" : "routed to Java";
            default -> "not applied (Horizon's Rust)";
        };
    }

    // ---- The actions' questions -----------------------------------------------------------

    /**
     * Whether a mod (not Fabric API, whose events Horizon fires) patches {@code methods} of
     * {@code type} or of a class above it, other than by the event-like injections Horizon
     * fires itself ({@code fired}: "owner.method" keys whose HEAD/TAIL/RETURN injections it
     * fires): then the action is the mirror's, vanilla's code with the patches in.
     */
    public static boolean patchedInside(Class<?> type, Set<String> methods, Set<String> fired) {
        scan();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            String owner = c.getName().replace('.', '/');
            Set<String> patched = BY_OWNER.get(owner);
            if (patched == null) continue;
            for (String method : patched) {
                if (!methods.contains(method)) continue;
                for (Patch p : PATCHES.get(owner + "." + method)) {
                    if (p.platform() || p.client()) continue;
                    if (p.eventLike() && fired.contains(owner + "." + method)) continue;
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether any mod has an event-like injection at {@code at} of owner.method. */
    public static boolean hasEvent(String owner, String method, String at) {
        scan();
        List<Patch> list = PATCHES.get(owner + "." + method);
        if (list == null) return false;
        for (Patch p : list) if (!p.platform() && p.eventLike() && p.at().equals(at)) return true;
        return false;
    }

    /** The mods patching anything of owner.method (not Fabric API's). */
    public static Set<String> mods(String owner, String method) {
        Set<String> out = new java.util.TreeSet<>();
        List<Patch> list = PATCHES.get(owner + "." + method);
        if (list != null) for (Patch p : list) if (!p.platform()) out.add(p.mod());
        return out;
    }

    /** Every patch (for tools and tests). */
    public static List<Patch> all() {
        scan();
        List<Patch> out = new ArrayList<>();
        PATCHES.values().forEach(out::addAll);
        return out;
    }

    // ---- The report -------------------------------------------------------------------------

    /**
     * Logs the table's sums and each vanilla method a mod (not Fabric API) patches that Horizon
     * does in Rust, so a mod that "does nothing" shows why; the whole table goes to
     * horizon-java/mixin-table.tsv in the game folder.
     */
    private static void report() {
        int total = 0;
        Map<String, Integer> byEffect = new TreeMap<>();
        Map<String, List<Patch>> inert = new TreeMap<>();
        StringBuilder tsv = new StringBuilder("mod\towner\tmethod\tdesc\tkind\tat\tat_target\tmixin\thandler\tside\tclient\twhere\teffect\n");
        for (var e : PATCHES.entrySet()) {
            for (Patch p : e.getValue()) {
                total++;
                Where w = where(p.owner(), p.method());
                String effect = effect(p);
                if (!p.platform()) byEffect.merge(effect, 1, Integer::sum);
                if (!p.platform() && !p.client() && effect.startsWith("not applied")) {
                    inert.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(p);
                }
                tsv.append(p.mod()).append('\t').append(p.owner()).append('\t').append(p.method()).append('\t')
                        .append(p.desc() == null ? "" : p.desc()).append('\t').append(p.kind()).append('\t')
                        .append(p.at()).append('\t').append(p.atTarget()).append('\t').append(p.mixin()).append('\t')
                        .append(p.handler()).append('\t').append(w.side()).append('\t').append(p.client()).append('\t')
                        .append(w.what()).append('\t').append(effect).append('\n');
            }
        }
        Natives.log("Mixin table: " + total + " patches into " + PATCHES.size() + " methods; mods' (not Fabric API's): " + byEffect
                + " (horizon-java/mixin-table.tsv)");
        int shown = 0;
        for (var e : inert.entrySet()) {
            if (shown++ >= 80) {
                Natives.log("Mixin table: … " + (inert.size() - 80) + " more methods Horizon does in Rust (see the .tsv)");
                break;
            }
            Map<String, List<String>> perMod = new TreeMap<>();
            for (Patch p : e.getValue()) perMod.computeIfAbsent(p.mod(), k -> new ArrayList<>()).add(p.describe());
            String owner = e.getKey().substring(0, e.getKey().lastIndexOf('.'));
            String method = e.getKey().substring(e.getKey().lastIndexOf('.') + 1);
            Natives.log("Mixin table: Rust does " + simple(owner) + "." + method + " (" + where(owner, method).what() + "): "
                    + perMod.entrySet().stream().map(m -> m.getKey() + " " + String.join(", ", new java.util.TreeSet<>(m.getValue())))
                            .reduce((a, b) -> a + "; " + b).orElse(""));
        }
        try {
            Path out = FabricLoader.getInstance().getGameDir().resolve("horizon-java").resolve("mixin-table.tsv");
            Files.createDirectories(out.getParent());
            Files.writeString(out, tsv);
        } catch (Throwable t) {
            Natives.log("Mixin table: could not write it: " + t);
        }
    }

    private static String simple(String owner) {
        return owner.substring(owner.lastIndexOf('/') + 1);
    }

    /** "Lnet/minecraft/world/level/block/Block;playerDestroy(...)V" as "Block.playerDestroy". */
    static String shortTarget(String target) {
        String t = target;
        int paren = t.indexOf('(');
        if (paren >= 0) t = t.substring(0, paren);
        int semi = t.indexOf(';');
        if (semi >= 0) {
            String owner = t.substring(0, semi);
            owner = owner.substring(owner.lastIndexOf('/') + 1);
            String rest = t.substring(semi + 1);
            int colon = rest.indexOf(':');
            if (colon >= 0) rest = rest.substring(0, colon);
            return owner + "." + rest;
        }
        return t;
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
}
