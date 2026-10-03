package com.zuza.rodentcolonies.client.model;

import com.zuza.rodentcolonies.entity.MouseEntity;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;

/**
 * Геометрия модели мыши (rodents_mod_assets.md §1.1, §1.2).
 * Суммарная высота: 9 единиц (24 - 15), текстура 64 x 64.
 */
public class MouseEntityModel extends RodentEntityModel<MouseEntity> {
    public MouseEntityModel(ModelPart root) {
        super(root);
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData modelData = new ModelData();
        ModelPartData root = modelData.getRoot();

        ModelPartData body = root.addChild("body",
                ModelPartBuilder.create().uv(0, 0).cuboid(-3.0F, -4.0F, -4.0F, 6.0F, 4.0F, 8.0F),
                ModelTransform.pivot(0.0F, 22.0F, 1.0F));

        body.addChild("tail",
                ModelPartBuilder.create().uv(20, 16).cuboid(-0.5F, 0.0F, 0.0F, 1.0F, 1.0F, 6.0F),
                ModelTransform.pivot(0.0F, -3.0F, 4.0F));

        ModelPartBuilder leg = ModelPartBuilder.create().uv(8, 16).cuboid(-1.0F, 0.0F, -1.0F, 2.0F, 2.0F, 2.0F);
        body.addChild("leg_fl", leg, ModelTransform.pivot(-2.0F, 0.0F, -3.0F));
        body.addChild("leg_fr", leg, ModelTransform.pivot( 2.0F, 0.0F, -3.0F));
        body.addChild("leg_bl", leg, ModelTransform.pivot(-2.0F, 0.0F,  3.0F));
        body.addChild("leg_br", leg, ModelTransform.pivot( 2.0F, 0.0F,  3.0F));

        ModelPartData head = body.addChild("head",
                ModelPartBuilder.create().uv(30, 0).cuboid(-2.5F, -2.0F, -4.0F, 5.0F, 4.0F, 4.0F),
                ModelTransform.pivot(0.0F, -3.0F, -4.0F));

        head.addChild("snout",
                ModelPartBuilder.create().uv(48, 0).cuboid(-1.0F, -2.0F, -3.0F, 2.0F, 2.0F, 3.0F),
                ModelTransform.pivot(0.0F, 0.0F, -4.0F));

        ModelPartBuilder ear = ModelPartBuilder.create().uv(0, 16).cuboid(-1.0F, -2.0F, -0.5F, 2.0F, 2.0F, 1.0F);
        head.addChild("ear_l", ear, ModelTransform.pivot(-1.5F, -2.0F, -2.5F));
        head.addChild("ear_r", ear, ModelTransform.pivot( 1.5F, -2.0F, -2.5F));

        head.addChild("carry",
                ModelPartBuilder.create().uv(48, 8).cuboid(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F),
                ModelTransform.pivot(0.0F, -2.0F, -9.0F));

        return TexturedModelData.of(modelData, 64, 64);
    }
}
