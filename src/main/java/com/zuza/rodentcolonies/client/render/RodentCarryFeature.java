package com.zuza.rodentcolonies.client.render;

import com.zuza.rodentcolonies.client.model.RodentEntityModel;
import com.zuza.rodentcolonies.entity.CarryLoadType;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentState;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Слой рендера носимого в зубах груза (бокс carry, rodents_mod_assets.md §5.12, §8).
 */
public class RodentCarryFeature<T extends RodentEntity, M extends RodentEntityModel<T>> extends FeatureRenderer<T, M> {
    public RodentCarryFeature(FeatureRendererContext<T, M> context) {
        super(context);
    }

    @Override
    public void render(
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            T entity,
            float limbAngle,
            float limbDistance,
            float tickDelta,
            float animationProgress,
            float headYaw,
            float headPitch
    ) {
        CarryLoadType load = entity.getCarryLoadType();
        if (!load.isCarrying() || entity.getRodentState() == RodentState.PLACE_BLOCK) {
            return;
        }

        // Цветовой оттенок груза по материалу (§5.12): земля, сено, палка, листва, еда
        float r = 1.0F;
        float g = 1.0F;
        float b = 1.0F;
        switch (load) {
            case DIRT -> {
                r = 0.78F;
                g = 0.62F;
                b = 0.46F;
            }
            case HAY, FOOD -> {
                r = 0.96F;
                g = 0.84F;
                b = 0.45F;
            }
            case STICK -> {
                r = 0.68F;
                g = 0.54F;
                b = 0.36F;
            }
            case FOLIAGE -> {
                r = 0.55F;
                g = 0.78F;
                b = 0.44F;
            }
            default -> {
            }
        }

        M model = this.getContextModel();
        matrices.push();
        model.getBodyPart().rotate(matrices);
        model.getHeadPart().rotate(matrices);
        boolean wasVisible = model.getCarryPart().visible;
        model.getCarryPart().visible = true;
        VertexConsumer consumer = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(this.getTexture(entity)));
        model.getCarryPart().render(matrices, consumer, light, OverlayTexture.DEFAULT_UV, r, g, b, 1.0F);
        model.getCarryPart().visible = wasVisible;
        matrices.pop();
    }
}
