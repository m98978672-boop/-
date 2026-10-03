package com.zuza.rodentcolonies.client.model;

import com.zuza.rodentcolonies.entity.RatEntity;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;

/**
 * Геометрия модели крысы (rodents_mod_assets.md §2, §2.1).
 * Суммарная высота: 11 единиц (24 - 13), текстура 64 x 64.
 */
public class RatEntityModel extends RodentEntityModel<RatEntity> {
    public RatEntityModel(ModelPart root) {
        super(root);
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData modelData = new ModelData();
        ModelPartData root = modelData.getRoot();

        ModelPartData body = root.addChild("body",
                ModelPartBuilder.create().uv(0, 0).cuboid(-4.0F, -6.0F, -6.0F, 8.0F, 6.0F, 12.0F),
                ModelTransform.pivot(0.0F, 21.0F, 2.0F));

        body.addChild("tail",
                ModelPartBuilder.create().uv(42, 26).cuboid(-0.5F, 0.0F, 0.0F, 1.0F, 1.0F, 10.0F),
                ModelTransform.pivot(0.0F, -5.0F, 6.0F));

        ModelPartBuilder legFront = ModelPartBuilder.create().uv(24, 20).cuboid(-1.0F, 0.0F, -1.0F, 2.0F, 3.0F, 2.0F);
        body.addChild("leg_fl", legFront, ModelTransform.pivot(-3.0F, 0.0F, -4.0F));
        body.addChild("leg_fr", legFront, ModelTransform.pivot( 3.0F, 0.0F, -4.0F));

        ModelPartBuilder legBack = ModelPartBuilder.create().uv(34, 20).cuboid(-1.0F, 0.0F, -1.5F, 2.0F, 3.0F, 3.0F);
        body.addChild("leg_bl", legBack, ModelTransform.pivot(-3.0F, 0.0F, 4.5F));
        body.addChild("leg_br", legBack, ModelTransform.pivot( 3.0F, 0.0F, 4.5F));

        ModelPartData head = body.addChild("head",
                ModelPartBuilder.create().uv(42, 0).cuboid(-3.0F, -2.5F, -5.0F, 6.0F, 5.0F, 5.0F),
                ModelTransform.pivot(0.0F, -3.5F, -6.0F));

        head.addChild("snout",
                ModelPartBuilder.create().uv(0, 20).cuboid(-2.0F, -1.5F, -3.0F, 4.0F, 3.0F, 3.0F),
                ModelTransform.pivot(0.0F, -1.0F, -5.0F));

        ModelPartBuilder ear = ModelPartBuilder.create().uv(16, 20).cuboid(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 1.0F);
        head.addChild("ear_l", ear, ModelTransform.pivot(-3.0F, -2.5F, -4.0F));
        head.addChild("ear_r", ear, ModelTransform.pivot( 3.0F, -2.5F, -4.0F));

        head.addChild("carry",
                ModelPartBuilder.create().uv(0, 26).cuboid(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F),
                ModelTransform.pivot(0.0F, -1.5F, -10.0F));

        return TexturedModelData.of(modelData, 64, 64);
    }
}
