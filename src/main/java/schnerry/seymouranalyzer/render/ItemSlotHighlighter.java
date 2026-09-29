package schnerry.seymouranalyzer.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import schnerry.seymouranalyzer.analyzer.ColorAnalyzer;
import schnerry.seymouranalyzer.analyzer.PatternDetector;
import schnerry.seymouranalyzer.config.ClothConfig;
import schnerry.seymouranalyzer.config.MatchPriority;
import schnerry.seymouranalyzer.data.ArmorPiece;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.scanner.ChestScanner;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Highlights armor pieces in inventory GUIs based on tier, custom colors, fade dyes, etc.
 * Ported from ChatTriggers index.js renderItemIntoGui event
 */
public class ItemSlotHighlighter {
    private static ItemSlotHighlighter instance;
    private final Set<String> searchHexes = new HashSet<>();
    private final ChestScanner scanner = new ChestScanner();

    // Cache analyzed item data to avoid re-processing every frame
    // WeakHashMap allows garbage collection of ItemStack keys when no longer referenced
    private final WeakHashMap<ItemStack, CachedItemData> itemCache = new WeakHashMap<>();

    private record CachedItemData(String hex, String uuid, Integer highlightColor) {
    }

    private ItemSlotHighlighter() {
        // Initialization - rendering is now done via mixin injection in HandledScreenMixin
    }

    public static ItemSlotHighlighter getInstance() {
        if (instance == null) {
            instance = new ItemSlotHighlighter();
        }
        return instance;
    }

    /**
     * Add a hex code to search for (highlights items in green)
     */
    public void addSearchHex(String hex) {
        searchHexes.add(hex.toUpperCase());
        // Clear cache when search changes since highlight colors will change
        itemCache.clear();
    }

    /**
     * Clear all search hex codes
     */
    public void clearSearchHexes() {
        searchHexes.clear();
        // Clear cache when search changes since highlight colors will change
        itemCache.clear();
    }

    /**
     * Get current search hexes
     */
    public Set<String> getSearchHexes() {
        return new HashSet<>(searchHexes);
    }

    /**
     * Clear the item cache (used when priorities or config changes)
     */
    public void clearCache() {
        itemCache.clear();
    }

    /**
     * Render highlight for a single slot (called by mixin)
     * This method is called during slot rendering, so it's in the correct coordinate space
     */
    public void renderSlotHighlight(GuiGraphicsExtractor guiGraphics, Slot slot) {
        ClothConfig config = ClothConfig.getInstance();
        if (!config.isHighlightsEnabled()) return;

        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;

        // Check if it's a Seymour armor piece (fast name check)
        String itemName = stack.getHoverName().getString();
        if (!ChestScanner.isSeymourArmor(itemName)) return;

        // Check cache first
        CachedItemData cachedData = itemCache.get(stack);

        if (cachedData == null) {
            // Not in cache - analyze and cache it
            String hex = scanner.extractHex(stack);
            if (hex == null) return;

            String uuid = scanner.getOrCreateItemUUID(stack);
            Integer highlightColor = getHighlightColor(stack, hex, itemName, uuid);

            // Cache for next frame
            cachedData = new CachedItemData(hex, uuid, highlightColor);
            itemCache.put(stack, cachedData);
        }

        // Use cached highlight color
        if (cachedData.highlightColor != null) {
            // Use slot.x and slot.y directly - we're already in the correct coordinate space
            int slotX = slot.x;
            int slotY = slot.y;

            drawSlotHighlight(guiGraphics, slotX, slotY, cachedData.highlightColor);
        }
    }

    /**
     * Determine highlight color based on item properties using the priority system
     * Returns null if no highlight should be drawn
     */
    private Integer getHighlightColor(ItemStack stack, String hex, String itemName, String uuid) {
        ClothConfig config = ClothConfig.getInstance();
        String hexUpper = hex.toUpperCase();

        // Collect all possible matches with their priorities
        java.util.Map<MatchPriority, Integer> possibleMatches = new java.util.HashMap<>();

        // Check dupe
        if (config.isDupesEnabled() && uuid != null && isDuplicateHex(hex, uuid)) {
            possibleMatches.put(MatchPriority.DUPE, config.getHighlightColor(MatchPriority.DUPE));
        }

        // Check search match
        if (!searchHexes.isEmpty() && searchHexes.contains(hexUpper)) {
            possibleMatches.put(MatchPriority.SEARCH, config.getHighlightColor(MatchPriority.SEARCH));
        }

        // Check word match
        if (config.isWordsEnabled()) {
            String wordMatch = PatternDetector.getInstance().detectWordMatch(hex);
            if (wordMatch != null) {
                possibleMatches.put(MatchPriority.WORD, config.getHighlightColor(MatchPriority.WORD));
            }
        }

        // Check pattern match
        if (config.isPatternsEnabled()) {
            String pattern = PatternDetector.getInstance().detectPattern(hex);
            if (pattern != null) {
                possibleMatches.put(MatchPriority.PATTERN, config.getHighlightColor(MatchPriority.PATTERN));
            }
        }

        // Check tier-based matches - check ALL top 3 matches, not just the best one
        // A piece can match multiple categories (e.g., T1 fade AND T2 normal)
        ColorAnalyzer.AnalysisResult analysis = ColorAnalyzer.getInstance().analyzeArmorColor(hex, itemName);
        if (analysis != null && analysis.top3Matches() != null) {
            for (ColorAnalyzer.ColorMatch match : analysis.top3Matches()) {
                int tier = calculateTier(match.deltaE(), match.isCustom(), match.isFade());

                // Skip T3+ matches (too far away)
                if (tier == 3) continue;

                if (match.isCustom()) {
                    switch (tier) {
                        case 0, 1 -> possibleMatches.putIfAbsent(MatchPriority.CUSTOM_T1, config.getHighlightColor(MatchPriority.CUSTOM_T1));
                        case 2    -> possibleMatches.putIfAbsent(MatchPriority.CUSTOM_T2, config.getHighlightColor(MatchPriority.CUSTOM_T2));
                    }
                } else if (match.isFade()) {
                    switch (tier) {
                        case 0 -> possibleMatches.putIfAbsent(MatchPriority.FADE_T0, config.getHighlightColor(MatchPriority.FADE_T0));
                        case 1 -> possibleMatches.putIfAbsent(MatchPriority.FADE_T1, config.getHighlightColor(MatchPriority.FADE_T1));
                        case 2 -> possibleMatches.putIfAbsent(MatchPriority.FADE_T2, config.getHighlightColor(MatchPriority.FADE_T2));
                    }
                } else {
                    switch (tier) {
                        case 0 -> possibleMatches.putIfAbsent(MatchPriority.NORMAL_T0, config.getHighlightColor(MatchPriority.NORMAL_T0));
                        case 1 -> possibleMatches.putIfAbsent(MatchPriority.NORMAL_T1, config.getHighlightColor(MatchPriority.NORMAL_T1));
                        case 2 -> possibleMatches.putIfAbsent(MatchPriority.NORMAL_T2, config.getHighlightColor(MatchPriority.NORMAL_T2));
                    }
                }
            }
        }

        // If no matches, return null
        if (possibleMatches.isEmpty()) {
            return null;
        }

        // Find the highest priority match based on user's priority order
        java.util.List<schnerry.seymouranalyzer.config.MatchPriority> priorities = config.getMatchPriorities();
        for (schnerry.seymouranalyzer.config.MatchPriority priority : priorities) {
            if (possibleMatches.containsKey(priority)) {
                return possibleMatches.get(priority);
            }
        }

        // Fallback: return any match
        return possibleMatches.values().iterator().next();
    }

    /**
     * Calculate tier for a match (copied from ColorAnalyzer to avoid recalculation)
     */
    private int calculateTier(double deltaE, boolean isCustom, boolean isFade) {
        if (isCustom) {
            if (deltaE <= 2) return 1;
            if (deltaE <= 5) return 2;
            return 3;
        }

        if (isFade) {
            if (deltaE <= 1) return 0;
            if (deltaE <= 2) return 1;
            if (deltaE <= 5) return 2;
            return 3;
        }

        // Normal colors
        if (deltaE <= 1) return 0;
        if (deltaE <= 2) return 1;
        if (deltaE <= 5) return 2;
        return 3;
    }

    /**
     * Check if a hex+uuid combination is a duplicate
     * An item is a DUPE only if:
     * - Another item in collection has the SAME hex
     * - But has a DIFFERENT uuid (it's a different item)
     */
    private boolean isDuplicateHex(String hex, String uuid) {
        Map<String, ArmorPiece> collection = CollectionManager.getInstance().getCollection();
        String hexUpper = hex.toUpperCase();

        for (Map.Entry<String, ArmorPiece> entry : collection.entrySet()) {
            String entryUuid = entry.getKey();
            ArmorPiece piece = entry.getValue();

            // Check if hex matches
            if (piece.getHexcode().toUpperCase().equals(hexUpper)) {
                // Only mark as dupe if UUID is DIFFERENT (different item, same color)
                if (!entryUuid.equals(uuid)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Draw a colored highlight overlay on a slot
     */
    private void drawSlotHighlight(GuiGraphicsExtractor guiGraphics, int x, int y, int color) {
        // Draw colored rectangle over the slot
        guiGraphics.fill(x, y, x + 16, y + 16, color);
    }
}

