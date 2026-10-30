package dev.horizonlab.keysmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The server side: a player's client says its key was pressed (held: how many ticks so far). */
public class KeysMod implements ModInitializer {
    public record Pressed(int held) implements CustomPacketPayload {
        public static final Type<Pressed> TYPE = new Type<>(Identifier.fromNamespaceAndPath("horizon_keys", "pressed"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Pressed> CODEC =
                ByteBufCodecs.VAR_INT.map(Pressed::new, Pressed::held).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(Pressed.TYPE, Pressed.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Pressed.TYPE, (payload, context) ->
                context.player().sendSystemMessage(Component.literal(
                        "key G pressed by " + context.player().getScoreboardName() + " (down " + payload.held() + " ticks)")));
    }
}
