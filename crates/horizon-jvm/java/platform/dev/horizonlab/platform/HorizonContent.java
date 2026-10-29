package dev.horizonlab.platform;

import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Java mods' blocks and items, for Horizon's content (docs/modpacks.md, registries 0.2):
 * the oracle facts horizon-port-pack's carry-over reads (oracle.rs), written from the bridge's
 * own running game. It is upstream's NeoForge oracle (mods/horizon/src/neoforge-oracle) on 26.3
 * and Fabric, read-only: the mirrored level's blocks are Horizon's world, so nothing is placed.
 *
 * <p>Every block and item outside {@code minecraft} (and the loaders' own), with vanilla's
 * blocks beside them so templates can be matched; the tags that hold them, and the {@code c}
 * convention tags whole.
 */
public final class HorizonContent {
    private HorizonContent() {}

    /** Namespaces that are no mod's content: vanilla, the loaders', conventions, Horizon's. */
    private static final Set<String> NOT_CONTENT = Set.of("minecraft", "c", "horizon", "fabric", "neoforge", "forge");

    /** Writes the facts to {@code out}; the mods' namespaces it found, comma separated. */
    public static String exportFacts(String out) throws IOException {
        Thread.currentThread().setContextClassLoader(HorizonContent.class.getClassLoader());
        HorizonServer.ensureServer();
        Set<String> namespaces = new TreeSet<>();
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) namespaces.add(id.getNamespace());
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) namespaces.add(id.getNamespace());
        namespaces.removeAll(NOT_CONTENT);
        Path path = Path.of(out);
        Files.createDirectories(path.getParent());
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8); JsonWriter json = new JsonWriter(w)) {
            new Export(HorizonServer.level("minecraft:overworld"), namespaces, json).run();
        }
        return String.join(",", namespaces);
    }

    private static final class Export {
        private static final BlockPos FLOOR = new BlockPos(0, 99, 0);
        final ServerLevel level;
        final Set<String> namespaces;
        final JsonWriter json;
        final List<ItemStack> tools = new ArrayList<>();
        final List<String> toolNames = new ArrayList<>();

        Export(ServerLevel level, Set<String> namespaces, JsonWriter json) {
            this.level = level;
            this.namespaces = namespaces;
            this.json = json;
            tool("hand", ItemStack.EMPTY);
            tool("diamond_pickaxe", new ItemStack(Items.DIAMOND_PICKAXE));
            tool("diamond_axe", new ItemStack(Items.DIAMOND_AXE));
            tool("diamond_shovel", new ItemStack(Items.DIAMOND_SHOVEL));
            tool("diamond_hoe", new ItemStack(Items.DIAMOND_HOE));
            tool("shears", new ItemStack(Items.SHEARS));
            ItemStack silk = new ItemStack(Items.DIAMOND_PICKAXE);
            silk.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1);
            tool("silk_touch_pickaxe", silk);
        }

        void tool(String name, ItemStack stack) {
            toolNames.add(name);
            tools.add(stack);
        }

        boolean ours(Identifier id) {
            return namespaces.contains(id.getNamespace());
        }

        void run() throws IOException {
            json.beginObject();
            json.name("schema").value(1);
            json.name("minecraft").value(net.minecraft.SharedConstants.getCurrentVersion().name());
            json.name("namespaces");
            json.beginArray();
            for (String n : namespaces) json.value(n);
            json.endArray();
            json.name("tools");
            json.beginArray();
            for (String n : toolNames) json.value(n);
            json.endArray();
            blocks();
            json.name("vanilla_items");
            json.beginArray();
            for (Item item : BuiltInRegistries.ITEM) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                if (id.getNamespace().equals("minecraft")) json.value(id.toString());
            }
            json.endArray();
            items();
            tags();
            json.endObject();
        }

        void blocks() throws IOException {
            json.name("blocks");
            json.beginObject();
            for (Block block : BuiltInRegistries.BLOCK) {
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                boolean mod = ours(id);
                if (!mod && !id.getNamespace().equals("minecraft")) continue;
                json.name(id.toString());
                json.beginObject();
                json.name("class");
                json.beginArray();
                for (Class<?> c = block.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    json.value(c.getName());
                }
                json.endArray();
                BlockState def = block.defaultBlockState();
                json.name("properties");
                json.beginObject();
                for (Property<?> p : block.getStateDefinition().getProperties()) {
                    json.name(p.getName());
                    json.beginArray();
                    for (Object v : p.getPossibleValues()) json.value(name(p, v));
                    json.endArray();
                }
                json.endObject();
                json.name("default").value(stateKey(def));
                json.name("item").value(BuiltInRegistries.ITEM.getKey(block.asItem()).toString());
                json.name("explosion_resistance").value(block.getExplosionResistance());
                json.name("friction").value(block.getFriction());
                json.name("speed_factor").value(block.getSpeedFactor());
                json.name("jump_factor").value(block.getJumpFactor());
                blockSetType(block);
                json.name("states");
                json.beginObject();
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    json.name(stateKey(state));
                    json.beginObject();
                    json.name("hardness").value(state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
                    json.name("emission").value(state.getLightEmission());
                    json.name("requires_tool").value(state.requiresCorrectToolForDrops());
                    json.name("random_ticks").value(state.isRandomlyTicking());
                    json.name("can_occlude").value(state.canOcclude());
                    json.name("replaceable").value(state.canBeReplaced());
                    var color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                    json.name("map_color").value(color.col);
                    json.name("map_color_id").value(color.id);
                    sound(state.getSoundType());
                    json.name("outline");
                    boxes(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
                    json.name("collision");
                    boxes(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
                    if (mod && lootSample(block, state)) drops(state);
                    json.endObject();
                }
                json.endObject();
                if (mod && block.asItem() instanceof BlockItem bi) placement(bi);
                json.endObject();
            }
            json.endObject();
        }

        /** Every state of a small block; else the default and each one-property change from it. */
        boolean lootSample(Block block, BlockState state) {
            if (block.getStateDefinition().getPossibleStates().size() <= 32) return true;
            BlockState def = block.defaultBlockState();
            int differ = 0;
            for (Property<?> p : block.getStateDefinition().getProperties()) {
                if (!state.getValue(p).equals(def.getValue(p))) differ++;
            }
            return differ <= 1;
        }

        void drops(BlockState state) throws IOException {
            json.name("drops");
            json.beginObject();
            for (int i = 0; i < tools.size(); i++) {
                Map<String, Integer> counts = new TreeMap<>();
                try {
                    LootParams.Builder params = new LootParams.Builder(level)
                            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(FLOOR.above()))
                            .withParameter(LootContextParams.TOOL, tools.get(i).copy());
                    for (ItemStack s : state.getDrops(params)) {
                        counts.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
                    }
                } catch (RuntimeException e) {
                    // A loot table that needs more than a level and a tool: no drops recorded.
                }
                json.name(toolNames.get(i));
                json.beginObject();
                for (Map.Entry<String, Integer> e : counts.entrySet()) json.name(e.getKey()).value(e.getValue());
                json.endObject();
            }
            json.endObject();
        }

        /** The state placing the block on the top of a block, and on the side of one, gives. */
        void placement(BlockItem item) throws IOException {
            BlockPos at = FLOOR.above();
            json.name("placement");
            json.beginObject();
            for (Direction face : new Direction[] {Direction.UP, Direction.NORTH}) {
                BlockPos clicked = face == Direction.UP ? FLOOR : at.relative(face.getOpposite());
                Vec3 hit = Vec3.atCenterOf(clicked).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
                BlockState placed = null;
                try {
                    // A player-less context (the constructor is protected, hence the subclass).
                    BlockPlaceContext ctx = new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND,
                            new ItemStack(item), new BlockHitResult(hit, face, clicked, false)) {};
                    placed = item.getBlock().getStateForPlacement(ctx);
                } catch (RuntimeException e) {
                    // Needs a player: recorded as no placement.
                }
                json.name(face.getName()).value(placed == null ? null : stateKey(placed));
            }
            json.endObject();
        }

        /** The BlockSetType (doors, trapdoors, buttons, plates) and WoodType (fence gates) a block keeps. */
        void blockSetType(Block block) throws IOException {
            String setType = null, woodType = null;
            Boolean byHand = null;
            for (Class<?> c = block.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (var f : c.getDeclaredFields()) {
                    try {
                        if (f.getType() == net.minecraft.world.level.block.state.properties.BlockSetType.class && setType == null) {
                            f.setAccessible(true);
                            var t = (net.minecraft.world.level.block.state.properties.BlockSetType) f.get(block);
                            if (t != null) {
                                setType = t.name();
                                byHand = t.canOpenByHand();
                            }
                        } else if (f.getType() == net.minecraft.world.level.block.state.properties.WoodType.class && woodType == null) {
                            f.setAccessible(true);
                            var t = (net.minecraft.world.level.block.state.properties.WoodType) f.get(block);
                            if (t != null) woodType = t.name();
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        // Not readable: left out.
                    }
                }
            }
            if (setType != null) {
                json.name("block_set_type");
                json.beginObject();
                json.name("name").value(setType);
                json.name("can_open_by_hand").value(byHand);
                json.endObject();
            }
            if (woodType != null) json.name("wood_type").value(woodType);
        }

        void sound(SoundType s) throws IOException {
            json.name("sound");
            json.beginObject();
            json.name("volume").value(s.getVolume());
            json.name("pitch").value(s.getPitch());
            event("break", s.getBreakSound());
            event("step", s.getStepSound());
            event("place", s.getPlaceSound());
            event("hit", s.getHitSound());
            event("fall", s.getFallSound());
            json.endObject();
        }

        void event(String name, SoundEvent e) throws IOException {
            json.name(name).value(e.location().toString());
        }

        void boxes(VoxelShape shape) throws IOException {
            json.beginArray();
            for (AABB b : shape.toAabbs()) {
                json.beginArray();
                json.value(b.minX).value(b.minY).value(b.minZ).value(b.maxX).value(b.maxY).value(b.maxZ);
                json.endArray();
            }
            json.endArray();
        }

        static final com.google.gson.Gson GSON = new com.google.gson.Gson();

        static <T> com.google.gson.JsonElement encode(net.minecraft.core.component.TypedDataComponent<T> component,
                com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
            var codec = component.type().codec();
            if (codec == null) return null;
            return codec.encodeStart(ops, component.value()).result().orElse(null);
        }

        void items() throws IOException {
            json.name("items");
            json.beginObject();
            for (Item item : BuiltInRegistries.ITEM) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                if (!ours(id)) continue;
                ItemStack stack = new ItemStack(item);
                json.name(id.toString());
                json.beginObject();
                json.name("class").value(item.getClass().getName());
                json.name("max_stack_size").value(stack.getMaxStackSize());
                json.name("rarity").value(stack.getRarity().getSerializedName());
                if (item instanceof BlockItem bi) {
                    json.name("block").value(BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString());
                }
                var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
                if (food != null) {
                    json.name("food");
                    json.beginObject();
                    json.name("nutrition").value(food.nutrition());
                    json.name("saturation").value(food.saturation());
                    json.endObject();
                }
                // Every default component in its JSON form (the ones without a codec are runtime only).
                json.name("components");
                json.beginObject();
                var ops = level.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                for (var component : item.components()) {
                    var encoded = encode(component, ops);
                    if (encoded == null) continue;
                    json.name(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type()).toString());
                    json.jsonValue(GSON.toJson(encoded));
                }
                // A food's crafting remainder (a stew's bowl) is what its mod gives back on
                // eating (Farmer's Delight's ConsumableItem): Horizon's use_remainder.
                var remainder = item.getCraftingRemainder();
                if (remainder != null && stack.has(net.minecraft.core.component.DataComponents.FOOD)
                        && !stack.has(net.minecraft.core.component.DataComponents.USE_REMAINDER)) {
                    var use = new net.minecraft.world.item.component.UseRemainder(remainder);
                    net.minecraft.core.component.DataComponents.USE_REMAINDER.codecOrThrow()
                            .encodeStart(ops, use).result().ifPresent(e -> {
                                try {
                                    json.name("minecraft:use_remainder");
                                    json.jsonValue(GSON.toJson(e));
                                } catch (IOException io) {
                                    throw new java.io.UncheckedIOException(io);
                                }
                            });
                }
                json.endObject();
                json.endObject();
            }
            json.endObject();
        }

        /** Tags that hold one of our ids, with only those members; and every `c` tag, whole. */
        void tags() throws IOException {
            json.name("tags");
            json.beginObject();
            tagsOf("block", BuiltInRegistries.BLOCK);
            tagsOf("item", BuiltInRegistries.ITEM);
            json.endObject();
        }

        <T> void tagsOf(String kind, net.minecraft.core.Registry<T> registry) throws IOException {
            json.name(kind);
            json.beginObject();
            Map<String, Set<String>> out = new TreeMap<>();
            registry.getTags().forEach(named -> {
                Identifier tag = named.key().location();
                boolean convention = tag.getNamespace().equals("c");
                Set<String> members = new LinkedHashSet<>();
                for (var holder : named) {
                    Identifier id = holder.unwrapKey().orElseThrow().identifier();
                    if (convention || ours(id) || ours(tag)) members.add(id.toString());
                }
                if (!members.isEmpty()) out.put(tag.toString(), new TreeSet<>(members));
            });
            for (var e : out.entrySet()) {
                json.name(e.getKey());
                json.beginArray();
                for (String m : e.getValue()) json.value(m);
                json.endArray();
            }
            json.endObject();
        }

        static String stateKey(BlockState state) {
            StringBuilder b = new StringBuilder();
            for (Property<?> p : state.getProperties()) {
                if (b.length() > 0) b.append(',');
                b.append(p.getName()).append('=').append(name(p, state.getValue(p)));
            }
            return b.toString();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static String name(Property p, Object v) {
            return p.getName((Comparable) v);
        }
    }
}
