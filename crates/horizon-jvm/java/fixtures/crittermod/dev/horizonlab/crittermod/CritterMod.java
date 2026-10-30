package dev.horizonlab.crittermod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

/** The critter's type, attributes and spawn egg, as a mob mod registers them. */
public final class CritterMod implements ModInitializer {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("horizon_critters", "critter");
    public static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(Registries.ENTITY_TYPE, ID);
    public static final EntityType<Critter> CRITTER = Registry.register(BuiltInRegistries.ENTITY_TYPE, KEY,
            EntityType.Builder.of(Critter::new, MobCategory.CREATURE).sized(0.8F, 0.9F).build(KEY));

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(CRITTER, Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 8.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25));
        ResourceKey<Item> egg = ResourceKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath("horizon_critters", "critter_spawn_egg"));
        Registry.register(BuiltInRegistries.ITEM, egg, new SpawnEggItem(new Item.Properties().spawnEgg(CRITTER).setId(egg)));
    }
}
