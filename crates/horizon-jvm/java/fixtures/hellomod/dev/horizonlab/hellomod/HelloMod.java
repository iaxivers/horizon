package dev.horizonlab.hellomod;

import dev.horizonlab.bridge.Natives;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The smallest real Fabric mod for Horizon: no Mixins, two entrypoints that say they ran.
 * Its jar goes in mods-java/ to check the bridge on the real game (tests/fabric.rs writes it).
 */
public class HelloMod implements ModInitializer, ClientModInitializer {
    @Override
    public void onInitialize() {
        Natives.log("Hello from a Fabric mod! Running on Minecraft "
                + FabricLoader.getInstance().getModContainer("minecraft").get().getMetadata().getVersion().getFriendlyString()
                + ", " + FabricLoader.getInstance().getAllMods().size() + " mods loaded");
    }

    @Override
    public void onInitializeClient() {
        Natives.log("Hello from a Fabric mod's client entrypoint!");
    }
}
