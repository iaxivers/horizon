package dev.horizonlab.keysmod;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;

/** The client side: G, consumed in END_CLIENT_TICK as mods do, with the player there. */
public class KeysClient implements ClientModInitializer {
    static int held;

    @Override
    public void onInitializeClient() {
        KeyMapping key = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.horizon_keys.ping", InputConstants.KEY_G, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            held = key.isDown() ? held + 1 : 0;
            while (key.consumeClick()) ClientPlayNetworking.send(new KeysMod.Pressed(held));
        });
    }
}
