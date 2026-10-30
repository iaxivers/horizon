package dev.horizonlab.crittermod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/** The critter's model layer and renderer, registered as a mob mod's client does. */
public final class CritterClient implements ClientModInitializer {
    static final ModelLayerLocation LAYER = new ModelLayerLocation(CritterMod.ID, "main");
    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("horizon_critters", "textures/entity/critter.png");

    @Override
    public void onInitializeClient() {
        ModelLayerRegistry.registerModelLayer(LAYER, Model::layer);
        EntityRendererRegistry.register(CritterMod.CRITTER, Renderer::new);
    }

    /** A body, a head that looks around and four legs that walk. */
    static final class Model extends EntityModel<LivingEntityRenderState> {
        private final ModelPart head;
        private final ModelPart[] legs;

        Model(ModelPart root) {
            super(root);
            head = root.getChild("head");
            legs = new ModelPart[] {root.getChild("leg0"), root.getChild("leg1"), root.getChild("leg2"), root.getChild("leg3")};
        }

        static LayerDefinition layer() {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition root = mesh.getRoot();
            root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -3.0F, -5.0F, 8.0F, 6.0F, 10.0F),
                    PartPose.offset(0.0F, 17.0F, 0.0F));
            root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(36, 0).addBox(-3.0F, -3.0F, -6.0F, 6.0F, 6.0F, 6.0F),
                    PartPose.offset(0.0F, 15.0F, -5.0F));
            for (int i = 0; i < 4; i++) {
                float x = i % 2 == 0 ? -2.5F : 2.5F;
                float z = i < 2 ? -3.5F : 3.5F;
                root.addOrReplaceChild("leg" + i, CubeListBuilder.create().texOffs(0, 16).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 4.0F, 2.0F),
                        PartPose.offset(x, 20.0F, z));
            }
            return LayerDefinition.create(mesh, 64, 32);
        }

        @Override
        public void setupAnim(LivingEntityRenderState state) {
            super.setupAnim(state);
            head.yRot = state.yRot * (float) (Math.PI / 180.0);
            head.xRot = state.xRot * (float) (Math.PI / 180.0);
            float pos = state.walkAnimationPos, speed = state.walkAnimationSpeed;
            for (int i = 0; i < 4; i++) {
                float phase = i == 0 || i == 3 ? 0.0F : (float) Math.PI;
                legs[i].xRot = Mth.cos(pos * 0.6662F + phase) * 1.4F * speed;
            }
        }
    }

    static final class Renderer extends MobRenderer<Critter, LivingEntityRenderState, Model> {
        Renderer(EntityRendererProvider.Context context) {
            super(context, new Model(context.bakeLayer(LAYER)), 0.4F);
        }

        @Override
        public LivingEntityRenderState createRenderState() {
            return new LivingEntityRenderState();
        }

        @Override
        public Identifier getTextureLocation(LivingEntityRenderState state) {
            return TEXTURE;
        }
    }
}
