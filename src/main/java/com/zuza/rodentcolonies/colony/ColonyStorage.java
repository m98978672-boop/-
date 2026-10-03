package com.zuza.rodentcolonies.colony;

import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.CarryLoadType;
import net.minecraft.nbt.NbtCompound;

import java.util.EnumMap;
import java.util.Map;

/**
 * Склад колонии: еда и стройматериалы по типам + строгий учёт нулевого баланса (§4, §6, §8.1).
 * Главный принцип: поставлено <= добыто - потеряно + захвачено.
 */
public class ColonyStorage {
    private float foodUnits;
    private final EnumMap<CarryLoadType, Integer> materials = new EnumMap<>(CarryLoadType.class);

    // Счётчики нулевого баланса (§6, §8.1)
    private int totalHarvestedBlocks;
    private int totalPlacedBlocks;
    private int totalLostBlocks;
    private int totalCapturedBlocks;

    public ColonyStorage() {
        for (CarryLoadType type : CarryLoadType.values()) {
            if (type != CarryLoadType.NONE && type != CarryLoadType.FOOD) {
                materials.put(type, 0);
            }
        }
    }

    public float getFoodUnits() {
        return foodUnits;
    }

    public void setFoodUnits(float foodUnits) {
        this.foodUnits = Math.max(0.0F, foodUnits);
    }

    public void addFood(float units) {
        if (units > 0.0F) {
            this.foodUnits += units;
        }
    }

    /**
     * Потребление пищи: если обычной еды не хватает, голодная колония съедает запас сена (§4).
     */
    public boolean consumeFood(float required) {
        if (foodUnits >= required) {
            foodUnits -= required;
            return true;
        }
        float deficit = required - foodUnits;
        int hayAvailable = getMaterialCount(CarryLoadType.HAY);
        float hayValue = RodentConfig.get().foodUnitsBreadMeatHay;
        int hayNeeded = (int) Math.ceil(deficit / Math.max(0.5F, hayValue));
        if (hayAvailable >= hayNeeded && hayNeeded > 0) {
            foodUnits = 0.0F;
            materials.put(CarryLoadType.HAY, hayAvailable - hayNeeded);
            totalLostBlocks += hayNeeded; // Сено съедено при голоде
            float gained = hayNeeded * hayValue;
            foodUnits = Math.max(0.0F, gained - deficit);
            return true;
        }
        foodUnits = 0.0F;
        return false;
    }

    /**
     * Порча запаса: -1% за игровой час (§4).
     */
    public void applyHourlySpoilage() {
        RodentConfig cfg = RodentConfig.get();
        if (!cfg.enableFoodSpoilage || foodUnits <= 0.0F) {
            return;
        }
        float factor = Math.max(0.0F, 1.0F - (cfg.foodSpoilagePercentPerGameHour / 100.0F));
        foodUnits *= factor;
        if (foodUnits < 0.05F) {
            foodUnits = 0.0F;
        }
    }

    public int getMaterialCount(CarryLoadType type) {
        return materials.getOrDefault(type, 0);
    }

    public int getTotalMaterialsInStock() {
        int sum = 0;
        for (int v : materials.values()) {
            sum += v;
        }
        return sum;
    }

    /**
     * Регистрирует добытый и доставленный в нору блок материала.
     */
    public void depositHarvestedMaterial(CarryLoadType type, int amount) {
        if (amount <= 0 || type == CarryLoadType.NONE || type == CarryLoadType.FOOD) {
            return;
        }
        materials.put(type, getMaterialCount(type) + amount);
        totalHarvestedBlocks += amount;
    }

    /**
     * Передача захваченных при войне материалов (§7.3, §8.1).
     */
    public void addCapturedMaterial(CarryLoadType type, int amount) {
        if (amount <= 0 || type == CarryLoadType.NONE || type == CarryLoadType.FOOD) {
            return;
        }
        materials.put(type, getMaterialCount(type) + amount);
        totalCapturedBlocks += amount;
    }

    /**
     * Списывает материал со склада для установки блока в мир.
     * Блок не может появиться «из воздуха»: поставлено <= добыто - потеряно + захвачено (§6).
     */
    public boolean consumeMaterialForPlacement(CarryLoadType type) {
        int current = getMaterialCount(type);
        if (current <= 0) {
            return false;
        }
        int maxAllowedPlaced = totalHarvestedBlocks + totalCapturedBlocks - totalLostBlocks;
        if (totalPlacedBlocks + 1 > maxAllowedPlaced) {
            return false;
        }
        materials.put(type, current - 1);
        totalPlacedBlocks += 1;
        return true;
    }

    /**
     * Возвращает материал на склад при откате (rollback) установленного блока.
     */
    public void refundPlacedMaterial(CarryLoadType type) {
        if (type == CarryLoadType.NONE || type == CarryLoadType.FOOD) {
            return;
        }
        if (totalPlacedBlocks > 0) {
            totalPlacedBlocks--;
            materials.put(type, getMaterialCount(type) + 1);
        }
    }

    /**
     * Фиксирует потерю несомого блока (например, при гибели грызуна и выпадении дропом).
     */
    public void recordLostMaterial(CarryLoadType type, int amount) {
        if (amount <= 0) {
            return;
        }
        int cur = getMaterialCount(type);
        int taken = Math.min(cur, amount);
        materials.put(type, cur - taken);
        totalLostBlocks += taken;
    }

    /**
     * Проверка инварианта нулевого баланса (§8.1):
     * добыто + захвачено == поставлено + потеряно + остаток в запасе.
     */
    public boolean verifyZeroSumBalance() {
        int leftSide = totalHarvestedBlocks + totalCapturedBlocks;
        int rightSide = totalPlacedBlocks + totalLostBlocks + getTotalMaterialsInStock();
        return leftSide == rightSide && totalPlacedBlocks <= (totalHarvestedBlocks + totalCapturedBlocks - totalLostBlocks);
    }

    public int getTotalHarvestedBlocks() {
        return totalHarvestedBlocks;
    }

    public int getTotalPlacedBlocks() {
        return totalPlacedBlocks;
    }

    public int getTotalLostBlocks() {
        return totalLostBlocks;
    }

    public int getTotalCapturedBlocks() {
        return totalCapturedBlocks;
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putFloat("FoodUnits", foodUnits);
        nbt.putInt("Harvested", totalHarvestedBlocks);
        nbt.putInt("Placed", totalPlacedBlocks);
        nbt.putInt("Lost", totalLostBlocks);
        nbt.putInt("Captured", totalCapturedBlocks);

        NbtCompound matTag = new NbtCompound();
        for (Map.Entry<CarryLoadType, Integer> entry : materials.entrySet()) {
            matTag.putInt(entry.getKey().getName(), entry.getValue());
        }
        nbt.put("Materials", matTag);
        return nbt;
    }

    public void readNbt(NbtCompound nbt) {
        this.foodUnits = nbt.getFloat("FoodUnits");
        this.totalHarvestedBlocks = nbt.getInt("Harvested");
        this.totalPlacedBlocks = nbt.getInt("Placed");
        this.totalLostBlocks = nbt.getInt("Lost");
        this.totalCapturedBlocks = nbt.getInt("Captured");

        NbtCompound matTag = nbt.getCompound("Materials");
        for (CarryLoadType type : CarryLoadType.values()) {
            if (type != CarryLoadType.NONE && type != CarryLoadType.FOOD) {
                materials.put(type, matTag.getInt(type.getName()));
            }
        }
    }
}
