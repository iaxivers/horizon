package dev.horizonlab.platform.mixin;

import dev.horizonlab.platform.HorizonClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The headless client's camera (GameRenderer.mainCamera, which HUD mods read: Jade's ray) is
 * at the eyes of the player whose client runs now, looking where they look, in first person.
 * Nothing renders, so it is never set up: it answers from the player.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    private static Entity horizon$player() {
        return HorizonClient.headless() ? Minecraft.getInstance().player : null;
    }

    @Inject(method = "position", at = @At("HEAD"), cancellable = true)
    private void horizon$position(CallbackInfoReturnable<Vec3> cir) {
        Entity player = horizon$player();
        if (player != null) cir.setReturnValue(player.getEyePosition());
    }

    @Inject(method = "blockPosition", at = @At("HEAD"), cancellable = true)
    private void horizon$blockPosition(CallbackInfoReturnable<BlockPos> cir) {
        Entity player = horizon$player();
        if (player != null) cir.setReturnValue(BlockPos.containing(player.getEyePosition()));
    }

    @Inject(method = "entity", at = @At("HEAD"), cancellable = true)
    private void horizon$entity(CallbackInfoReturnable<Entity> cir) {
        Entity player = horizon$player();
        if (player != null) cir.setReturnValue(player);
    }

    @Inject(method = "forwardVector", at = @At("HEAD"), cancellable = true)
    private void horizon$forward(CallbackInfoReturnable<Vector3fc> cir) {
        Entity player = horizon$player();
        if (player != null) cir.setReturnValue(player.getViewVector(1f).toVector3f());
    }

    @Inject(method = "xRot", at = @At("HEAD"), cancellable = true)
    private void horizon$xRot(CallbackInfoReturnable<Float> cir) {
        Entity player = horizon$player();
        if (player != null) cir.setReturnValue(player.getXRot());
    }

    @Inject(method = {"yRot", "yaw"}, at = @At("HEAD"), cancellable = true)
    private void horizon$yRot(CallbackInfoReturnable<Float> cir) {
        Entity player = horizon$player();
        if (player != null) cir.setReturnValue(player.getYRot());
    }

    @Inject(method = {"isDetached"}, at = @At("HEAD"), cancellable = true)
    private void horizon$detached(CallbackInfoReturnable<Boolean> cir) {
        if (horizon$player() != null) cir.setReturnValue(false);
    }

    @Inject(method = {"isInitialized"}, at = @At("HEAD"), cancellable = true)
    private void horizon$initialized(CallbackInfoReturnable<Boolean> cir) {
        if (horizon$player() != null) cir.setReturnValue(true);
    }
}
