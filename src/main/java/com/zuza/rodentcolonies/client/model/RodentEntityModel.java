package com.zuza.rodentcolonies.client.model;

import com.zuza.rodentcolonies.entity.RodentEntity;
import com.zuza.rodentcolonies.entity.RodentState;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.model.SinglePartEntityModel;
import net.minecraft.util.math.MathHelper;

/**
 * Общая модель и все 13 состояний анимации грызунов (rodents_mod_assets.md §0, §7, §8).
 * Приоритет состояний: сон -> паника -> тревога -> атака -> еда/грызение/укладка -> ходьба -> покой.
 */
public abstract class RodentEntityModel<T extends RodentEntity> extends SinglePartEntityModel<T> {
    protected final ModelPart root;
    protected final ModelPart body;
    protected final ModelPart head;
    protected final ModelPart snout;
    protected final ModelPart earL;
    protected final ModelPart earR;
    protected final ModelPart legFl;
    protected final ModelPart legFr;
    protected final ModelPart legBl;
    protected final ModelPart legBr;
    protected final ModelPart tail;
    protected final ModelPart carry;

    protected RodentEntityModel(ModelPart root) {
        super(RenderLayer::getEntityCutoutNoCull);
        this.root = root;
        this.body = root.getChild("body");
        this.tail = this.body.getChild("tail");
        this.legFl = this.body.getChild("leg_fl");
        this.legFr = this.body.getChild("leg_fr");
        this.legBl = this.body.getChild("leg_bl");
        this.legBr = this.body.getChild("leg_br");
        this.head = this.body.getChild("head");
        this.snout = this.head.getChild("snout");
        this.earL = this.head.getChild("ear_l");
        this.earR = this.head.getChild("ear_r");
        this.carry = this.head.getChild("carry");
    }

    @Override
    public ModelPart getPart() {
        return this.root;
    }

    public ModelPart getCarryPart() {
        return this.carry;
    }

    public ModelPart getBodyPart() {
        return this.body;
    }

    public ModelPart getHeadPart() {
        return this.head;
    }

    private static float deg(float degrees) {
        return degrees * ((float) Math.PI / 180.0F);
    }

    @Override
    public void setAngles(T entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        this.getPart().traverse().forEach(ModelPart::resetTransform);

        RodentState state = entity.getRodentState();
        float babyLegMult = (entity.isBaby() || state == RodentState.BABY) ? 1.2F : 1.0F;

        // Видимость бокса груза (при укладке блока скрывается в конце фазы, §7)
        this.carry.visible = entity.getCarryLoadType().isCarrying() && state != RodentState.PLACE_BLOCK;

        // Базовый поворот головы за взглядом (если не во сне)
        if (state != RodentState.SLEEP) {
            this.head.yaw = deg(MathHelper.clamp(headYaw, -35.0F, 35.0F));
            this.head.pitch = deg(MathHelper.clamp(headPitch, -25.0F, 25.0F));
        }

        // Получение урона (hurtTime > 0 или состояние HURT): body rotateZ ±8°, 5 т (§7)
        if (entity.hurtTime > 0 || state == RodentState.HURT) {
            this.body.roll = deg(8.0F) * MathHelper.sin(animationProgress * 1.25F);
        }

        switch (state) {
            case SLEEP -> {
                // Сон: лапы rotateX 90° (поджаты); head rotateX +35°; tail rotateY 160°; дыхание scaleY 1.00->1.04, цикл 60 т (§7)
                this.legFl.pitch = deg(90.0F);
                this.legFr.pitch = deg(90.0F);
                this.legBl.pitch = deg(90.0F);
                this.legBr.pitch = deg(90.0F);
                this.head.pitch = deg(35.0F);
                this.tail.yaw = deg(160.0F);
                float breath = (MathHelper.sin(animationProgress * ((float) Math.PI * 2.0F / 60.0F)) + 1.0F) * 0.5F;
                this.body.yScale = 1.00F + 0.04F * breath;
            }
            case RUN -> {
                // Бег/паника: период 12 т; амплитуда лап 40°; body rotateX -6°; tail rotateX +10°; ear_l/r rotateX +20° (§7)
                float phase = animationProgress * ((float) Math.PI * 2.0F / 12.0F);
                float legAmp = deg(40.0F) * babyLegMult;
                this.legFl.pitch = MathHelper.sin(phase) * legAmp;
                this.legBr.pitch = MathHelper.sin(phase) * legAmp;
                this.legFr.pitch = -MathHelper.sin(phase) * legAmp;
                this.legBl.pitch = -MathHelper.sin(phase) * legAmp;
                this.body.pitch = deg(-6.0F);
                this.tail.pitch = deg(10.0F);
                this.earL.pitch = deg(20.0F);
                this.earR.pitch = deg(20.0F);
            }
            case ALARM -> {
                // Тревога: ear_l/r rotateX -20° (подняты); tail rotateX +15° (торчком); head rotateY ±30°, период 20 т (§7)
                float phase = animationProgress * ((float) Math.PI * 2.0F / 20.0F);
                this.earL.pitch = deg(-20.0F);
                this.earR.pitch = deg(-20.0F);
                this.tail.pitch = deg(15.0F);
                this.head.yaw = MathHelper.sin(phase) * deg(30.0F);
            }
            case ATTACK -> {
                // Драка, выпад: 6 т: корень translateZ -1.5 (выпад вперёд), head rotateX -15°, leg_fl/fr rotateX -35° (§7)
                this.body.pivotZ -= 1.5F;
                this.head.pitch = deg(-15.0F);
                this.legFl.pitch = deg(-35.0F);
                this.legFr.pitch = deg(-35.0F);
            }
            case EAT -> {
                // Еда/грызение: head rotateX +8°/-8°, период 5 т; body сдвиг 0.2 ед. в такт (§7)
                float phase = animationProgress * ((float) Math.PI * 2.0F / 5.0F);
                this.head.pitch = MathHelper.sin(phase) * deg(8.0F);
                this.body.pivotZ += MathHelper.sin(phase) * 0.2F;
            }
            case PLACE_BLOCK -> {
                // Укладка блока: 8 т: head rotateX +25° (морда вниз), затем carry.visible = false (§7)
                this.head.pitch = deg(25.0F);
                this.carry.visible = false;
            }
            case SNIFF -> {
                // Нюхает: head rotateX +12° (нос вниз), 10 т (§7)
                this.head.pitch = deg(12.0F);
                this.tail.yaw = MathHelper.sin(animationProgress * ((float) Math.PI * 2.0F / 60.0F)) * deg(8.0F);
            }
            case GROOM -> {
                // Умывается: head rotateX +20°, rotateY ±25°, 25 т; leg_fl rotateX -40°, 25 т (§7)
                float phase = animationProgress * ((float) Math.PI * 2.0F / 12.5F);
                this.head.pitch = deg(20.0F);
                this.head.yaw = MathHelper.sin(phase) * deg(25.0F);
                this.legFl.pitch = deg(-40.0F);
            }
            case WALK, BABY -> {
                // Ходьба: период 20 т; leg_fl/leg_br rotateX ±25° (sin); leg_fr/leg_bl в противофазе;
                // body translateY +0.4 на двойной частоте; head rotateX ±3°; tail rotateY ±10° (§7)
                float phase = limbDistance > 0.01F
                        ? limbAngle * 0.6662F
                        : animationProgress * ((float) Math.PI * 2.0F / 20.0F);
                float amp = (limbDistance > 0.01F ? Math.min(1.0F, limbDistance * 1.5F) : 1.0F) * deg(25.0F) * babyLegMult;
                this.legFl.pitch = MathHelper.sin(phase) * amp;
                this.legBr.pitch = MathHelper.sin(phase) * amp;
                this.legFr.pitch = -MathHelper.sin(phase) * amp;
                this.legBl.pitch = -MathHelper.sin(phase) * amp;
                this.body.pivotY += Math.abs(MathHelper.sin(phase)) * 0.4F;
                this.head.pitch += MathHelper.sin(phase * 2.0F) * deg(3.0F);
                this.tail.yaw = MathHelper.sin(phase) * deg(10.0F);
            }
            case IDLE, HURT, DEATH -> {
                // Покой: tail rotateY ±8°, цикл 60 т; ear_l/r подрагивание rotateZ ±12° раз в 40-120 т (§7)
                float tailPhase = animationProgress * ((float) Math.PI * 2.0F / 60.0F);
                this.tail.yaw = MathHelper.sin(tailPhase) * deg(8.0F);
                int mod = (int) (animationProgress % 80.0F);
                if (mod < 4) {
                    float twitch = MathHelper.sin(mod * 1.5F) * deg(12.0F);
                    this.earL.roll = -twitch;
                    this.earR.roll = twitch;
                }
            }
        }
    }
}
