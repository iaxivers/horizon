package dev.horizonlab.platform.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Level.class)
public interface LevelAccess {
    @Mutable
    @Accessor("dimension")
    void horizon$setDimension(ResourceKey<Level> dimension);

    @Mutable
    @Accessor("isClientSide")
    void horizon$setClientSide(boolean clientSide);

    // What a level's constructor would have set: a mirror is made without it.
    @Mutable
    @Accessor("registryAccess")
    void horizon$setRegistryAccess(RegistryAccess registries);

    @Mutable
    @Accessor("dimensionTypeRegistration")
    void horizon$setDimensionType(Holder<DimensionType> type);

    @Mutable
    @Accessor("damageSources")
    void horizon$setDamageSources(DamageSources sources);

    @Mutable
    @Accessor("levelData")
    void horizon$setLevelData(net.minecraft.world.level.storage.WritableLevelData data);

    @Mutable
    @Accessor("random")
    void horizon$setRandom(RandomSource random);
}
