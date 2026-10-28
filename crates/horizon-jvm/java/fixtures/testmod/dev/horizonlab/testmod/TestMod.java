package dev.horizonlab.testmod;

import dev.horizonlab.bridge.Natives;
import net.fabricmc.api.ModInitializer;

/** The Fabric test mod's entrypoint: says it ran, through Horizon's log. */
public class TestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        Natives.log("horizon_test: onInitialize ran");
    }
}
