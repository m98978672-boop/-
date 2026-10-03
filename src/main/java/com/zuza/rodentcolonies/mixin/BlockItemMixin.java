package com.zuza.rodentcolonies.mixin;

import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.world.PlayerBlockTracker;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Опциональный миксин на установку блока игроком (rodents_mod_plan.md §6.3).
 * Активен только когда trackPlayerPlacedBlocks = true в конфиге.
 */
@Mixin(BlockItem.class)
public class BlockItemMixin {
    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;", at = @At("RETURN"))
    private void rodentColonies$onPlace(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (!RodentConfig.get().trackPlayerPlacedBlocks) {
            return;
        }
        ActionResult result = cir.getReturnValue();
        if (result != null && result.isAccepted() && context != null && context.getPlayer() != null) {
            PlayerBlockTracker.onPlayerPlaceBlock(context.getWorld(), context.getBlockPos());
        }
    }
}
