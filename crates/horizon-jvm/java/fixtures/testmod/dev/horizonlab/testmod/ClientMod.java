package dev.horizonlab.testmod;

import dev.horizonlab.bridge.Natives;
import net.fabricmc.api.ClientModInitializer;

public class ClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Natives.log("horizon_test: onInitializeClient ran");
    }
}
