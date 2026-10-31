package dev.horizonlab.geomod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Renderers that write their own vertices, as GeckoLib's GeoRenderer does: no ModelPart, a
 * submitCustomGeometry per pass with boxes ("bones") posed from the entity's age.
 */
public final class GeoClient implements ClientModInitializer {
    static final Identifier TEXTURE = GeoMod.id("textures/entity/geo.png");

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(GeoMod.SPINNER, SpinnerRenderer::new);
        BlockEntityRendererRegistry.register(GeoMod.GEO_BLOCK_ENTITY, context -> new GeoBlockRenderer());
    }

    /** A box of 6 quads, each face the whole texture, coloured. */
    static void box(PoseStack.Pose pose, VertexConsumer out, float x0, float y0, float z0, float x1, float y1, float z1,
                    int argb, int light) {
        float[][] faces = {
            {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1},
            {x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1},
            {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0},
            {x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0},
            {x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0},
            {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0},
        };
        float[][] uvs = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
        for (float[] f : faces) {
            for (int i = 0; i < 4; i++) {
                out.addVertex(pose, f[i * 3], f[i * 3 + 1], f[i * 3 + 2]).setColor(argb).setUv(uvs[i][0], uvs[i][1])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, f[12], f[13], f[14]);
            }
        }
    }

    /** A body, a bobbing head and two arms that swing, from the entity's age. */
    static final class SpinnerRenderer extends EntityRenderer<GeoMod.Spinner, EntityRenderState> {
        SpinnerRenderer(EntityRendererProvider.Context context) {
            super(context);
            shadowRadius = 0.3F;
        }

        @Override
        public EntityRenderState createRenderState() {
            return new EntityRenderState();
        }

        @Override
        public void submit(EntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
            float age = state.ageInTicks;
            int light = state.lightCoords;
            pose.pushPose();
            pose.rotateDegrees(Axis.YP, age * 3.0F);
            collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE), (p, out) -> {
                box(p, out, -0.25F, 0.0F, -0.25F, 0.25F, 0.6F, 0.25F, 0xFFFFFFFF, light);
                float bob = (float) Math.sin(age * 0.2) * 0.08F;
                box(p, out, -0.2F, 0.62F + bob, -0.2F, 0.2F, 0.98F + bob, 0.2F, 0xFFFFC080, light);
                float swing = (float) Math.sin(age * 0.3) * 0.15F;
                box(p, out, 0.27F, 0.25F + swing, -0.07F, 0.4F, 0.55F + swing, 0.07F, 0xFF80C0FF, light);
                box(p, out, -0.4F, 0.25F - swing, -0.07F, -0.27F, 0.55F - swing, 0.07F, 0xFF80C0FF, light);
            });
            pose.popPose();
            super.submit(state, pose, collector, camera);
        }
    }

    /** A cube turning over the block, as GeoBlockRenderer draws a block entity's model. */
    static final class GeoBlockRenderer implements BlockEntityRenderer<GeoMod.GeoBlockEntity, GeoBlockRenderer.State> {
        static final class State extends BlockEntityRenderState {
            float age;
        }

        @Override
        public State createRenderState() {
            return new State();
        }

        @Override
        public void extractRenderState(GeoMod.GeoBlockEntity be, State state, float partialTick, Vec3 camera,
                                       ModelFeatureRenderer.CrumblingOverlay breaking) {
            BlockEntityRenderer.super.extractRenderState(be, state, partialTick, camera, breaking);
            state.age = be.getLevel() == null ? 0 : be.getLevel().getGameTime() + partialTick;
        }

        @Override
        public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
            pose.pushPose();
            pose.translate(0.5F, 1.5F, 0.5F);
            pose.rotateDegrees(Axis.YP, state.age * 4.0F);
            pose.rotateDegrees(Axis.XP, 25.0F);
            int light = state.lightCoords;
            collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE), (p, out) ->
                    box(p, out, -0.25F, -0.25F, -0.25F, 0.25F, 0.25F, 0.25F, 0xFFFFFFFF, light));
            pose.popPose();
        }
    }
}
