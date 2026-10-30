package dev.horizonlab.platform.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonListenerAccess {
    @Mutable
    @Accessor("server")
    void horizon$setServer(MinecraftServer server);

    @Mutable
    @Accessor("connection")
    void horizon$setConnection(net.minecraft.network.Connection connection);
}
