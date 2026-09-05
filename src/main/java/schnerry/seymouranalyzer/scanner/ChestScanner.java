package schnerry.seymouranalyzer.scanner;

import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.analyzer.ColorAnalyzer;
import schnerry.seymouranalyzer.analyzer.PatternDetector;
import schnerry.seymouranalyzer.config.ClothConfig;
import schnerry.seymouranalyzer.data.ArmorPiece;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.util.ItemStackUtils;
import schnerry.seymouranalyzer.util.ScoreboardUtils;
import schnerry.seymouranalyzer.util.SkyBlockProfileTracker;
import schnerry.seymouranalyzer.util.StringUtility;

import java.util.*;

/**
 * Scans chests and item frames for Seymour armor pieces
 * Exact port from ChatTriggers index.js scanning logic
 */
public class ChestScanner {
    @Getter
    private boolean scanningEnabled = false;
    @Getter
    private boolean exportingEnabled = false;
    private final Map<String, ArmorPiece> exportCollection = new HashMap<>();
    private long lastChestOpenTime = 0;
    private long lastLocationUpdateTime = 0;
    private long lastItemFrameScanTime = 0;
    private AbstractContainerScreen<?> trackedChestScreen = null;
    private static final long SCAN_DELAY_MS = 250;
    private static final long LOCATION_UPDATE_INTERVAL_MS = 250;
    private static final long ITEM_FRAME_SCAN_INTERVAL_MS = 5000;

    // ── Batch accumulation ───────────────────────────────────────────────────
    // Accumulate all pieces scanned from a single chest before sending ONE message.
    // Flushed when the screen changes (new chest opened) or closes.
    private AbstractContainerScreen<?> pendingBatchScreen = null;
    private final List<String> pendingBatchUuids = new ArrayList<>();

    // ── Badge registry ───────────────────────────────────────────────────────
    // Keeps the last MAX_BADGES scan events so they can be undone.
    private static final int MAX_BADGES = 20;
    private final Deque<ScanBadge> badgeHistory = new ArrayDeque<>();

    // ────────────────────────────────────────────────────────────────────────

    public void startScan() {
        if (exportingEnabled) {
            SeymourAnalyzer.LOGGER.warn("Cannot start scanning while exporting");
            return;
        }
        if (!SkyBlockProfileTracker.hasActiveProfile()) {
            SeymourAnalyzer.LOGGER.warn("Cannot start scanning before the active SkyBlock profile is known");
            return;
        }
        scanningEnabled = true;
    }

    public void stopScan() {
        scanningEnabled = false;
        // Force save any pending changes when stopping scan
        CollectionManager.getInstance().forceSync();
        // Cache will automatically regenerate on next tick when collection size change is detected
        SeymourAnalyzer.LOGGER.info("Scanning stopped, collection saved");
    }


    public void startExport() {
        if (scanningEnabled) {
            SeymourAnalyzer.LOGGER.warn("Cannot start exporting while scanning");
            return;
        }
        exportCollection.clear();
        exportingEnabled = true;
    }

    public void stopExport() {
        exportingEnabled = false;
    }


    public Map<String, ArmorPiece> getExportCollection() {
        return new HashMap<>(exportCollection);
    }

    // ── Badge registry access ────────────────────────────────────────────────

    /** Register a newly created badge and evict the oldest if over limit. */
    public void registerBadge(ScanBadge badge) {
        badgeHistory.addFirst(badge);
        if (badgeHistory.size() > MAX_BADGES) {
            badgeHistory.removeLast();
        }
    }

    /** Look up a badge by its ID. Returns null if not found / already consumed. */
    public ScanBadge getBadge(String badgeId) {
        for (ScanBadge b : badgeHistory) {
            if (b.getBadgeId().equals(badgeId)) return b;
        }
        return null;
    }

    /** Remove a badge (called after an undo). */
    public void removeBadge(String badgeId) {
        badgeHistory.removeIf(b -> b.getBadgeId().equals(badgeId));
    }

    // ── Flush helpers ────────────────────────────────────────────────────────

    /**
     * Flush the pending chest batch: create a ScanBadge, send the consolidated
     * chat message (with clickable [Undo]), and reset the pending state.
     */
    private void flushChestBatch(Minecraft client) {
        if (pendingBatchUuids.isEmpty()) {
            pendingBatchUuids.clear();
            return;
        }

        List<String> batchCopy = new ArrayList<>(pendingBatchUuids);
        pendingBatchUuids.clear();

        if (!exportingEnabled) {
            // Create and register the badge
            String badgeId = UUID.randomUUID().toString();
            ScanBadge badge = new ScanBadge(badgeId, batchCopy, "chest");
            registerBadge(badge);

            int count = batchCopy.size();
            int total = CollectionManager.getInstance().size();

            if (client.player != null) {
                MutableComponent msg = Component.literal(
                    "§a[Seymour Analyzer] §7Scanned §e" + count +
                    "§7 new piece" + (count == 1 ? "" : "s") +
                    "! Total: §e" + total + " ");

                MutableComponent undoBtn = Component.literal("§4[Undo]");
                undoBtn.withStyle(style -> style
                    .withClickEvent(new ClickEvent.RunCommand("/seymour undo " + badgeId))
                    .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("§7Click to undo this scan and remove §e" +
                            count + " §7piece" + (count == 1 ? "" : "s") + " from the database"))));
                msg.append(undoBtn);

                client.player.sendSystemMessage(msg);
            }
        } else {
            // Export mode – no badge needed, just send the count message
            if (client.player != null) {
                int count = batchCopy.size();
                client.player.sendSystemMessage(
                    Component.literal("§a[Seymour Analyzer] §7Added §e" + count +
                        "§7 piece" + (count == 1 ? "" : "s") +
                        " to export collection! Total: §e" + exportCollection.size())
                );
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    /**
     * Tick handler - checks for GUI opens and item frame scanning
     */
    public void tick(Minecraft client) {
        long now = System.currentTimeMillis();

        // ── Location update: ALWAYS active, no scan-mode guard ───────────────
        // Keeps chest coordinates up to date whenever the player opens any container.
        // Two independent guards prevent false updates:
        //   1. Title blacklist  – catches known non-chest screens (trade, auctions) even if
        //                         the player happens to be looking at a block while they're open.
        //   2. Storage-block check – hitResult must point at a real chest/barrel/shulker,
        //                            which reliably excludes NPC shops, trade menus, etc.
        if (client.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            String title = screen.getTitle().getString();
            boolean isKnownNonChestScreen = title.contains("Auctions")
                || title.contains("Showcase")
                || title.startsWith("Trading with ")
                || TradeScanner.isHypixelTradeTitle(title);
            if (!isKnownNonChestScreen && trackedChestScreen != screen) {
                trackedChestScreen = screen;
                BlockPos storagePos = ChestSpaceTracker.getInstance().consumePendingStorageInteraction();
                if (storagePos != null) {
                    ChestSpaceTracker.getInstance().onChestOpen(screen, storagePos);
                }
                if (now - lastLocationUpdateTime >= LOCATION_UPDATE_INTERVAL_MS) {
                    lastLocationUpdateTime = now;
                    updateKnownPieceLocations(screen, client);
                }
            } else if (!isKnownNonChestScreen && trackedChestScreen == screen) {
                ChestSpaceTracker.getInstance().updateCurrentChestContents(screen);
            }
        } else {
            ChestSpaceTracker.getInstance().clearCurrentOpenChest();
            trackedChestScreen = null;
        }

        if (!scanningEnabled && !exportingEnabled) return;

        // ── Scan / export ─────────────────────────────────────────────────────
        if (client.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            // New chest opened (different screen object) – flush the previous batch first
            if (pendingBatchScreen != null && pendingBatchScreen != screen) {
                flushChestBatch(client);
            }
            pendingBatchScreen = screen;

            if (now - lastChestOpenTime >= SCAN_DELAY_MS) {
                lastChestOpenTime = now;
                scanChestContents(screen, client);
            }
        } else {
            // Screen closed or changed to non-container – flush any pending batch and clear current chest highlight
            ChestSpaceTracker.getInstance().clearCurrentOpenChest();
            if (pendingBatchScreen != null) {
                flushChestBatch(client);
                pendingBatchScreen = null;
            }
        }

        // Check for item frame scanning (every 5 seconds)
        if (ClothConfig.getInstance().isItemFramesEnabled() && now - lastItemFrameScanTime >= ITEM_FRAME_SCAN_INTERVAL_MS) {
            lastItemFrameScanTime = now;
            readItemFrames(client);
        }
    }

    /**
     * Silently update the stored chest location of every known DB piece visible in
     * the container portion of the current screen (player-inventory slots excluded).
     * Runs regardless of scanningEnabled – just opening a chest is enough.
     */
    private void updateKnownPieceLocations(AbstractContainerScreen<?> screen, Minecraft client) {
        try {
            if (screen.getMenu() == null) return;

            ArmorPiece.ChestLocation chestLoc = getChestLocationFromLooking(client);
            if (chestLoc == null) return;

            List<Slot> slots = screen.getMenu().slots;
            // Only look at container slots, not the player-inventory tail (last 36 slots)
            int containerSlots = Math.max(0, slots.size() - 36);

            for (int i = 0; i < containerSlots; i++) {
                ItemStack stack = slots.get(i).getItem();
                if (stack.isEmpty()) continue;

                String itemName = stack.getHoverName().getString();
                if (!isSeymourArmor(itemName)) continue;

                String uuid = ItemStackUtils.getOrCreateItemUUID(stack);
                if (uuid == null) continue;

                // updatePieceLocation is a no-op if UUID not in DB or location unchanged
                CollectionManager.getInstance().updatePieceLocation(uuid, chestLoc);
            }
        } catch (Exception e) {
            // Best-effort – never crash the tick
        }
    }

    /**
     * Scan chest contents - exact port from index.js scanChestContents()
     * NOTE: no longer sends chat messages directly; accumulates into pendingBatchUuids.
     */
    private void scanChestContents(AbstractContainerScreen<?> screen, Minecraft client) {
        if (!scanningEnabled && !exportingEnabled) {
            // Still track the exact chest currently open, even if scanning is off.
            try {
                if (screen.getMenu() == null) return;
                String title = screen.getTitle().getString();
                if (title.contains("Auctions") || title.contains("Showcase")) return;

                ArmorPiece.ChestLocation chestLoc = getChestLocationFromLooking(client);
                if (chestLoc != null && client.player != null) {
                    BlockPos chestPos = new BlockPos(chestLoc.x, chestLoc.y, chestLoc.z);
                    ChestSpaceTracker.getInstance().onChestOpen(screen, chestPos);
                }
            } catch (Exception e) {
                SeymourAnalyzer.LOGGER.error("Error tracking chest space", e);
            }
            return;
        }

        try {
            if (screen.getMenu() == null) return;
            String title = screen.getTitle().getString();
            if (title.contains("Auctions") || title.contains("Showcase")) return;

            ArmorPiece.ChestLocation chestLoc = getChestLocationFromLooking(client);
            List<Slot> slots = screen.getMenu().slots;

            for (Slot slot : slots) {
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;

                String itemName = stack.getHoverName().getString();
                if (!StringUtility.isSeymourArmor(itemName)) continue;

                String uuid = ItemStackUtils.getOrCreateItemUUID(stack);
                if (uuid == null) continue;

                // Check if already in collection/export
                if (CollectionManager.getInstance().hasPiece(uuid) && !exportingEnabled) continue;
                if (exportingEnabled && exportCollection.containsKey(uuid)) continue;
                // Don't add duplicates within the same batch
                if (pendingBatchUuids.contains(uuid)) continue;

                String itemHex = ItemStackUtils.extractHex(stack);
                if (itemHex == null) continue;

                ColorAnalyzer.AnalysisResult analysis = ColorAnalyzer.getInstance().analyzeArmorColor(itemHex, itemName);
                if (analysis == null) continue;

                ColorAnalyzer.ColorMatch best = analysis.bestMatch();
                int itemRgb = Integer.parseInt(itemHex, 16);
                int targetRgb = Integer.parseInt(best.targetHex(), 16);
                int absoluteDist = Math.abs(((itemRgb >> 16) & 0xFF) - ((targetRgb >> 16) & 0xFF)) +
                                  Math.abs(((itemRgb >> 8) & 0xFF) - ((targetRgb >> 8) & 0xFF)) +
                                  Math.abs((itemRgb & 0xFF) - (targetRgb & 0xFF));

                String wordMatch = PatternDetector.getInstance().detectWordMatch(itemHex);
                String specialPattern = PatternDetector.getInstance().detectPattern(itemHex);

                // Store top 3 matches
                List<ArmorPiece.ColorMatch> top3Matches = new ArrayList<>();
                for (int m = 0; m < 3 && m < analysis.top3Matches().size(); m++) {
                    ColorAnalyzer.ColorMatch match = analysis.top3Matches().get(m);
                    int matchRgb = Integer.parseInt(match.targetHex(), 16);
                    int matchAbsoluteDist = Math.abs(((itemRgb >> 16) & 0xFF) - ((matchRgb >> 16) & 0xFF)) +
                                           Math.abs(((itemRgb >> 8) & 0xFF) - ((matchRgb >> 8) & 0xFF)) +
                                           Math.abs((itemRgb & 0xFF) - (matchRgb & 0xFF));

                    top3Matches.add(new ArmorPiece.ColorMatch(
                        match.name(),
                        match.targetHex(),
                        match.deltaE(),
                        matchAbsoluteDist,
                        match.tier()
                    ));
                }

                // Create armor piece
                ArmorPiece piece = new ArmorPiece();
                piece.setPieceName(StringUtility.removeFormatting(itemName));
                piece.setUuid(uuid);
                piece.setHexcode(itemHex);
                piece.setSpecialPattern(specialPattern);
                piece.setBestMatch(new ArmorPiece.BestMatch(
                    best.name(),
                    best.targetHex(),
                    best.deltaE(),
                    absoluteDist,
                    analysis.tier()
                ));
                piece.setAllMatches(top3Matches);
                piece.setWordMatch(wordMatch);
                piece.setChestLocation(chestLoc);
                piece.setTimestamp(System.currentTimeMillis());

                if (!exportingEnabled) {
                    CollectionManager.getInstance().addPiece(piece);
                } else {
                    exportCollection.put(uuid, piece);
                }

                // Track in current batch
                pendingBatchUuids.add(uuid);
            }

        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Error scanning chest contents", e);
        }
    }

    /**
     * Read item frames - exact port from index.js readItemFrames()
     */
    private void readItemFrames(Minecraft client) {
        if (!ClothConfig.getInstance().isItemFramesEnabled() || (!scanningEnabled && !exportingEnabled)) {
            return;
        }

        // If "scan only own island" is active, skip when the scoreboard shows we're elsewhere
        if (ClothConfig.getInstance().isScanOnlyOwnIsland() && !ScoreboardUtils.isOnOwnIsland(client)) {
            return;
        }

        try {
            Level level = client.level;
            if (level == null) return;

            List<ItemFrame> itemFrames = new ArrayList<>();

            // Create a bounding box around the player (64 block radius in all directions)
            if (client.player != null) {
                double x = client.player.getX();
                double y = client.player.getY();
                double z = client.player.getZ();
                double radius = 64.0;

                net.minecraft.world.phys.AABB searchBox = new net.minecraft.world.phys.AABB(
                    x - radius, y - radius, z - radius,
                    x + radius, y + radius, z + radius
                );

                // Use entity selector to find all item frame entities within the box
                itemFrames.addAll(level.getEntitiesOfClass(ItemFrame.class, searchBox, frame -> true));
            }

            if (itemFrames.isEmpty()) return;

            List<String> frameBatchUuids = new ArrayList<>();

            for (ItemFrame frame : itemFrames) {
                ItemStack stack = frame.getItem();

                if (stack.isEmpty()) continue;

                ArmorPiece.ChestLocation chestLoc = new ArmorPiece.ChestLocation(
                    (int) Math.floor(frame.getX()),
                    (int) Math.floor(frame.getY()),
                    (int) Math.floor(frame.getZ())
                );

                String itemName = stack.getHoverName().getString();

                if (!StringUtility.isSeymourArmor(itemName)) continue;

                String uuid = ItemStackUtils.getOrCreateItemUUID(stack);

                if (uuid == null) continue;

                if (CollectionManager.getInstance().hasPiece(uuid) && !exportingEnabled) continue;
                if (exportingEnabled && exportCollection.containsKey(uuid)) continue;

                String itemHex = ItemStackUtils.extractHex(stack);

                if (itemHex == null) continue;

                ColorAnalyzer.AnalysisResult analysis = ColorAnalyzer.getInstance().analyzeArmorColor(itemHex, itemName);
                if (analysis == null) continue;

                ColorAnalyzer.ColorMatch best = analysis.bestMatch();
                int itemRgb = Integer.parseInt(itemHex, 16);
                int targetRgb = Integer.parseInt(best.targetHex(), 16);
                int absoluteDist = Math.abs(((itemRgb >> 16) & 0xFF) - ((targetRgb >> 16) & 0xFF)) +
                                  Math.abs(((itemRgb >> 8) & 0xFF) - ((targetRgb >> 8) & 0xFF)) +
                                  Math.abs((itemRgb & 0xFF) - (targetRgb & 0xFF));

                String wordMatch = PatternDetector.getInstance().detectWordMatch(itemHex);
                String specialPattern = PatternDetector.getInstance().detectPattern(itemHex);

                // Store top 3 matches
                List<ArmorPiece.ColorMatch> top3Matches = new ArrayList<>();
                for (int m = 0; m < 3 && m < analysis.top3Matches().size(); m++) {
                    ColorAnalyzer.ColorMatch match = analysis.top3Matches().get(m);
                    int matchRgb = Integer.parseInt(match.targetHex(), 16);
                    int matchAbsoluteDist = Math.abs(((itemRgb >> 16) & 0xFF) - ((matchRgb >> 16) & 0xFF)) +
                                           Math.abs(((itemRgb >> 8) & 0xFF) - ((matchRgb >> 8) & 0xFF)) +
                                           Math.abs((itemRgb & 0xFF) - (matchRgb & 0xFF));

                    top3Matches.add(new ArmorPiece.ColorMatch(
                        match.name(),
                        match.targetHex(),
                        match.deltaE(),
                        matchAbsoluteDist,
                        match.tier()
                    ));
                }

                // Create armor piece
                ArmorPiece piece = new ArmorPiece();
                piece.setPieceName(StringUtility.removeFormatting(itemName));
                piece.setUuid(uuid);
                piece.setHexcode(itemHex);
                piece.setSpecialPattern(specialPattern);
                piece.setBestMatch(new ArmorPiece.BestMatch(
                    best.name(),
                    best.targetHex(),
                    best.deltaE(),
                    absoluteDist,
                    analysis.tier()
                ));
                piece.setAllMatches(top3Matches);
                piece.setWordMatch(wordMatch);
                piece.setChestLocation(chestLoc);
                piece.setTimestamp(System.currentTimeMillis());

                if (scanningEnabled && !exportingEnabled) {
                    CollectionManager.getInstance().addPiece(piece);
                } else if (exportingEnabled) {
                    exportCollection.put(uuid, piece);
                }

                frameBatchUuids.add(uuid);
            }

            if (!frameBatchUuids.isEmpty() && !exportingEnabled) {
                int pieceCount = frameBatchUuids.size();
                int total = CollectionManager.getInstance().size();

                // Create and register badge
                String badgeId = UUID.randomUUID().toString();
                ScanBadge badge = new ScanBadge(badgeId, frameBatchUuids, "item_frames");
                registerBadge(badge);

                if (client.player != null) {
                    MutableComponent msg = Component.literal(
                        "§a[Seymour Analyzer] §7Scanned §e" + pieceCount +
                        "§7 new piece" + (pieceCount == 1 ? "" : "s") +
                        " from item frames! Total: §e" + total + " ");

                    MutableComponent undoBtn = Component.literal("§4[Undo]");
                    undoBtn.withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/seymour undo " + badgeId))
                        .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal("§7Click to undo this scan and remove §e" +
                                pieceCount + " §7piece" + (pieceCount == 1 ? "" : "s") + " from the database"))));
                    msg.append(undoBtn);

                    client.player.sendSystemMessage(msg);
                }
            } else if (!frameBatchUuids.isEmpty()) {
                // exportingEnabled is true here
                int pieceCount = frameBatchUuids.size();
                if (client.player != null) {
                    client.player.sendSystemMessage(
                        Component.literal("§a[Seymour Analyzer] §7Added §e" + pieceCount +
                            "§7 piece" + (pieceCount == 1 ? "" : "s") +
                            " from item frames to export collection! Total: §e" + exportCollection.size())
                    );
                }
            }

        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Error scanning item frames", e);
        }
    }

    /**
     * Returns true if the player's crosshair is currently pointing at a physical
     * storage block (chest, trapped chest, barrel, or any shulker box).
     * <p>
     * This is used to guard location updates so that only genuine chest opens
     * trigger a coordinate update. Trade menus, NPC shops, and auction houses are
     * opened by interacting with entities, not blocks, so hitResult will never be
     * a storage block when those screens are open.
     */
    private boolean isPlayerLookingAtStorageBlock(Minecraft client) {
        if (client.player == null || client.level == null) return false;
        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        net.minecraft.world.level.block.Block block = client.level.getBlockState(pos).getBlock();
        return block == Blocks.CHEST
            || block == Blocks.TRAPPED_CHEST
            || block == Blocks.BARREL
            || block == Blocks.HOPPER
            || block == Blocks.DROPPER
            || block == Blocks.DISPENSER
            || block instanceof ShulkerBoxBlock;
    }

    /**
     * Get chest location from player's crosshair.
     * If the GUI is already open and the crosshair no longer points at a block,
     * fall back to the nearest real storage block around the player.
     */
    private ArmorPiece.ChestLocation getChestLocationFromLooking(Minecraft client) {
        if (client.player == null) return null;

        HitResult hit = client.hitResult;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = ((BlockHitResult) hit).getBlockPos();
            return new ArmorPiece.ChestLocation(pos.getX(), pos.getY(), pos.getZ());
        }

        return null;
    }

    /**
     * Extract hex from item - delegates to ItemStackUtils
     */
    public String extractHex(ItemStack stack) {
        return ItemStackUtils.extractHex(stack);
    }

    /**
     * Extract UUID from item - delegates to ItemStackUtils
     */
    public String getOrCreateItemUUID(ItemStack stack) {
        return ItemStackUtils.getOrCreateItemUUID(stack);
    }

    /**
     * Check if item is Seymour armor - delegates to StringUtility
     */
    public static boolean isSeymourArmor(String itemName) {
        return StringUtility.isSeymourArmor(itemName);
    }
}
