package dev.horizonlab.geomod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** A wandering "spinner" mob and a "geo block" with a block entity, both drawn as GeckoLib does. */
public final class GeoMod implements ModInitializer {
    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("horizon_geo", path);
    }

    static final ResourceKey<EntityType<?>> SPINNER_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("spinner"));
    public static final EntityType<Spinner> SPINNER = Registry.register(BuiltInRegistries.ENTITY_TYPE, SPINNER_KEY,
            EntityType.Builder.of(Spinner::new, MobCategory.CREATURE).sized(0.6F, 1.0F).build(SPINNER_KEY));

    static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, id("geo_block"));
    public static final Block GEO_BLOCK = Registry.register(BuiltInRegistries.BLOCK, BLOCK_KEY,
            new GeoBlock(BlockBehaviour.Properties.of().strength(1.0F).noOcclusion().setId(BLOCK_KEY)));
    public static final BlockEntityType<GeoBlockEntity> GEO_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, id("geo_block"),
            new BlockEntityType<>(GeoBlockEntity::new, java.util.Set.of(GEO_BLOCK)));

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(SPINNER, Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 6.0)
                .add(Attributes.MOVEMENT_SPEED, 0.2));
        ResourceKey<Item> item = ResourceKey.create(Registries.ITEM, id("geo_block"));
        Registry.register(BuiltInRegistries.ITEM, item, new BlockItem(GEO_BLOCK, new Item.Properties().setId(item)));
    }

    /** It wanders; its look is all its renderer's. */
    public static final class Spinner extends PathfinderMob {
        public Spinner(EntityType<? extends Spinner> type, Level level) {
            super(type, level);
        }

        @Override
        protected void registerGoals() {
            goalSelector.addGoal(5, new RandomStrollGoal(this, 1.0, 20));
        }
    }

    public static final class GeoBlock extends Block implements EntityBlock {
        GeoBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new GeoBlockEntity(pos, state);
        }
    }

    public static final class GeoBlockEntity extends BlockEntity {
        public GeoBlockEntity(BlockPos pos, BlockState state) {
            super(GEO_BLOCK_ENTITY, pos, state);
        }
    }
}
