package dev.horizonlab.platform.mixin;

import java.util.Map;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.RegistryLayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccess {
    @Accessor("playerList")
    void horizon$setPlayerList(PlayerList playerList);

    @Mutable
    @Accessor("registries")
    void horizon$setRegistries(LayeredRegistryAccess<RegistryLayer> registries);

    @Mutable
    @Accessor("worldData")
    void horizon$setWorldData(net.minecraft.world.level.storage.WorldData data);

    @Mutable
    @Accessor("levels")
    void horizon$setLevels(Map<ResourceKey<Level>, ServerLevel> levels);
}
