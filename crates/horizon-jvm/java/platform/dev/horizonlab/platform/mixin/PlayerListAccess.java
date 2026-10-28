package dev.horizonlab.platform.mixin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerList.class)
public interface PlayerListAccess {
    @Mutable
    @Accessor("players")
    void horizon$setPlayers(List<ServerPlayer> players);

    @Mutable
    @Accessor("playersByUUID")
    void horizon$setPlayersByUUID(Map<UUID, ServerPlayer> players);

    @Mutable
    @Accessor("server")
    void horizon$setServer(MinecraftServer server);
}
