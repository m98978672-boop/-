package com.zuza.rodentcolonies.colony;

import com.zuza.rodentcolonies.config.RodentConfig;
import com.zuza.rodentcolonies.entity.RodentSpecies;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Данные одной колонии грызунов (rodents_mod_plan.md §2, §6, §7, §12).
 */
public class Colony {
    private final int colonyId;
    private final RodentSpecies species;
    private BlockPos homePos;
    private final List<BlockPos> entrances = new ArrayList<>();
    private final List<BlockPos> chamberPositions = new ArrayList<>();
    private final List<BlockPos> branchPositions = new ArrayList<>();
    private final Set<UUID> memberUuids = new HashSet<>();

    private final ColonyStorage storage = new ColonyStorage();
    private final ThreatMemory threatMemory = new ThreatMemory();
    private ConstructionSite constructionSite;

    private boolean alertActive;
    private BlockPos alertPos;
    private long alertUntilTick;

    private long foundedTick;
    private long abandonedTick = -1L;
    private long lastBreedingTick;
    private long lastStarvationTick;
    private long lastSpoilageHour = -1L;

    private int dailyBlockChanges;
    private long lastBudgetResetDay = -1L;

    private int playerKillsCount;
    private int warsSurvived;
    private boolean inActiveWar;
    private boolean entranceBuilt;

    public Colony(int colonyId, RodentSpecies species, BlockPos homePos, long foundedTick) {
        this.colonyId = colonyId;
        this.species = species;
        this.homePos = homePos.toImmutable();
        this.foundedTick = foundedTick;
        this.lastStarvationTick = foundedTick;
        this.entrances.add(homePos.toImmutable());
    }

    public int getColonyId() {
        return colonyId;
    }

    public RodentSpecies getSpecies() {
        return species;
    }

    public BlockPos getHomePos() {
        return homePos;
    }

    public void setHomePos(BlockPos homePos) {
        this.homePos = homePos.toImmutable();
    }

    public List<BlockPos> getEntrances() {
        return entrances;
    }

    public BlockPos getPrimaryEntrance() {
        return entrances.isEmpty() ? homePos : entrances.get(0);
    }

    public void addEntrance(BlockPos pos) {
        BlockPos imm = pos.toImmutable();
        if (!entrances.contains(imm)) {
            entrances.add(imm);
        }
    }

    public List<BlockPos> getChamberPositions() {
        return chamberPositions;
    }

    public void addChamberPosition(BlockPos pos) {
        BlockPos imm = pos.toImmutable();
        if (!chamberPositions.contains(imm)) {
            chamberPositions.add(imm);
        }
    }

    public List<BlockPos> getBranchPositions() {
        return branchPositions;
    }

    public void addBranchPosition(BlockPos pos) {
        BlockPos imm = pos.toImmutable();
        if (!branchPositions.contains(imm)) {
            branchPositions.add(imm);
        }
    }

    public Set<UUID> getMemberUuids() {
        return memberUuids;
    }

    public int getMemberCount() {
        return memberUuids.size();
    }

    public void addMember(UUID uuid) {
        memberUuids.add(uuid);
        abandonedTick = -1L;
    }

    public void removeMember(UUID uuid, long currentTick) {
        memberUuids.remove(uuid);
        if (memberUuids.isEmpty() && abandonedTick < 0L) {
            abandonedTick = currentTick;
        }
    }

    public boolean isAbandoned() {
        return memberUuids.isEmpty() && abandonedTick >= 0L;
    }

    public long getAbandonedTick() {
        return abandonedTick;
    }

    public void adoptAbandonedBurrow(UUID firstNewMember) {
        this.abandonedTick = -1L;
        if (firstNewMember != null) {
            this.memberUuids.add(firstNewMember);
        }
    }

    public ColonyStorage getStorage() {
        return storage;
    }

    public ThreatMemory getThreatMemory() {
        return threatMemory;
    }

    public ConstructionSite getConstructionSite() {
        return constructionSite;
    }

    public void setConstructionSite(ConstructionSite constructionSite) {
        this.constructionSite = constructionSite;
    }

    public boolean isEntranceBuilt() {
        return entranceBuilt;
    }

    public void setEntranceBuilt(boolean entranceBuilt) {
        this.entranceBuilt = entranceBuilt;
    }

    public void triggerAlert(BlockPos threatPos, String threatType, long currentTick) {
        this.alertActive = true;
        this.alertPos = threatPos.toImmutable();
        this.alertUntilTick = currentTick + 600L; // 30 секунд активной тревоги
        this.threatMemory.recordThreat(threatPos, threatType, currentTick);
    }

    public boolean isAlertActive(long currentTick) {
        if (alertActive && currentTick > alertUntilTick) {
            alertActive = false;
            alertPos = null;
        }
        return alertActive;
    }

    public BlockPos getAlertPos() {
        return alertPos;
    }

    public long getFoundedTick() {
        return foundedTick;
    }

    public long getLastBreedingTick() {
        return lastBreedingTick;
    }

    public void setLastBreedingTick(long lastBreedingTick) {
        this.lastBreedingTick = lastBreedingTick;
    }

    public long getLastStarvationTick() {
        return lastStarvationTick;
    }

    public void setLastStarvationTick(long lastStarvationTick) {
        this.lastStarvationTick = lastStarvationTick;
    }

    public long getLastSpoilageHour() {
        return lastSpoilageHour;
    }

    public void setLastSpoilageHour(long lastSpoilageHour) {
        this.lastSpoilageHour = lastSpoilageHour;
    }

    /**
     * Проверка и учёт дневного бюджета изменений колонии (не более 400 блоков в игровой день, §8.2).
     */
    public boolean canModifyBlockToday(long worldTime) {
        long day = worldTime / 24000L;
        if (day != lastBudgetResetDay) {
            lastBudgetResetDay = day;
            dailyBlockChanges = 0;
        }
        return dailyBlockChanges < RodentConfig.get().maxBlockChangesPerColonyPerDay;
    }

    public void recordBlockChangeToday(long worldTime) {
        long day = worldTime / 24000L;
        if (day != lastBudgetResetDay) {
            lastBudgetResetDay = day;
            dailyBlockChanges = 0;
        }
        dailyBlockChanges++;
    }

    public int getDailyBlockChanges() {
        return dailyBlockChanges;
    }

    public int getPlayerKillsCount() {
        return playerKillsCount;
    }

    public void incrementPlayerKills() {
        this.playerKillsCount++;
    }

    public int getWarsSurvived() {
        return warsSurvived;
    }

    public void incrementWarsSurvived() {
        this.warsSurvived++;
    }

    public boolean isInActiveWar() {
        return inActiveWar;
    }

    public void setInActiveWar(boolean inActiveWar) {
        this.inActiveWar = inActiveWar;
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("ColonyId", colonyId);
        nbt.putString("Species", species.getId());
        nbt.putLong("HomePos", homePos.asLong());
        nbt.putLong("FoundedTick", foundedTick);
        nbt.putLong("AbandonedTick", abandonedTick);
        nbt.putLong("LastBreedingTick", lastBreedingTick);
        nbt.putLong("LastStarvationTick", lastStarvationTick);
        nbt.putLong("LastSpoilageHour", lastSpoilageHour);
        nbt.putInt("DailyBlockChanges", dailyBlockChanges);
        nbt.putLong("LastBudgetResetDay", lastBudgetResetDay);
        nbt.putInt("PlayerKills", playerKillsCount);
        nbt.putInt("WarsSurvived", warsSurvived);
        nbt.putBoolean("InActiveWar", inActiveWar);
        nbt.putBoolean("EntranceBuilt", entranceBuilt);
        nbt.putBoolean("AlertActive", alertActive);
        if (alertPos != null) {
            nbt.putLong("AlertPos", alertPos.asLong());
        }
        nbt.putLong("AlertUntilTick", alertUntilTick);

        NbtList entranceList = new NbtList();
        for (BlockPos pos : entrances) {
            entranceList.add(NbtLong.of(pos.asLong()));
        }
        nbt.put("Entrances", entranceList);

        NbtList chamberList = new NbtList();
        for (BlockPos pos : chamberPositions) {
            chamberList.add(NbtLong.of(pos.asLong()));
        }
        nbt.put("Chambers", chamberList);

        NbtList branchList = new NbtList();
        for (BlockPos pos : branchPositions) {
            branchList.add(NbtLong.of(pos.asLong()));
        }
        nbt.put("Branches", branchList);

        NbtList memberList = new NbtList();
        for (UUID uuid : memberUuids) {
            memberList.add(NbtHelper.fromUuid(uuid));
        }
        nbt.put("Members", memberList);

        nbt.put("Storage", storage.toNbt());
        nbt.put("Threats", threatMemory.toNbt());
        if (constructionSite != null) {
            nbt.put("ConstructionSite", constructionSite.toNbt());
        }
        return nbt;
    }

    public static Colony fromNbt(NbtCompound nbt) {
        int id = nbt.getInt("ColonyId");
        RodentSpecies species = RodentSpecies.fromId(nbt.getString("Species"));
        BlockPos home = BlockPos.fromLong(nbt.getLong("HomePos"));
        long founded = nbt.getLong("FoundedTick");

        Colony colony = new Colony(id, species, home, founded);
        colony.abandonedTick = nbt.getLong("AbandonedTick");
        colony.lastBreedingTick = nbt.getLong("LastBreedingTick");
        colony.lastStarvationTick = nbt.getLong("LastStarvationTick");
        colony.lastSpoilageHour = nbt.getLong("LastSpoilageHour");
        colony.dailyBlockChanges = nbt.getInt("DailyBlockChanges");
        colony.lastBudgetResetDay = nbt.getLong("LastBudgetResetDay");
        colony.playerKillsCount = nbt.getInt("PlayerKills");
        colony.warsSurvived = nbt.getInt("WarsSurvived");
        colony.inActiveWar = nbt.getBoolean("InActiveWar");
        colony.entranceBuilt = nbt.getBoolean("EntranceBuilt");
        colony.alertActive = nbt.getBoolean("AlertActive");
        if (nbt.contains("AlertPos")) {
            colony.alertPos = BlockPos.fromLong(nbt.getLong("AlertPos"));
        }
        colony.alertUntilTick = nbt.getLong("AlertUntilTick");

        colony.entrances.clear();
        NbtList entranceList = nbt.getList("Entrances", NbtElement.LONG_TYPE);
        for (int i = 0; i < entranceList.size(); i++) {
            colony.entrances.add(BlockPos.fromLong(((NbtLong) entranceList.get(i)).longValue()));
        }
        if (colony.entrances.isEmpty()) {
            colony.entrances.add(home);
        }

        NbtList chamberList = nbt.getList("Chambers", NbtElement.LONG_TYPE);
        for (int i = 0; i < chamberList.size(); i++) {
            colony.chamberPositions.add(BlockPos.fromLong(((NbtLong) chamberList.get(i)).longValue()));
        }

        NbtList branchList = nbt.getList("Branches", NbtElement.LONG_TYPE);
        for (int i = 0; i < branchList.size(); i++) {
            colony.branchPositions.add(BlockPos.fromLong(((NbtLong) branchList.get(i)).longValue()));
        }

        NbtList memberList = nbt.getList("Members", NbtElement.INT_ARRAY_TYPE);
        for (int i = 0; i < memberList.size(); i++) {
            colony.memberUuids.add(NbtHelper.toUuid(memberList.get(i)));
        }

        if (nbt.contains("Storage")) {
            colony.storage.readNbt(nbt.getCompound("Storage"));
        }
        if (nbt.contains("Threats")) {
            colony.threatMemory.readNbt(nbt.getList("Threats", NbtElement.COMPOUND_TYPE));
        }
        if (nbt.contains("ConstructionSite")) {
            colony.constructionSite = ConstructionSite.fromNbt(nbt.getCompound("ConstructionSite"));
        }
        return colony;
    }
}
