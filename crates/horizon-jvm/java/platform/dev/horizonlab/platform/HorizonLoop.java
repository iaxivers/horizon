package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.client.ClientNetworkingImpl;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The mods' own messages between their two halves, both in this JVM: what a mod's server code
 * sends a player (ServerPlayNetworking.send) reaches its client code's receiver for that
 * player's headless client, and what its client code sends (ClientPlayNetworking.send) reaches
 * its server receiver as that player's. The payload objects go over as they are, never
 * encoded: one process, one copy of every class.
 *
 * <p>Fabric runs play receivers on the receiving side's main thread; both sides' are Horizon's
 * server thread here. Server messages wait for the end of what sent them (flush), as packets
 * do; client messages run at once, as the server reads them next.
 */
public final class HorizonLoop {
    private HorizonLoop() {}

    /** Whether the loop is up: the headless client runs (the game's own JVM, not a server's). */
    public static boolean on() {
        return HorizonClient.headless();
    }

    /** The channels the mods' client code receives on: what a server may send a player. */
    public static Set<Identifier> clientChannels() {
        return ClientNetworkingImpl.PLAY.getChannels();
    }

    /** The channels the mods' server code receives on: what a client may send. */
    public static Set<Identifier> serverChannels() {
        return ServerNetworkingImpl.PLAY.getChannels();
    }

    /**
     * What the server sent the clients, in order, for after the server's changes show: as a
     * vanilla client reads its packets once they arrive, after the ones before (a menu's
     * opening, then its data: the screen is up when the data comes).
     */
    private static final java.util.Queue<Runnable> OUTBOX = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Delivers what the server sent (HorizonServer.syncInventories' end). */
    public static void flush() {
        for (Runnable delivery; (delivery = OUTBOX.poll()) != null; ) delivery.run();
    }

    /** Server to client: whether a mod's client code receives it (it is then delivered at flush). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean toClient(ServerPlayer player, CustomPacketPayload payload) {
        if (!on()) return false;
        var handler = (ClientPlayNetworking.PlayPayloadHandler) ClientNetworkingImpl.PLAY.getHandler(payload.type().id());
        if (handler == null) return false;
        Runnable delivery = () -> {
            try {
                HorizonClient.as(player, () -> handler.receive(payload, clientContext(player)));
            } catch (Throwable t) {
                log("the client of " + payload.type().id() + " failed", t);
            }
        };
        OUTBOX.add(delivery);
        return true;
    }

    /** Client to server, for the player whose client runs now (Minecraft.player). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void toServer(CustomPacketPayload payload) {
        ServerPlayer player = HorizonClient.serverPlayer(Minecraft.getInstance().player);
        if (player == null) {
            Natives.log("Java mods: " + payload.type().id() + " was sent with no player's client running; dropped");
            return;
        }
        var handler = (ServerPlayNetworking.PlayPayloadHandler) ServerNetworkingImpl.PLAY.getHandler(payload.type().id());
        if (handler == null) return;
        try {
            handler.receive(payload, serverContext(player));
            // What the mod's server code changed shows as the server's other changes do.
            HorizonServer.afterLoop();
        } catch (Throwable t) {
            log("the server side of " + payload.type().id() + " failed", t);
        }
    }

    private static ClientPlayNetworking.Context clientContext(ServerPlayer player) {
        return new ClientPlayNetworking.Context() {
            @Override
            public Minecraft client() {
                return Minecraft.getInstance();
            }

            @Override
            public LocalPlayer player() {
                return Minecraft.getInstance().player;
            }

            @Override
            public PacketSender responseSender() {
                return clientSender();
            }
        };
    }

    private static ServerPlayNetworking.Context serverContext(ServerPlayer player) {
        return new ServerPlayNetworking.Context() {
            @Override
            public MinecraftServer server() {
                return player.level().getServer();
            }

            @Override
            public ServerPlayer player() {
                return player;
            }

            @Override
            public PacketSender responseSender() {
                return serverSender(player);
            }
        };
    }

    /** The client's sender (ClientPlayNetworking.getSender, a context's response sender). */
    public static PacketSender clientSender() {
        return new Sender() {
            @Override
            public Packet<?> createPacket(CustomPacketPayload payload) {
                return new ServerboundCustomPayloadPacket(payload);
            }

            @Override
            void send(CustomPacketPayload payload) {
                toServer(payload);
            }
        };
    }

    /** A player's server sender (ServerPlayNetworking.getSender, a context's response sender). */
    public static PacketSender serverSender(ServerPlayer player) {
        return new Sender() {
            @Override
            public Packet<?> createPacket(CustomPacketPayload payload) {
                return new ClientboundCustomPayloadPacket(payload);
            }

            @Override
            void send(CustomPacketPayload payload) {
                HorizonServer.payload(player, payload);
            }
        };
    }

    /** A sender whose packets are custom payloads, delivered in this JVM. */
    private abstract static class Sender implements PacketSender {
        abstract void send(CustomPacketPayload payload);

        @Override
        public void sendPacket(Packet<?> packet, io.netty.channel.ChannelFutureListener callback) {
            if (packet instanceof ServerboundCustomPayloadPacket(CustomPacketPayload payload)) send(payload);
            else if (packet instanceof ClientboundCustomPayloadPacket(CustomPacketPayload payload)) send(payload);
            else HorizonServer.dropped(packet);
        }

        @Override
        public void sendPacket(CustomPacketPayload payload) {
            send(payload);
        }

        @Override
        public void disconnect(Component reason) {
            Natives.log("Java mods: a mod asked to disconnect a player: " + reason.getString());
        }
    }

    private static void log(String what, Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        Natives.log("Java mods: " + what + ": " + root);
        int n = 0;
        for (StackTraceElement frame : root.getStackTrace()) {
            if (n++ == 12) break;
            Natives.log("    at " + frame);
        }
    }
}
