package schnerry.seymouranalyzer.render;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import schnerry.seymouranalyzer.analyzer.ColorAnalyzer;
import schnerry.seymouranalyzer.analyzer.PatternDetector;
import schnerry.seymouranalyzer.config.ClothConfig;
import schnerry.seymouranalyzer.data.ArmorPiece;
import schnerry.seymouranalyzer.data.ChecklistCache;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.scanner.ChestScanner;
import schnerry.seymouranalyzer.util.ItemStackUtils;
import schnerry.seymouranalyzer.util.PieceTypeUtil;
import schnerry.seymouranalyzer.util.StringUtility;
import schnerry.seymouranalyzer.util.ColorMath;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders info box showing detailed color analysis for hovered items
 * Exact port from ChatTriggers index.js
 */
public class InfoBoxRenderer {
    private static final boolean DEBUG = false; // Disable debugging
    private static InfoBoxRenderer instance;
    private static HoveredItemData hoveredItemData = null;
    private static ItemStack lastHoveredStack = null;
    private static int boxX = 10;
    private static int boxY = 10;
    private static float infoBoxScale = 1.0f;

    // Move drag
    private static boolean isDragging = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;

    // Resize drag (corner handle)
    private static boolean isResizing = false;
    private static double resizeDragStartX = 0;
    private static double resizeDragStartY = 0;
    private static float resizeDragStartScale = 1.0f;
    private static int resizeDragStartBoxW = 0;
    private static int resizeDragStartBoxH = 0;
    private static final int HANDLE_SIZE = 12; // px of corner resize handle

    private static Object currentOpenGui = null;

    /** Cache: targetHex → best owned ΔE. Cleared when collection changes. */
    private static final Map<String, Double> ownedDeltaCache = new ConcurrentHashMap<>();
    /** UUID of the last item whose HoveredItemData was fully computed. */
    private static String lastComputedUuid = null;

    public static void resetPosition() {
        boxX = 50;
        boxY = 80;
        infoBoxScale = 1.0f;
        ClothConfig config = ClothConfig.getInstance();
        config.setInfoBoxX(boxX);
        config.setInfoBoxY(boxY);
        config.setInfoBoxScale(infoBoxScale);
        config.save();
    }

    private InfoBoxRenderer() {
        // Load position and scale from config
        ClothConfig config = ClothConfig.getInstance();
        boxX = config.getInfoBoxX();
        boxY = config.getInfoBoxY();
        infoBoxScale = config.getInfoBoxScale();

        // Register screen render callback to render AFTER screen elements
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
            ScreenEvents.afterExtract(screen).register((scr, context, mouseX, mouseY, delta) ->
                render(context, delta, scr)));
    }

    public static InfoBoxRenderer getInstance() {
        if (instance == null) {
            instance = new InfoBoxRenderer();
        }
        return instance;
    }

    /**
     * Called by mixin to set the currently hovered item directly
     * This avoids timing issues where the slot might be empty by the time we check it
     */
    public void setHoveredItem(ItemStack stack, String itemName) {
        if (DEBUG) {
            System.out.println("[InfoBox] setHoveredItem() called");
            System.out.println("[InfoBox]   Item: " + itemName);
        }

        // Store the stack for debugger access
        lastHoveredStack = stack.copy();

        // Check if it's a Seymour armor piece
        if (StringUtility.isSeymourArmor(itemName)) {
            if (DEBUG) System.out.println("[InfoBox] Is Seymour armor, analyzing...");

            // Skip full recomputation if we already have data for this exact item
            String uuid = ItemStackUtils.getOrCreateItemUUID(stack);
            if (uuid != null && uuid.equals(lastComputedUuid) && hoveredItemData != null) {
                if (DEBUG) System.out.println("[InfoBox] Skipping recompute - same UUID as last item");
                return;
            }

            setHoveredItemData(stack, itemName);
        } else {
            if (DEBUG) System.out.println("[InfoBox] Not Seymour armor, ignoring");
            // Don't clear data here - let it persist
        }
    }

    /**
     * Called by mixin to clear the hovered item when nothing is focused
     */
    public void clearHoveredItem() {
        // Don't clear immediately - let the data persist until GUI changes
        if (DEBUG) System.out.println("[InfoBox] clearHoveredItem() called (but not clearing to persist data)");
    }

    /**
     * Force clear the hovered item data cache
     * Called after checklist cache regeneration to ensure stale data is not shown
     */
    public static void forceCloseHoveredDataCache() {
        hoveredItemData = null;
        lastHoveredStack = null;
        lastComputedUuid = null;
        ownedDeltaCache.clear();
        if (DEBUG) System.out.println("[InfoBox] Forced clear of hovered item data cache");
    }

    /**
     * Invalidate the owned-delta cache when the collection changes (scan, clear, etc.).
     * Forces recomputation of comparison values on the next hover.
     */
    public static void invalidateOwnedDeltaCache() {
        ownedDeltaCache.clear();
        lastComputedUuid = null; // Force recompute even for the currently hovered item
    }

    /**
     * Get the last hovered ItemStack (for debugger access)
     */
    public ItemStack getLastHoveredStack() {
        return lastHoveredStack;
    }

    private static class HoveredItemData {
        String bestMatchName;
        String bestMatchHex;
        double deltaE;
        int absoluteDist;
        int tier;
        boolean isFadeDye;
        boolean isCustom;
        String itemHex;
        ColorAnalyzer.AnalysisResult analysisResult;
        String wordMatch;
        String specialPattern;
        String uuid;
        String itemName;
        long timestamp;
        int dupeCount;
        boolean isOwned;
        boolean isNeededForChecklist;
        int matchTier; // Tier of the assigned match in checklist
        /** For each of the top 3 matches: delta of the best owned piece to that target (-1 = none owned) */
        double[] ownedBestDeltasForTop3;

        HoveredItemData(String bestMatchName, String bestMatchHex, double deltaE, int absoluteDist,
                       int tier, boolean isFadeDye, boolean isCustom, String itemHex,
                       ColorAnalyzer.AnalysisResult analysisResult, String wordMatch, String specialPattern,
                       String uuid, String itemName, int dupeCount, boolean isOwned, boolean isNeededForChecklist,
                       int matchTier, double[] ownedBestDeltasForTop3) {
            this.bestMatchName = bestMatchName;
            this.bestMatchHex = bestMatchHex;
            this.deltaE = deltaE;
            this.absoluteDist = absoluteDist;
            this.tier = tier;
            this.isFadeDye = isFadeDye;
            this.isCustom = isCustom;
            this.itemHex = itemHex;
            this.analysisResult = analysisResult;
            this.wordMatch = wordMatch;
            this.specialPattern = specialPattern;
            this.uuid = uuid;
            this.itemName = itemName;
            this.timestamp = System.currentTimeMillis();
            this.dupeCount = dupeCount;
            this.isOwned = isOwned;
            this.isNeededForChecklist = isNeededForChecklist;
            this.matchTier = matchTier;
            this.ownedBestDeltasForTop3 = ownedBestDeltasForTop3;
        }
    }

    @SuppressWarnings("unused") // delta is required by Fabric API callback signature
    private static void render(GuiGraphicsExtractor guiGraphics, float delta, Screen currentScreen) {
        if (DEBUG) {
            System.out.println("[InfoBox] render() called");
            System.out.println("[InfoBox] Current screen instance: " + System.identityHashCode(currentScreen));
        }

        ClothConfig config = ClothConfig.getInstance();
        if (!config.isInfoBoxEnabled()) {
            if (DEBUG) System.out.println("[InfoBox] InfoBox disabled in config");
            return;
        }

        Minecraft client = Minecraft.getInstance();

        // Check if GUI changed - if so, clear data
        if (currentOpenGui != currentScreen) {
            if (DEBUG) System.out.println("[InfoBox] GUI changed from " + (currentOpenGui != null ? currentOpenGui.getClass().getSimpleName() : "null") + " to " + currentScreen.getClass().getSimpleName());
            currentOpenGui = currentScreen;
            hoveredItemData = null; // Clear data when switching GUIs
            isDragging = false;
        }

        // The mixin now calls setHoveredItem() directly, so we don't need updateHoveredItem()

        // Handle dragging
        handleDragging(client);

        // Render the box if we have data (either from current hover or persisted from previous hover)
        if (hoveredItemData != null) {
            if (DEBUG) System.out.println("[InfoBox] Rendering box with data");
            renderInfoBox(guiGraphics, client);
        } else {
            if (DEBUG) System.out.println("[InfoBox] No hovered item data to render");
        }
    }

    private static void setHoveredItemData(ItemStack stack, String itemName) {
        String hex = ItemStackUtils.extractHex(stack);
        if (hex == null) return;

        String uuid = ItemStackUtils.getOrCreateItemUUID(stack);

        ColorAnalyzer.AnalysisResult analysis = ColorAnalyzer.getInstance().analyzeArmorColor(hex, itemName);
        if (analysis == null || analysis.bestMatch() == null) return;

        ClothConfig config = ClothConfig.getInstance();

        String wordMatch = config.isWordsEnabled() ? PatternDetector.getInstance().detectWordMatch(hex) : null;
        String specialPattern = config.isPatternsEnabled() ? PatternDetector.getInstance().detectPattern(hex) : null;

        int itemRgb = Integer.parseInt(hex, 16);
        int targetRgb = Integer.parseInt(analysis.bestMatch().targetHex(), 16);
        int absoluteDist = Math.abs(((itemRgb >> 16) & 0xFF) - ((targetRgb >> 16) & 0xFF)) +
                          Math.abs(((itemRgb >> 8) & 0xFF) - ((targetRgb >> 8) & 0xFF)) +
                          Math.abs((itemRgb & 0xFF) - (targetRgb & 0xFF));

        // Get checklist status from cache for the best match hex
        ChecklistStatus checklistStatus = getChecklistStatusForHex(analysis.bestMatch().targetHex(), itemName);
        int dupeCount = config.isDupesEnabled() ? checkDupeCount(hex, uuid) : 0;

        // Compute owned-best deltas for each of the top matches (for shift comparison)
        // Cache key includes the hovered piece's UUID so each piece gets its own
        // "best OTHER owned" value (excluding itself), preventing stale cache hits
        // when multiple pieces share the same target hex in their top-3.
        List<ColorAnalyzer.ColorMatch> topMatches = analysis.top3Matches();
        double[] ownedBestDeltas = new double[topMatches.size()];
        for (int i = 0; i < ownedBestDeltas.length; i++) {
            String targetHex = topMatches.get(i).targetHex();
            String cacheKey = targetHex + "|" + (uuid != null ? uuid : "");
            ownedBestDeltas[i] = ownedDeltaCache.computeIfAbsent(
                cacheKey, k -> findBestOwnedDeltaForTarget(targetHex, uuid));
        }

        hoveredItemData = new HoveredItemData(
            analysis.bestMatch().name(),
            analysis.bestMatch().targetHex(),
            analysis.bestMatch().deltaE(),
            absoluteDist,
            analysis.tier(),
            analysis.bestMatch().isFade(),
            config.getCustomColors().containsKey(analysis.bestMatch().name()),
            hex,
            analysis,
            wordMatch,
            specialPattern,
            uuid,
            itemName,
            dupeCount,
            checklistStatus.hasMatch,
            checklistStatus.isNeeded,
            checklistStatus.matchTier,
            ownedBestDeltas
        );

        // Mark this UUID as computed so we skip recomputation on the next frame
        lastComputedUuid = uuid;
    }

    private static class ChecklistStatus {
        boolean hasMatch;
        boolean isNeeded;
        int matchTier;

        ChecklistStatus(boolean hasMatch, boolean isNeeded, int matchTier) {
            this.hasMatch = hasMatch;
            this.isNeeded = isNeeded;
            this.matchTier = matchTier;
        }
    }

    /**
     * Get checklist status for a target hex by checking the checklist cache.
     */
    private static ChecklistStatus getChecklistStatusForHex(String targetHex, String itemName) {
        ChecklistCache cache = ChecklistCache.getInstance();
        String hexUpper = targetHex.toUpperCase();

        String pieceType = PieceTypeUtil.detectPieceType(itemName);
        if (pieceType == null) {
            return new ChecklistStatus(false, false, Integer.MAX_VALUE);
        }

        for (ChecklistCache.CategoryCache categoryCache : cache.getNormalColorCache().values()) {
            if (categoryCache.matchesByIndex != null) {
                for (ChecklistCache.StageMatches stageMatches : categoryCache.matchesByIndex.values()) {
                    if (stageMatches.stageHex != null && stageMatches.stageHex.equalsIgnoreCase(hexUpper)) {
                        ChecklistCache.MatchInfo matchInfo = getMatchForPieceType(stageMatches, pieceType);
                        if (matchInfo != null) {
                            return new ChecklistStatus(true, true, getTierFromMatch(matchInfo));
                        }
                        return new ChecklistStatus(false, true, Integer.MAX_VALUE);
                    }
                }
            }
        }

        for (ChecklistCache.CategoryCache categoryCache : cache.getFadeDyeOptimalCache().values()) {
            if (categoryCache.matchesByIndex != null) {
                for (ChecklistCache.StageMatches stageMatches : categoryCache.matchesByIndex.values()) {
                    if (stageMatches.stageHex != null && stageMatches.stageHex.equalsIgnoreCase(hexUpper)) {
                        ChecklistCache.MatchInfo matchInfo = getMatchForPieceType(stageMatches, pieceType);
                        if (matchInfo != null) {
                            return new ChecklistStatus(true, true, getTierFromMatch(matchInfo));
                        }
                        return new ChecklistStatus(false, true, Integer.MAX_VALUE);
                    }
                }
            }
        }

        return new ChecklistStatus(false, false, Integer.MAX_VALUE);
    }

    private static ChecklistCache.MatchInfo getMatchForPieceType(ChecklistCache.StageMatches stageMatches, String pieceType) {
        return switch (pieceType) {
            case "helmet" -> stageMatches.helmet;
            case "chestplate" -> stageMatches.chestplate;
            case "leggings" -> stageMatches.leggings;
            case "boots" -> stageMatches.boots;
            default -> null;
        };
    }

    private static int getTierFromMatch(ChecklistCache.MatchInfo matchInfo) {
        if (matchInfo == null || matchInfo.hex == null) return Integer.MAX_VALUE;
        ColorAnalyzer.AnalysisResult analysis = ColorAnalyzer.getInstance().analyzeArmorColor(matchInfo.hex, matchInfo.name);
        if (analysis != null) {
            return analysis.tier();
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Find the best ΔE to a target hex among all owned pieces (excluding self by uuid).
     * Returns -1 if no owned piece found.
     */
    private static double findBestOwnedDeltaForTarget(String targetHex, String selfUuid) {
        Map<String, ArmorPiece> collection = CollectionManager.getInstance().getCollection();
        double best = Double.MAX_VALUE;
        for (Map.Entry<String, ArmorPiece> entry : collection.entrySet()) {
            if (selfUuid != null && selfUuid.equals(entry.getKey())) continue;
            ArmorPiece piece = entry.getValue();
            if (piece.getHexcode() == null || piece.getHexcode().isEmpty()) continue;
            double delta = ColorMath.calculateDeltaE(piece.getHexcode(), targetHex);
            if (delta < best) best = delta;
        }
        return best == Double.MAX_VALUE ? -1.0 : best;
    }

    private static int checkDupeCount(String hex, String uuid) {
        Map<String, ArmorPiece> collection = CollectionManager.getInstance().getCollection();
        String hexUpper = hex.toUpperCase();
        int dupeCount = 0;
        boolean isThisItemInCollection = false;

        for (Map.Entry<String, ArmorPiece> entry : collection.entrySet()) {
            if (entry.getValue().getHexcode().toUpperCase().equals(hexUpper)) {
                dupeCount++;

                // Check if the hovered item IS this collection piece
                if (java.util.Objects.equals(uuid, entry.getKey())) {
                    isThisItemInCollection = true;
                }
            }
        }

        // For items IN collection: show dupe if there are 2+ pieces with this hex
        if (isThisItemInCollection && dupeCount >= 2) {
            return dupeCount;
        }

        // For items NOT in collection (unscanned): show dupe if there's even 1 piece with this hex
        if (!isThisItemInCollection && dupeCount >= 1) {
            return dupeCount + 1; // +1 to include the hovered piece
        }

        return 0; // No dupe
    }

    private static void handleDragging(Minecraft client) {
        double mouseX = client.mouseHandler.xpos() * client.getWindow().getGuiScaledWidth() / client.getWindow().getWidth();
        double mouseY = client.mouseHandler.ypos() * client.getWindow().getGuiScaledHeight() / client.getWindow().getHeight();

        boolean isShiftHeld = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                             GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        boolean isMouseDown = GLFW.glfwGetMouseButton(client.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (hoveredItemData == null) return;

        int boxWidth  = calculateBoxWidth(hoveredItemData, client, isShiftHeld);
        int boxHeight = calculateBoxHeight(hoveredItemData, isShiftHeld);
        int scaledW   = (int)(boxWidth  * infoBoxScale);
        int scaledH   = (int)(boxHeight * infoBoxScale);

        boolean isOnCornerHandle =
            mouseX >= boxX + scaledW - HANDLE_SIZE && mouseX <= boxX + scaledW &&
            mouseY >= boxY + scaledH - HANDLE_SIZE && mouseY <= boxY + scaledH;

        boolean isMouseOverBox =
            mouseX >= boxX && mouseX <= boxX + scaledW &&
            mouseY >= boxY && mouseY <= boxY + scaledH;

        // ── Start resize (corner handle, no shift needed) ──────────────────────
        if (!isDragging && !isResizing && isOnCornerHandle && isMouseDown) {
            isResizing = true;
            resizeDragStartX     = mouseX;
            resizeDragStartY     = mouseY;
            resizeDragStartScale = infoBoxScale;
            resizeDragStartBoxW  = boxWidth;
            resizeDragStartBoxH  = boxHeight;
        }

        // ── Start move (shift + drag inside box, not on corner handle) ──────────
        if (!isDragging && !isResizing && isShiftHeld && isMouseOverBox && !isOnCornerHandle && isMouseDown) {
            isDragging  = true;
            dragOffsetX = (int)(mouseX - boxX);
            dragOffsetY = (int)(mouseY - boxY);
        }

        // ── Update resize ───────────────────────────────────────────────────────
        if (isResizing && isMouseDown) {
            double dx    = mouseX - resizeDragStartX;
            double dy    = mouseY - resizeDragStartY;
            double delta = (dx + dy) * 0.5;
            float newScale = resizeDragStartScale + (float)(delta / Math.max(resizeDragStartBoxW, resizeDragStartBoxH));
            infoBoxScale = (float) Math.clamp(newScale, 0.4f, 4.0f);
        } else if (isResizing) {
            isResizing = false;
            saveToConfig();
        }

        // ── Update move ─────────────────────────────────────────────────────────
        if (isDragging && isMouseDown) {
            boxX = (int)(mouseX - dragOffsetX);
            boxY = (int)(mouseY - dragOffsetY);
        } else if (isDragging) {
            isDragging = false;
            saveToConfig();
        }
    }

    private static void saveToConfig() {
        ClothConfig config = ClothConfig.getInstance();
        config.setInfoBoxX(boxX);
        config.setInfoBoxY(boxY);
        config.setInfoBoxScale(infoBoxScale);
        config.save();
    }

    private static int calculateBoxHeight(HoveredItemData data, boolean isShiftHeld) {
        if (data == null) return 90;

        ClothConfig config = ClothConfig.getInstance();

        int baseHeight = 28; // title + piece line
        if (config.isWordsEnabled() && data.wordMatch != null) baseHeight += 10;
        if (config.isPatternsEnabled() && data.specialPattern != null) baseHeight += 10;

        if (isShiftHeld) {
            int matchCount = Math.min(10, data.analysisResult.top3Matches().size());
            // "Top N Matches:" header (10px) + each match takes 25px (name + delta lines + gap)
            baseHeight += 10 + matchCount * 25 + 8; // 8px bottom padding
        } else {
            baseHeight += 62; // single match block (closest/target/deltaE/abs/tier = ~60px)
            if (data.isOwned || data.isNeededForChecklist) baseHeight += 10;
            if (config.isDupesEnabled() && data.dupeCount > 0) baseHeight += 10;
        }

        return baseHeight;
    }

    private static int calculateBoxWidth(HoveredItemData data, Minecraft client, boolean isShiftHeld) {
        if (data == null) return 150; // Return minimum width if data is null

        int maxWidth = 300;
        int minWidth = 150;
        int padding = 10; // 5px on each side

        ClothConfig config = ClothConfig.getInstance();
        Font textRenderer = client.font;

        int maxTextWidth = 0;

        // Title
        String title = isShiftHeld ? "§l§nSeymour §7[DRAG]" : "§l§nSeymour Analysis";
        maxTextWidth = Math.max(maxTextWidth, textRenderer.width(title));

        // Piece hex
        String pieceType = PieceTypeUtil.detectPieceType(data.itemName);
        String pieceTypeDisplay = pieceType != null
            ? pieceType.substring(0, 1).toUpperCase() + pieceType.substring(1)
            : "Unknown";
        maxTextWidth = Math.max(maxTextWidth, textRenderer.width("§7Piece: §f#" + data.itemHex + " - " + pieceTypeDisplay));

        // Word match
        if (config.isWordsEnabled() && data.wordMatch != null) {
            maxTextWidth = Math.max(maxTextWidth, textRenderer.width("§d§l✦ WORD: " + data.wordMatch));
        }

        // Pattern match
        if (config.isPatternsEnabled() && data.specialPattern != null) {
            String patternName = getPatternDisplayName(data.specialPattern);
            maxTextWidth = Math.max(maxTextWidth, textRenderer.width("§5§l★ PATTERN: " + patternName));
        }

        if (isShiftHeld) {
            // Top matches (up to 10 non-T3)
            int matchCount = data.analysisResult.top3Matches().size();
            maxTextWidth = Math.max(maxTextWidth, textRenderer.width("§7§lTop " + matchCount + " Matches:"));

            List<ColorAnalyzer.ColorMatch> top3 = data.analysisResult.top3Matches();
            for (int i = 0; i < Math.min(10, top3.size()); i++) {
                ColorAnalyzer.ColorMatch match = top3.get(i);
                String colorPrefix = getTierColorCode(match.tier(), match.isFade(), match.isCustom());
                String line1 = colorPrefix + (i + 1) + ". §f" + match.name() + " §7- #" + match.targetHex();
                // Include worst-case comparison suffix width (+/-xx.xx)
                String line2 = "§7  ΔE: " + colorPrefix + String.format("%.5f", match.deltaE()) +
                               " §7| Abs: §f" + match.absoluteDistance() + " §7| §c-xx.xx";
                maxTextWidth = Math.max(maxTextWidth, textRenderer.width(line1));
                maxTextWidth = Math.max(maxTextWidth, textRenderer.width(line2));
            }
        } else {
            // Single match details
            maxTextWidth = Math.max(maxTextWidth, textRenderer.width("§7Closest: §f" + data.bestMatchName));
            maxTextWidth = Math.max(maxTextWidth, textRenderer.width("§7Target: §7#" + data.bestMatchHex));

            String colorPrefix = getTierColorCode(data.tier, data.isFadeDye, data.isCustom);
            maxTextWidth = Math.max(maxTextWidth, textRenderer.width(colorPrefix + "ΔE: §f" + String.format("%.2f", data.deltaE)));
            maxTextWidth = Math.max(maxTextWidth, textRenderer.width("§7Absolute: §f" + data.absoluteDist));

            String tierText = getTierText(data.tier, data.isFadeDye, data.isCustom);
            maxTextWidth = Math.max(maxTextWidth, textRenderer.width(tierText));

            // Show checkmark if we have an assigned match in checklist
            if (data.isNeededForChecklist) {
                if (data.isOwned) {
                    String ownershipText = data.matchTier <= 1 ? "§a§l✓ Checklist" : "§e§l✓ Checklist";
                    maxTextWidth = Math.max(maxTextWidth, textRenderer.width(ownershipText));
                } else {
                    maxTextWidth = Math.max(maxTextWidth, textRenderer.width("§c§l✗ NEEDED FOR CHECKLIST"));
                }
            }
        }

        // Dupe warning
        if (config.isDupesEnabled() && data.dupeCount > 0) {
            maxTextWidth = Math.max(maxTextWidth, textRenderer.width("§c§l⚠ DUPE HEX §7(x" + data.dupeCount + ")"));
        }

        // Add padding and clamp to min/max
        int calculatedWidth = maxTextWidth + padding;
        return Math.clamp(calculatedWidth, minWidth, maxWidth);
    }

    private static void renderInfoBox(GuiGraphicsExtractor guiGraphics, Minecraft client) {
        boolean isShiftHeld = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                             GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        int boxWidth  = calculateBoxWidth(hoveredItemData, client, isShiftHeld);
        int boxHeight = calculateBoxHeight(hoveredItemData, isShiftHeld);

        double mouseX   = client.mouseHandler.xpos() * client.getWindow().getGuiScaledWidth() / client.getWindow().getWidth();
        double mouseY   = client.mouseHandler.ypos() * client.getWindow().getGuiScaledHeight() / client.getWindow().getHeight();
        int scaledW     = (int)(boxWidth  * infoBoxScale);
        int scaledH     = (int)(boxHeight * infoBoxScale);
        boolean isMouseOver = mouseX >= boxX && mouseX <= boxX + scaledW &&
                              mouseY >= boxY && mouseY <= boxY + scaledH;
        boolean isOnHandle  = mouseX >= boxX + scaledW - HANDLE_SIZE && mouseX <= boxX + scaledW &&
                              mouseY >= boxY + scaledH - HANDLE_SIZE && mouseY <= boxY + scaledH;

        // ── Apply pose: translate to box origin, then scale ─────────────────────
        var pose = guiGraphics.pose();
        pose.pushMatrix();
        pose.translate(boxX, boxY);
        pose.scale(infoBoxScale, infoBoxScale);

        // All coordinates below are relative to (0,0) = top-left corner of the box

        // Background
        guiGraphics.fill(0, 0, boxWidth, boxHeight, 0xFF000000);

        // Border
        int borderColor = getBorderColor(hoveredItemData);
        guiGraphics.fill(0,            0,             boxWidth,    2,            borderColor);
        guiGraphics.fill(0,            boxHeight - 2, boxWidth,    boxHeight,    borderColor);
        guiGraphics.fill(0,            0,             2,           boxHeight,    borderColor);
        guiGraphics.fill(boxWidth - 2, 0,             boxWidth,    boxHeight,    borderColor);

        // Corner resize handle – small "staircase" lines in the bottom-right corner
        int hs = HANDLE_SIZE;
        int hc = isOnHandle ? 0xFFFFFFFF : (isResizing ? 0xFFFFAA00 : 0xFF666666);
        guiGraphics.fill(boxWidth - hs,     boxHeight - 2,  boxWidth - 2, boxHeight - 1,  hc);
        guiGraphics.fill(boxWidth - hs + 4, boxHeight - 5,  boxWidth - 2, boxHeight - 4,  hc);
        guiGraphics.fill(boxWidth - hs + 8, boxHeight - 8,  boxWidth - 2, boxHeight - 7,  hc);
        guiGraphics.fill(boxWidth - 2,      boxHeight - hs, boxWidth - 1, boxHeight - 2,  hc);

        // Title
        String title = isOnHandle ? "§l§nSeymour §7[RESIZE]" :
                       (isShiftHeld && isMouseOver) ? "§l§nSeymour §7[DRAG]" : "§l§nSeymour Analysis";
        guiGraphics.text(client.font, Component.literal(title), 5, 5, 0xFFFFFFFF, true);

        // Piece information
        String pieceType = PieceTypeUtil.detectPieceType(hoveredItemData.itemName);
        String pieceTypeDisplay = pieceType != null
            ? pieceType.substring(0, 1).toUpperCase() + pieceType.substring(1)
            : "Unknown";
        guiGraphics.text(client.font, Component.literal("§7Piece: §f#" + hoveredItemData.itemHex + " - " + pieceTypeDisplay), 5, 18, 0xFFFFFFFF, true);

        int yOffset = 28;

        ClothConfig config = ClothConfig.getInstance();
        if (config.isWordsEnabled() && hoveredItemData.wordMatch != null) {
            guiGraphics.text(client.font, Component.literal("§d§l✦ WORD: " + hoveredItemData.wordMatch), 5, yOffset, 0xFFFFFFFF, true);
            yOffset += 10;
        }
        if (config.isPatternsEnabled() && hoveredItemData.specialPattern != null) {
            String patternName = getPatternDisplayName(hoveredItemData.specialPattern);
            guiGraphics.text(client.font, Component.literal("§5§l★ PATTERN: " + patternName), 5, yOffset, 0xFFFFFFFF, true);
            yOffset += 10;
        }

        if (isShiftHeld) {
            List<ColorAnalyzer.ColorMatch> top3 = hoveredItemData.analysisResult.top3Matches();
            int matchCount = top3.size();
            guiGraphics.text(client.font, Component.literal("§7§lTop " + matchCount + " Matches:"), 5, yOffset, 0xFFFFFFFF, true);

            double[] ownedDeltas = hoveredItemData.ownedBestDeltasForTop3;
            for (int i = 0; i < Math.min(10, matchCount); i++) {
                ColorAnalyzer.ColorMatch match = top3.get(i);
                int matchY = yOffset + 12 + (i * 25);
                String colorPrefix = getTierColorCode(match.tier(), match.isFade(), match.isCustom());
                String line1 = colorPrefix + (i + 1) + ". §f" + match.name() + " §7- #" + match.targetHex();
                String compSuffix = "";
                if (ownedDeltas != null && i < ownedDeltas.length) {
                    double ownedDelta = ownedDeltas[i];
                    boolean selfIsOwned = CollectionManager.getInstance().getCollection().containsKey(hoveredItemData.uuid);
                    if (ownedDelta < 0) {
                        if (selfIsOwned) compSuffix = " §7| §eBest!";
                    } else {
                        double diff = ownedDelta - match.deltaE();
                        if      (diff > 0.005 && selfIsOwned) compSuffix = " §7| §eBest!";
                        else if (diff > 0.005)                compSuffix = " §7| §a+" + String.format("%.2f", diff);
                        else if (diff < -0.005)               compSuffix = " §7| §c"  + String.format("%.2f", diff);
                        else                                  compSuffix = " §7| §7±0.00";
                    }
                }
                String line2 = "§7  ΔE: " + colorPrefix + String.format("%.5f", match.deltaE()) +
                               " §7| Abs: §f" + match.absoluteDistance() + compSuffix;
                guiGraphics.text(client.font, Component.literal(line1), 5, matchY,      0xFFFFFFFF, true);
                guiGraphics.text(client.font, Component.literal(line2), 5, matchY + 10, 0xFFFFFFFF, true);
            }
        } else {
            guiGraphics.text(client.font, Component.literal("§7Closest: §f" + hoveredItemData.bestMatchName), 5, yOffset,      0xFFFFFFFF, true);
            guiGraphics.text(client.font, Component.literal("§7Target: §7#"  + hoveredItemData.bestMatchHex),  5, yOffset + 10, 0xFFFFFFFF, true);
            String colorPrefix = getTierColorCode(hoveredItemData.tier, hoveredItemData.isFadeDye, hoveredItemData.isCustom);
            guiGraphics.text(client.font, Component.literal(colorPrefix + "ΔE: §f" + String.format("%.2f", hoveredItemData.deltaE)), 5, yOffset + 20, 0xFFFFFFFF, true);
            guiGraphics.text(client.font, Component.literal("§7Absolute: §f" + hoveredItemData.absoluteDist), 5, yOffset + 30, 0xFFFFFFFF, true);
            String tierText = getTierText(hoveredItemData.tier, hoveredItemData.isFadeDye, hoveredItemData.isCustom);
            guiGraphics.text(client.font, Component.literal(tierText), 5, yOffset + 40, 0xFFFFFFFF, true);
            yOffset += 50;
            if (hoveredItemData.isNeededForChecklist) {
                if (hoveredItemData.isOwned) {
                    String ownershipText = hoveredItemData.matchTier <= 1 ? "§a§l✓ Checklist" : "§e§l✓ Checklist";
                    guiGraphics.text(client.font, Component.literal(ownershipText), 5, yOffset, 0xFFFFFFFF, true);
                    yOffset += 10;
                } else {
                    guiGraphics.text(client.font, Component.literal("§c§l✗ NEEDED FOR CHECKLIST"), 5, yOffset, 0xFFFFFFFF, true);
                    yOffset += 10;
                }
            }
            if (config.isDupesEnabled() && hoveredItemData != null && hoveredItemData.dupeCount > 0) {
                guiGraphics.text(client.font, Component.literal("§c§l⚠ DUPE HEX §7(x" + hoveredItemData.dupeCount + ")"), 5, yOffset, 0xFFFFFFFF, true);
            }
        }

        pose.popMatrix();
    }


    private static int getBorderColor(HoveredItemData data) {
        if (data.isCustom) {
            return switch (data.tier) {
                case 0 -> 0xFF009600;
                case 1 -> 0xFF6B8E23;
                case 2 -> 0xFF9ACD32;
                default -> 0xFF808080;
            };
        } else if (data.isFadeDye) {
            return switch (data.tier) {
                case 0 -> 0xFF0000FF;
                case 1 -> 0xFF87CEFA;
                case 2 -> 0xFFFFFF00;
                default -> 0xFF808080;
            };
        } else {
            return switch (data.tier) {
                case 0 -> 0xFFFF0000;
                case 1 -> 0xFFFF69B4;
                case 2 -> 0xFFFFA500;
                default -> 0xFF808080;
            };
        }
    }

    private static String getTierColorCode(int tier, boolean isFade, boolean isCustom) {
        if (isCustom) {
            return switch (tier) {
                case 0 -> "§2";
                case 1 -> "§a";
                case 2 -> "§e";
                default -> "§7";
            };
        } else if (isFade) {
            return switch (tier) {
                case 0 -> "§9";
                case 1 -> "§b";
                case 2 -> "§e";
                default -> "§7";
            };
        } else {
            return switch (tier) {
                case 0 -> "§c";
                case 1 -> "§d";
                case 2 -> "§6";
                default -> "§7";
            };
        }
    }

    private static String getTierText(int tier, boolean isFade, boolean isCustom) {
        if (isCustom) {
            return switch (tier) {
                case 0, 1 -> "§2§l★ CUSTOM T1";
                case 2 -> "§a§l★ CUSTOM T2";
                default -> "§7§lT3+";
            };
        } else if (isFade) {
            return switch (tier) {
                case 0 -> "§9§lT1<";
                case 1 -> "§b§lT1";
                case 2 -> "§e§lT2";
                default -> "§7§lT3+";
            };
        } else {
            return switch (tier) {
                case 0 -> "§c§lT1<";
                case 1 -> "§d§lT1";
                case 2 -> "§6§lT2";
                default -> "§7§lT3+";
            };
        }
    }

    private static String getPatternDisplayName(String pattern) {
        if (pattern == null) return "";
        return switch (pattern.toLowerCase()) {
            case "paired" -> "PAIRED";
            case "repeating" -> "REPEATING";
            case "palindrome" -> "PALINDROME";
            default -> pattern.startsWith("axbxcx") ? "AxBxCx" : pattern.toUpperCase();
        };
    }
}

