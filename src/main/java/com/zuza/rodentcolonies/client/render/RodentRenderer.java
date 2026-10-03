package com.zuza.rodentcolonies.client.render;

import com.zuza.rodentcolonies.RodentColonies;
import com.zuza.rodentcolonies.client.model.RodentEntityModel;
import com.zuza.rodentcolonies.entity.RodentCoatVariant;
import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentSpecies;
import com.zuza.rodentcolonies.entity.RodentState;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * Рендерер мыши и крысы (rodents_mod_assets.md §3.3, §6, §8):
 * - радиус тени: 0.12 у мыши, 0.18 у крысы;
 * - масштаб детёныша: 0.5;
 * - выбор текстуры по виду и варианту окраса;
 * - слой груза RodentCarryFeature.
 */
public class RodentRenderer<T extends RodentEntity, M extends RodentEntityModel<T>> extends MobEntityRenderer<T, M> {
    private static final Identifier MOUSE_GRAY = new Identifier(RodentColonies.MOD_ID, "textures/entity/mouse_gray.png");
    private static final Identifier MOUSE_BROWN = new Identifier(RodentColonies.MOD_ID, "textures/entity/mouse_brown.png");
    private static final Identifier MOUSE_VETERAN = new Identifier(RodentColonies.MOD_ID, "textures/entity/mouse_veteran.png");
    private static final Identifier MOUSE_MANGY = new Identifier(RodentColonies.MOD_ID, "textures/entity/mouse_mangy.png");

    private static final Identifier RAT_AGOUTI = new Identifier(RodentColonies.MOD_ID, "textures/entity/rat_agouti.png");
    private static final Identifier RAT_DARK = new Identifier(RodentColonies.MOD_ID, "textures/entity/rat_dark.png");
    private static final Identifier RAT_VETERAN = new Identifier(RodentColonies.MOD_ID, "textures/entity/rat_veteran.png");
    private static final Identifier RAT_MANGY = new Identifier(RodentColonies.MOD_ID, "textures/entity/rat_mangy.png");

    public RodentRenderer(EntityRendererFactory.Context context, M entityModel, float shadowRadius) {
        super(context, entityModel, shadowRadius);
        this.addFeature(new RodentCarryFeature<>(this));
    }

    @Override
    protected void scale(T entity, MatrixStack matrices, float amount) {
        // Масштаб детёныша 0.5 (rodents_mod_assets.md §6, §7, §8)
        if (entity.isBaby() || entity.getRodentState() == RodentState.BABY) {
            matrices.scale(0.5F, 0.5F, 0.5F);
        }
    }

    @Override
    public Identifier getTexture(T entity) {
        RodentCoatVariant variant = entity.getCoatVariant();
        if (entity.getSpecies() == RodentSpecies.MOUSE) {
            return switch (variant) {
                case BASE_SECONDARY -> MOUSE_BROWN;
                case VETERAN -> MOUSE_VETERAN;
                case MANGY -> MOUSE_MANGY;
                default -> MOUSE_GRAY;
            };
        } else {
            return switch (variant) {
                case BASE_SECONDARY -> RAT_DARK;
                case VETERAN -> RAT_VETERAN;
                case MANGY -> RAT_MANGY;
                default -> RAT_AGOUTI;
            };
        }
    }
}
