package schnerry.seymouranalyzer.scanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import lombok.Getter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.BlockHitResult;
import schnerry.seymouranalyzer.SeymourAnalyzer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks chest space: monitors which chests have free slots
 * Uses dirty-flag pattern like CollectionManager for persistent + live updates
 * Cache format: BlockPos (x,y,z) -> (usedSlots, totalSlots, lastUpdate)
 */
public class ChestSpaceTracker {
    private static ChestSpaceTracker INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Getter
    private boolean highlightEnabled = false;

    private final Map<String, ChestInfo> chestCache = new HashMap<>();
    private final File cacheFile;
    private final AtomicBoolean isDirty = new AtomicBoolean(false);
    private long lastSaveTime = 0;
    private static final long SAVE_DEBOUNCE_MS = 2000; // Wait 2 seconds after last change before saving
    private static final long INTERACTION_VALIDITY_MS = 1000;
    private BlockPos currentOpenChestPos = null;
    private java.util.List<BlockPos> currentOpenChestPositions = new java.util.ArrayList<>();
    private BlockPos pendingStorageInteraction = null;
    private long pendingStorageInteractionTime = 0;

    public static class ChestInfo {
        public int usedSlots;
        public int totalSlots;
        public long lastUpdate;

        public ChestInfo(int used, int total) {
            this.usedSlots = used;
            this.totalSlots = total;
            this.lastUpdate = System.currentTimeMillis();
        }
    }

    public ChestSpaceTracker() {
        File configDir = new File(FabricLoader.getInstance().getConfigDir().toFile(), "seymouranalyzer");
        if (!configDir.exists() && !configDir.mkdirs()) {
            SeymourAnalyzer.LOGGER.error("Failed to create seymouranalyzer config directory");
        }
        this.cacheFile = new File(configDir, "chest_cache.json");
        load();
    }

    public static ChestSpaceTracker getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ChestSpaceTracker();
        }
        return INSTANCE;
    }

    /**
     * Load cache from JSON file (no TTL - persists indefinitely)
     */
    public void load() {
        try {
            if (cacheFile.exists()) {
                JsonObject json = GSON.fromJson(new FileReader(cacheFile), JsonObject.class);
                
                json.entrySet().forEach(entry -> {
                    try {
                        JsonObject chestJson = entry.getValue().getAsJsonObject();
                        int usedSlots = chestJson.get("usedSlots").getAsInt();
                        int totalSlots = chestJson.get("totalSlots").getAsInt();
                        long lastUpdate = chestJson.get("lastUpdate").getAsLong();
                        
                        ChestInfo info = new ChestInfo(usedSlots, totalSlots);
                        info.lastUpdate = lastUpdate;
                        chestCache.put(entry.getKey(), info);
                    } catch (Exception e) {
                        SeymourAnalyzer.LOGGER.warn("Failed to parse chest cache entry: " + entry.getKey(), e);
                    }
                });

                SeymourAnalyzer.LOGGER.info("Loaded {} cached chests from file", chestCache.size());
            }
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Failed to load chest cache", e);
        }
    }

    /**
     * Save cache to JSON file (sync)
     */
    public void save() {
        try {
            JsonObject json = new JsonObject();
            
            for (Map.Entry<String, ChestInfo> entry : chestCache.entrySet()) {
                ChestInfo info = entry.getValue();
                JsonObject chestJson = new JsonObject();
                chestJson.addProperty("usedSlots", info.usedSlots);
                chestJson.addProperty("totalSlots", info.totalSlots);
                chestJson.addProperty("lastUpdate", info.lastUpdate);
                json.add(entry.getKey(), chestJson);
            }
            
            try (FileWriter writer = new FileWriter(cacheFile)) {
                GSON.toJson(json, writer);
            }
            
            isDirty.set(false);
            lastSaveTime = System.currentTimeMillis();
            SeymourAnalyzer.LOGGER.debug("Saved {} chests to cache file", chestCache.size());
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Failed to save chest cache", e);
        }
    }

    /**
     * Mark cache as dirty - will trigger auto-save after debounce period
     */
    private void markDirty() {
        isDirty.set(true);
        lastSaveTime = System.currentTimeMillis();
    }

    /**
     * Called every tick to handle auto-save with debounce
     */
    public void tick() {
        if (isDirty.get()) {
            long timeSinceLastChange = System.currentTimeMillis() - lastSaveTime;
            if (timeSinceLastChange >= SAVE_DEBOUNCE_MS) {
                save();
            }
        }
    }

    /**
     * Update cache when a chest screen opens (live - highlights update immediately)
     */
    public void onChestOpen(AbstractContainerScreen<?> screen, BlockPos chestPos) {
        if (screen.getMenu() == null || chestPos == null) return;
        currentOpenChestPos = chestPos;
        currentOpenChestPositions = resolveConnectedChestBlocks(chestPos);
        updateCurrentChestContents(screen);
    }

    /**
     * Stores the exact storage block clicked immediately before a container opens.
     */
    public void recordStorageInteraction(BlockHitResult hitResult) {
        if (Minecraft.getInstance().level == null || hitResult == null) return;

        BlockPos pos = hitResult.getBlockPos();
        net.minecraft.world.level.block.Block block = Minecraft.getInstance().level.getBlockState(pos).getBlock();
        if (block == Blocks.CHEST
            || block == Blocks.TRAPPED_CHEST
            || block == Blocks.BARREL
            || block == Blocks.HOPPER
            || block == Blocks.DROPPER
            || block == Blocks.DISPENSER
            || block instanceof ShulkerBoxBlock) {
            pendingStorageInteraction = pos.immutable();
            pendingStorageInteractionTime = System.currentTimeMillis();
        }
    }

    /**
     * Returns the storage block that opened the current GUI, if it immediately
     * followed a real block interaction.
     */
    public BlockPos consumePendingStorageInteraction() {
        if (pendingStorageInteraction == null) return null;

        BlockPos result = pendingStorageInteraction;
        pendingStorageInteraction = null;
        if (System.currentTimeMillis() - pendingStorageInteractionTime > INTERACTION_VALIDITY_MS) {
            return null;
        }
        return result;
    }

    /** Refreshes slot usage for the chest that is currently open. */
    public void updateCurrentChestContents(AbstractContainerScreen<?> screen) {
        if (screen.getMenu() == null || currentOpenChestPos == null) return;

        try {
            int containerSlots = Math.max(0, screen.getMenu().slots.size() - 36);
            int usedSlots = 0;

            for (int i = 0; i < containerSlots; i++) {
                Slot slot = screen.getMenu().slots.get(i);
                if (!slot.getItem().isEmpty()) {
                    usedSlots++;
                }
            }

            String key = currentOpenChestPos.getX() + "," + currentOpenChestPos.getY() + "," + currentOpenChestPos.getZ();
            ChestInfo newInfo = new ChestInfo(usedSlots, containerSlots);
            ChestInfo oldInfo = chestCache.get(key);

            if (oldInfo == null || oldInfo.usedSlots != usedSlots || oldInfo.totalSlots != containerSlots) {
                chestCache.put(key, newInfo);
                markDirty();
            }

            for (BlockPos pos : currentOpenChestPositions) {
                chestCache.put(pos.getX() + "," + pos.getY() + "," + pos.getZ(), newInfo);
            }

            SeymourAnalyzer.LOGGER.debug("[ChestSpace] Tracked chest at {} with {}/{} slots used",
                currentOpenChestPos, usedSlots, containerSlots);
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Error tracking chest space", e);
        }
    }

    private java.util.List<BlockPos> resolveConnectedChestBlocks(BlockPos basePos) {
        java.util.List<BlockPos> positions = new java.util.ArrayList<>();
        positions.add(basePos);

        if (Minecraft.getInstance().level == null) return positions;

        net.minecraft.world.level.block.state.BlockState state = Minecraft.getInstance().level.getBlockState(basePos);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) {
            return positions;
        }

        net.minecraft.world.level.block.state.properties.ChestType type = state.getValue(
            net.minecraft.world.level.block.ChestBlock.TYPE
        );
        if (type == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            return positions;
        }

        net.minecraft.core.Direction partnerDirection = net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state);
        BlockPos partnerPos = basePos.relative(partnerDirection);
        net.minecraft.world.level.block.state.BlockState partnerState = Minecraft.getInstance().level.getBlockState(partnerPos);
        if (partnerState.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
            && partnerState.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            positions.add(partnerPos);
        }
        return positions;
    }

    public void clearCurrentOpenChest() {
        currentOpenChestPos = null;
        currentOpenChestPositions.clear();
    }

    public BlockPos getCurrentOpenChestPos() {
        return currentOpenChestPos;
    }

    public java.util.List<BlockPos> getCurrentOpenChestPositions() {
        return new java.util.ArrayList<>(currentOpenChestPositions);
    }

    /**
     * Get free slots for a chest position. Returns -1 if not in cache.
     */
    public int getFreeSlotsForChest(BlockPos chestPos) {
        if (chestPos == null) return -1;

        String key = chestPos.getX() + "," + chestPos.getY() + "," + chestPos.getZ();
        ChestInfo info = chestCache.get(key);

        if (info == null) {
            return -1;
        }

        return info.totalSlots - info.usedSlots;
    }

    /**
     * Check if a specific chest has space
     */
    public boolean hasSpace(BlockPos chestPos) {
        return getFreeSlotsForChest(chestPos) > 0;
    }

    /**
     * Clear all cache
     */
    public void clearCache() {
        chestCache.clear();
        isDirty.set(true);
        save();
    }

    /**
     * Toggle highlight mode
     */
    public void toggleHighlight() {
        highlightEnabled = !highlightEnabled;
    }

    /**
     * Get cached chest info (for rendering)
     */
    public Map<String, ChestInfo> getCacheSnapshot() {
        return new HashMap<>(chestCache);
    }
}


