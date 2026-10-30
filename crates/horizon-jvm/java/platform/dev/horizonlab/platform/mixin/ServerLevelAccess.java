package dev.horizonlab.platform.mixin;

import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface ServerLevelAccess {
    @Mutable
    @Accessor("server")
    void horizon$setServer(MinecraftServer server);

    @Mutable
    @Accessor("sleepStatus")
    void horizon$setSleepStatus(net.minecraft.server.players.SleepStatus status);

    @Mutable
    @Accessor("players")
    void horizon$setPlayers(List<net.minecraft.server.level.ServerPlayer> players);

    @Mutable
    @Accessor("dragonParts")
    void horizon$setDragonParts(it.unimi.dsi.fastutil.ints.Int2ObjectMap<net.minecraft.world.entity.boss.enderdragon.EnderDragonPart> parts);

    @Mutable
    @Accessor("navigatingMobs")
    void horizon$setNavigatingMobs(java.util.Set<net.minecraft.world.entity.Mob> mobs);

    @Mutable
    @Accessor("blockEvents")
    void horizon$setBlockEvents(it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<net.minecraft.world.level.BlockEventData> events);

    @Mutable
    @Accessor("blockEventsToReschedule")
    void horizon$setBlockEventsToReschedule(List<net.minecraft.world.level.BlockEventData> events);

    @Mutable
    @Accessor("customSpawners")
    void horizon$setCustomSpawners(List<net.minecraft.world.level.CustomSpawner> spawners);

    @Mutable
    @Accessor("blockTicks")
    void horizon$setBlockTicks(net.minecraft.world.ticks.LevelTicks<net.minecraft.world.level.block.Block> ticks);

    @Mutable
    @Accessor("fluidTicks")
    void horizon$setFluidTicks(net.minecraft.world.ticks.LevelTicks<net.minecraft.world.level.material.Fluid> ticks);

    @Mutable
    @Accessor("chunkSource")
    void horizon$setChunkSource(net.minecraft.server.level.ServerChunkCache source);
}
