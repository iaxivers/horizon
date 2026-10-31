package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonProfile;
import dev.horizonlab.platform.HorizonWorldgen;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The noise fill of a chunk the mods' worldgen makes (HorizonWorldgen): Horizon's own noise
 * (the same router, aquifers and ore veins) filled it already when it handed the proto over,
 * so doFill's sampling is skipped. Surface rules and carvers still run here, on the mods'
 * biomes. With HORIZON_JAVA_PROFILE, buildTerrain's parts are timed.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseFillMixin {
    private static final ThreadLocal<long[]> horizon$started = ThreadLocal.withInitial(() -> new long[4]);

    @Inject(method = "doFill", at = @At("HEAD"), cancellable = true)
    private void horizon$fill(NoiseChunk noise, ChunkAccess chunk, CallbackInfo ci) {
        long started = HorizonProfile.start();
        if (HorizonWorldgen.fill(chunk)) {
            HorizonProfile.add("worldgen terrain: fill (Horizon's noise)", started);
            ci.cancel();
        } else {
            horizon$started.get()[0] = started;
        }
    }

    @Inject(method = "doFill", at = @At("TAIL"))
    private void horizon$check(NoiseChunk noise, ChunkAccess chunk, CallbackInfo ci) {
        HorizonProfile.add("worldgen terrain: fill (Java's noise)", horizon$started.get()[0]);
        HorizonWorldgen.check(chunk);
    }

    @Inject(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Ljava/util/Set;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;)V",
            at = @At("HEAD"))
    private void horizon$surfaceStarts(CallbackInfo ci) {
        horizon$started.get()[1] = HorizonProfile.start();
    }

    @Inject(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Ljava/util/Set;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;)V",
            at = @At("TAIL"))
    private void horizon$surfaceEnds(CallbackInfo ci) {
        HorizonProfile.add("worldgen terrain: surface", horizon$started.get()[1]);
    }

    @Inject(method = "createNoiseChunk", at = @At("HEAD"))
    private void horizon$noiseChunkStarts(CallbackInfoReturnable<NoiseChunk> cir) {
        horizon$started.get()[3] = HorizonProfile.start();
    }

    @Inject(method = "createNoiseChunk", at = @At("RETURN"))
    private void horizon$noiseChunkEnds(CallbackInfoReturnable<NoiseChunk> cir) {
        HorizonProfile.add("worldgen terrain: noise chunk", horizon$started.get()[3]);
    }

    @Inject(method = "generateCarvers", at = @At("HEAD"))
    private void horizon$carversStart(CallbackInfo ci) {
        horizon$started.get()[2] = HorizonProfile.start();
    }

    @Inject(method = "generateCarvers", at = @At("TAIL"))
    private void horizon$carversEnd(CallbackInfo ci) {
        HorizonProfile.add("worldgen terrain: carvers", horizon$started.get()[2]);
    }
}
