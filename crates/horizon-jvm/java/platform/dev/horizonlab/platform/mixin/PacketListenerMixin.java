package dev.horizonlab.platform.mixin;

import dev.horizonlab.bridge.Natives;
import dev.horizonlab.platform.HorizonServer;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mirrored player has no connection: Horizon talks to the real one. Packets mods send it go
 * to Horizon when Horizon knows what to do with them (a system message), and are dropped
 * otherwise (counted, see HorizonServer.dropped).
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PacketListenerMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void horizon$send(Packet<?> packet, CallbackInfo ci) {
        horizon$route(packet, ci);
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void horizon$send(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        horizon$route(packet, ci);
    }

    private void horizon$route(Packet<?> packet, CallbackInfo ci) {
        Object self = this;
        if (!HorizonServer.isMirror(self)) return;
        ci.cancel();
        if (packet instanceof ClientboundSystemChatPacket chat && self instanceof ServerGamePacketListenerImpl game
                && game.player != null && !chat.overlay()) {
            Natives.tell(game.player.getStringUUID(), HorizonServer.chatJson(chat.content()));
            return;
        }
        if (packet instanceof ClientboundOpenScreenPacket open && self instanceof ServerGamePacketListenerImpl game
                && game.player != null) {
            var type = net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(open.getType());
            String name = type == null ? "" : type.toString();
            String title = HorizonServer.json(open.getTitle());
            if (HorizonServer.horizonMenu(name, title)) {
                // A menu Horizon runs itself (a crafting table, an ender chest).
                Natives.openMenu(game.player.getStringUUID(), name, title);
                HorizonServer.forwarded(game.player, open.getContainerId());
            } else {
                // The mod's own menu (a cabinet's): Horizon shows it, Java runs it.
                HorizonServer.menuOpened(game.player, open.getContainerId(), name, title, open.getTitle());
            }
            return;
        }
        if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                && self instanceof ServerGamePacketListenerImpl game && game.player != null
                && custom.payload().getClass().getName().equals("net.fabricmc.fabric.impl.menu.Networking$OpenScreenPayload")) {
            // Fabric API's ExtendedMenuProvider opens its menus with a payload of its own
            // (the menu's data for the client's screen): shown as vanilla's are.
            HorizonServer.payload(game.player, custom.payload());
            return;
        }
        if (self instanceof ServerGamePacketListenerImpl game && game.player != null
                && HorizonServer.isShown(game.player, containerId(packet))) {
            // A shown menu's content: HorizonServer.syncMenus sends it whole.
            return;
        }
        if (self instanceof ServerGamePacketListenerImpl game && game.player != null
                && HorizonServer.isForwarded(game.player, containerId(packet))) {
            // The forwarded menu's content: Horizon's menu has its own.
            return;
        }
        if (packet instanceof ClientboundPlayerAbilitiesPacket abilities && self instanceof ServerGamePacketListenerImpl game
                && game.player != null) {
            int flags = (abilities.isInvulnerable() ? 1 : 0) | (abilities.isFlying() ? 2 : 0)
                    | (abilities.canFly() ? 4 : 0) | (abilities.canInstabuild() ? 8 : 0);
            Natives.abilities(game.player.getStringUUID(), game.player.gameMode().getId(), flags,
                    abilities.getFlyingSpeed(), abilities.getWalkingSpeed());
            return;
        }
        if (packet instanceof ClientboundPlayerInfoUpdatePacket info
                && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)) {
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.entries()) {
                Natives.tabName(entry.profileId().toString(), HorizonServer.json(entry.displayName()));
            }
            if (info.actions().size() == 1) return;
        }
        HorizonServer.dropped(packet);
    }

    /** The window a container packet is for, or -1. */
    private static int containerId(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket p) return p.containerId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket p) return p.getContainerId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket p) return p.getContainerId();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundContainerClosePacket p) return p.getContainerId();
        return -1;
    }
}
