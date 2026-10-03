package com.zuza.rodentcolonies.client;

import com.zuza.rodentcolonies.RodentColonies;
import com.zuza.rodentcolonies.client.model.MouseEntityModel;
import com.zuza.rodentcolonies.client.model.RatEntityModel;
import com.zuza.rodentcolonies.client.render.RodentRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;

/**
 * Клиентский инициализатор мода: регистрация слоёв моделей и рендереров (rodents_mod_guide.md §3.1, §5.1).
 */
public class RodentColoniesClient implements ClientModInitializer {
    public static final EntityModelLayer MOUSE_MODEL_LAYER = new EntityModelLayer(
            new Identifier(RodentColonies.MOD_ID, "mouse"), "main");
    public static final EntityModelLayer RAT_MODEL_LAYER = new EntityModelLayer(
            new Identifier(RodentColonies.MOD_ID, "rat"), "main");

    @Override
    public void onInitializeClient() {
        EntityModelLayerRegistry.registerModelLayer(MOUSE_MODEL_LAYER, MouseEntityModel::getTexturedModelData);
        EntityModelLayerRegistry.registerModelLayer(RAT_MODEL_LAYER, RatEntityModel::getTexturedModelData);

        // Радиус тени: мышь 0.12, крыса 0.18 (rodents_mod_assets.md §8)
        EntityRendererRegistry.register(
                RodentColonies.MOUSE_ENTITY_TYPE,
                ctx -> new RodentRenderer<>(ctx, new MouseEntityModel(ctx.getPart(MOUSE_MODEL_LAYER)), 0.12F)
        );
        EntityRendererRegistry.register(
                RodentColonies.RAT_ENTITY_TYPE,
                ctx -> new RodentRenderer<>(ctx, new RatEntityModel(ctx.getPart(RAT_MODEL_LAYER)), 0.18F)
        );
    }
}
