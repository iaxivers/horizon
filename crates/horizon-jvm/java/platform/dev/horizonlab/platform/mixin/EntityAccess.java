package dev.horizonlab.platform.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityAccess {
    @Accessor("level")
    void horizon$setLevel(Level level);

    // What an entity's constructor would have set: a mirror is made without it.
    @Accessor("position")
    void horizon$setPosition(Vec3 position);

    @Accessor("blockPosition")
    void horizon$setBlockPosition(BlockPos position);

    @Accessor("chunkPosition")
    void horizon$setChunkPosition(ChunkPos position);

    @Accessor("deltaMovement")
    void horizon$setDeltaMovement(Vec3 movement);

    @Accessor("levelCallback")
    void horizon$setLevelCallback(EntityInLevelCallback callback);
}
