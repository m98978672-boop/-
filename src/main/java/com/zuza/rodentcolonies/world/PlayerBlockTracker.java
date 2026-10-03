package com.zuza.rodentcolonies.world;

import com.zuza.rodentcolonies.config.RodentConfig;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Отслеживание блоков, поставленных игроком (rodents_mod_plan.md §6.3, rodents_mod_guide.md §3.1).
 * Пополняется миксином BlockItemMixin при включённой опции trackPlayerPlacedBlocks.
 */
public final class PlayerBlockTracker {
    private static final Map<RegistryKey<World>, Set<Long>> PLAYER_BLOCKS = new HashMap<>();

    private PlayerBlockTracker() {
    }

    public static void onPlayerPlaceBlock(World world, BlockPos pos) {
        if (world == null || world.isClient() || !RodentConfig.get().trackPlayerPlacedBlocks) {
            return;
        }
        Set<Long> set = PLAYER_BLOCKS.computeIfAbsent(world.getRegistryKey(), k -> Collections.synchronizedSet(new HashSet<>()));
        if (set.size() < 200000) {
            set.add(pos.asLong());
        }
    }

    public static boolean isPlayerPlaced(World world, BlockPos pos) {
        if (world == null || !RodentConfig.get().trackPlayerPlacedBlocks) {
            return false;
        }
        Set<Long> set = PLAYER_BLOCKS.get(world.getRegistryKey());
        return set != null && set.contains(pos.asLong());
    }
}
