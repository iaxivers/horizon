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
}
