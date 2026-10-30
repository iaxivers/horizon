package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;

/**
 * Packets mods send mirrored players that Horizon sends on as they are: sounds and particles
 * (a mod's mob's steps, hurt and death sounds, its particles). They are encoded with
 * vanilla's own codec, which is Horizon's protocol. Ids of vanilla's registries are Horizon's;
 * a mod's sound goes by name (a direct holder), and a mod's particle type, which Horizon's
 * game can't know, is left out. An entity's sound plays where the entity is (Horizon's game
 * doesn't know mirrored entities' ids).
 */
public final class HorizonPackets {
    private HorizonPackets() {}

    private static net.minecraft.network.ProtocolInfo<net.minecraft.network.protocol.game.ClientGamePacketListener> protocol;

    /** True when the packet went to Horizon (one it sends on), false for the rest. */
    public static boolean forward(ServerPlayer player, Packet<?> packet) {
        Packet<?> out = packet;
        if (packet instanceof ClientboundSoundEntityPacket sound) {
            Entity source = player.level().getEntity(sound.getId());
            if (source == null) return true;
            out = new ClientboundSoundPacket(named(sound.getSound()), sound.getSource(), source.getX(), source.getY(),
                    source.getZ(), sound.getVolume(), sound.getPitch(), sound.getSeed());
        } else if (packet instanceof ClientboundSoundPacket sound) {
            if (!modded(sound.getSound())) {
                out = sound;
            } else {
                out = new ClientboundSoundPacket(named(sound.getSound()), sound.getSource(), sound.getX(), sound.getY(),
                        sound.getZ(), sound.getVolume(), sound.getPitch(), sound.getSeed());
            }
        } else if (packet instanceof ClientboundLevelParticlesPacket particles) {
            if (!BuiltInRegistries.PARTICLE_TYPE.getKey(particles.particle().getType()).getNamespace().equals("minecraft")) {
                return true;
            }
        } else {
            return false;
        }
        send(player, out);
        return true;
    }

    /** To every mirrored player in the level within {@code range} of the point. */
    static void near(ServerLevel level, double x, double y, double z, double range, Packet<?> packet) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) < range * range) send(player, packet);
        }
    }

    private static boolean modded(Holder<SoundEvent> sound) {
        return sound.unwrapKey().map(k -> !k.identifier().getNamespace().equals("minecraft"))
                .orElse(false);
    }

    /** A mod's sound by its name (Horizon's game has no registry id for it). */
    private static Holder<SoundEvent> named(Holder<SoundEvent> sound) {
        return modded(sound) ? Holder.direct(sound.value()) : sound;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void send(ServerPlayer player, Packet<?> packet) {
        try {
            if (protocol == null) {
                protocol = net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(HorizonServer.server().registryAccess()));
            }
            ByteBuf buf = Unpooled.buffer();
            ((net.minecraft.network.codec.StreamCodec) protocol.codec()).encode(buf, packet);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            Natives.packet(player.getStringUUID(), bytes);
        } catch (Throwable t) {
            HorizonServer.fire("sending " + packet.getClass().getSimpleName(), () -> { throw new RuntimeException(t); });
        }
    }
}
