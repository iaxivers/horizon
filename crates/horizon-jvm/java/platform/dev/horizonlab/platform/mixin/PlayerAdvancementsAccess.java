package dev.horizonlab.platform.mixin;

import java.util.Map;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A mirrored player's advancements: Horizon keeps the real ones, so no trigger listens here. */
@Mixin(PlayerAdvancements.class)
public interface PlayerAdvancementsAccess {
    @Mutable
    @Accessor("activeTriggers")
    void horizon$setActiveTriggers(Map<?, ?> triggers);
}
