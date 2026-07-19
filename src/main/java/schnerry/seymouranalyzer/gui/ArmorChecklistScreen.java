package schnerry.seymouranalyzer.gui;

import schnerry.seymouranalyzer.util.ChatUtils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Setter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.config.ClothConfig;
import schnerry.seymouranalyzer.data.ArmorPiece;
import schnerry.seymouranalyzer.data.ChecklistCache;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.util.ColorMath;
import schnerry.seymouranalyzer.util.PieceTypeUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Armor Checklist GUI - shows categorized armor sets and tracks completion
 * Ported from ChatTriggers ArmorChecklistGUI.js
 */
public class ArmorChecklistScreen extends ModScreen {
    private final Map<String, List<ChecklistEntry>> categories = new LinkedHashMap<>();
    private final List<String> normalPageOrder = new ArrayList<>();
    private final List<String> fadeDyePageOrder = new ArrayList<>();
    private List<String> pageOrder = new ArrayList<>();
    private int currentPage = 0;
    private int scrollOffset = 0;
    private static final int ROW_HEIGHT = 30;
    private static final int START_Y = 70;
    private static final int BOTTOM_MARGIN = 95; // Reserved px at bottom for page buttons + fixed buttons

    // Mode toggles
    private boolean fadeDyeMode = false;
    private boolean pieceToPieceMode = false;

    // Scrollbar dragging
    private boolean isDraggingScrollbar = false;

    // Context menu
    private ContextMenu contextMenu = null;

    // Flag to track when custom colors need reloading
    private static boolean customColorsNeedReload = false;

    // Remember position state (static = persists between opens)
    @Setter
    private static boolean rememberPage = false;
    private static int savedPage = 0;
    private static int savedChecklistScroll = 0;
    private static boolean savedFadeDyeMode = false;

    private static class ContextMenu {
        ArmorPiece piece; // For individual piece context menu
        String targetHex; // The target hex from the checklist entry
        List<ArmorPiece> allPieces; // For "Find all pieces" on row hex/name
        int x, y;
        int width = 140;
        boolean isRowMenu; // true if right-clicked on hex/name area, false if on piece slot
    }

    private static class ChecklistEntry {
        String hex;
        String name;
        List<String> pieces; // helmet, chestplate, leggings, boots

        // Completion tracking
        Map<String, ArmorPiece> foundPieces = new HashMap<>();
        Map<String, String> foundPieceUuids = new HashMap<>(); // pieceType -> UUID
    }

    public ArmorChecklistScreen(Screen parent) {
        super(Component.literal("Armor Set Checklist"), parent);
        loadChecklistData();

        // Check if cache needs invalidation (collection size changed)
        ChecklistCache cache = ChecklistCache.getInstance();
        Map<String, ArmorPiece> collection = CollectionManager.getInstance().getCollection();
        cache.checkAndInvalidate(collection.size());

        // Restore pinned page/mode before calculating matches
        if (rememberPage) {
            fadeDyeMode = savedFadeDyeMode;
            pageOrder = fadeDyeMode ? fadeDyePageOrder : normalPageOrder;
            // Clamp saved page to valid range
            currentPage = Math.clamp(savedPage, 0, pageOrder.size() - 1);
            scrollOffset = savedChecklistScroll;
        }

        calculateOptimalMatches();
    }

    /**
     * Call this method when custom colors are added or removed to reload the Custom category
     */
    public static void markCustomColorsForReload() {
        customColorsNeedReload = true;
    }

    private void loadChecklistData() {
        try {
            InputStream inputStream = SeymourAnalyzer.class.getResourceAsStream("/data/seymouranalyzer/checklistdata.json");

            if (inputStream == null) {
                SeymourAnalyzer.LOGGER.error("Could not load checklistdata.json");
                return;
            }

            Gson gson = new Gson();
            JsonObject root = gson.fromJson(new InputStreamReader(inputStream, StandardCharsets.UTF_8), JsonObject.class);

            // Load categories
            JsonObject categoriesJson = root.getAsJsonObject("categories");
            for (String categoryName : categoriesJson.keySet()) {
                List<ChecklistEntry> entries = new ArrayList<>();
                JsonArray array = categoriesJson.getAsJsonArray(categoryName);

                for (JsonElement element : array) {
                    JsonObject obj = element.getAsJsonObject();
                    ChecklistEntry entry = new ChecklistEntry();
                    entry.hex = obj.get("hex").getAsString().toUpperCase();
                    entry.name = obj.get("name").getAsString();
                    entry.pieces = new ArrayList<>();

                    JsonArray piecesArray = obj.getAsJsonArray("pieces");
                    for (JsonElement pieceElement : piecesArray) {
                        entry.pieces.add(pieceElement.getAsString());
                    }

                    entries.add(entry);
                }

                categories.put(categoryName, entries);
            }

            // Load page order
            JsonArray pageOrderArray = root.getAsJsonArray("normalPageOrder");
            for (JsonElement element : pageOrderArray) {
                String name = element.getAsString();
                if (categories.containsKey(name)) {
                    normalPageOrder.add(name);
                } else {
                    SeymourAnalyzer.LOGGER.warn("normalPageOrder references unknown category '{}', skipping", name);
                }
            }

            // Failsafe: append any categories not listed in normalPageOrder at the end
            // (excluding fade dye categories which are handled separately)
            String[] fadeDyeNames = {"Aurora", "Black Ice", "Black Opal", "Dusk Dye", "Forest Dye", "Frog", "Hellebore", "Jerry",
                                     "Kingfisher", "Lava", "Lucky", "Marine",
                                     "Oasis", "Ocean", "Pastel Sky", "Portal", "Red Tulip", "Rose",
                                     "Snowflake", "Spooky", "Sunflower", "Sunset", "Warden", "Charcoal", "Beach"};
            Set<String> fadeDyeSet = new HashSet<>(Arrays.asList(fadeDyeNames));
            for (String categoryName : categories.keySet()) {
                if (!normalPageOrder.contains(categoryName) && !fadeDyeSet.contains(categoryName)) {
                    SeymourAnalyzer.LOGGER.warn("Category '{}' not in normalPageOrder, appending at end", categoryName);
                    normalPageOrder.add(categoryName);
                }
            }

            // Load fade dyes from colors.json
            loadFadeDyes();

            // Load custom colors from config
            loadCustomColors();

            // Build fade dye page order from fade dye categories
            String[] fadeDyes = {"Aurora", "Black Ice", "Black Opal", "Dusk Dye", "Forest Dye", "Frog", "Hellebore", "Jerry",
                                "Kingfisher", "Lava", "Lucky", "Marine",
                                "Oasis", "Ocean", "Pastel Sky", "Portal", "Red Tulip", "Rose",
                                "Snowflake", "Spooky", "Sunflower", "Sunset", "Warden", "Charcoal", "Beach"};
            for (String fadeDye : fadeDyes) {
                if (categories.containsKey(fadeDye)) {
                    fadeDyePageOrder.add(fadeDye);
                }
            }

            // Start with normal page order
            pageOrder = normalPageOrder;

            SeymourAnalyzer.LOGGER.info("Loaded {} checklist categories (including {} fade dyes)",
                categories.size(), fadeDyePageOrder.size());

        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Error loading checklist data", e);
        }
    }

    private void loadFadeDyes() {
        try {
            InputStream inputStream = SeymourAnalyzer.class.getResourceAsStream("/data/seymouranalyzer/colors.json");

            if (inputStream == null) {
                SeymourAnalyzer.LOGGER.error("Could not load colors.json for fade dyes");
                return;
            }

            Gson gson = new Gson();
            JsonObject root = gson.fromJson(new InputStreamReader(inputStream, StandardCharsets.UTF_8), JsonObject.class);
            JsonObject fadeDyes = root.getAsJsonObject("FADE_DYES");

            if (fadeDyes == null) {
                SeymourAnalyzer.LOGGER.warn("No FADE_DYES section found in colors.json");
                return;
            }

            // Group stages by fade dye name
            Map<String, List<ChecklistEntry>> fadeDyeCategories = new LinkedHashMap<>();

            for (String key : fadeDyes.keySet()) {
                // Parse "Aurora - Stage 1" format
                String[] parts = key.split(" - Stage ");
                if (parts.length == 2) {
                    String dyeName = parts[0];
                    String hexValue = fadeDyes.get(key).getAsString().toUpperCase();

                    fadeDyeCategories.putIfAbsent(dyeName, new ArrayList<>());

                    ChecklistEntry entry = new ChecklistEntry();
                    entry.hex = hexValue;
                    entry.name = key;
                    entry.pieces = new ArrayList<>();
                    entry.pieces.add("helmet");
                    entry.pieces.add("chestplate");
                    entry.pieces.add("leggings");
                    entry.pieces.add("boots");

                    fadeDyeCategories.get(dyeName).add(entry);
                }
            }

            // Add all fade dye categories to main categories map
            categories.putAll(fadeDyeCategories);

            SeymourAnalyzer.LOGGER.info("Loaded {} fade dye categories with {} total stages",
                fadeDyeCategories.size(),
                fadeDyeCategories.values().stream().mapToInt(List::size).sum());

        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Error loading fade dyes", e);
        }
    }

    private void loadCustomColors() {
        try {
            ClothConfig config = ClothConfig.getInstance();
            Map<String, String> customColors = config.getCustomColors();

            // Remove existing Custom category if it exists (for reload support)
            categories.remove("Custom");
            normalPageOrder.remove("Custom");

            if (customColors.isEmpty()) {
                SeymourAnalyzer.LOGGER.info("No custom colors to load for checklist");
                return;
            }

            List<ChecklistEntry> customEntries = new ArrayList<>();

            for (Map.Entry<String, String> colorEntry : customColors.entrySet()) {
                String colorName = colorEntry.getKey();
                String hexValue = colorEntry.getValue().toUpperCase();

                ChecklistEntry entry = new ChecklistEntry();
                entry.hex = hexValue;
                entry.name = colorName;
                entry.pieces = new ArrayList<>();
                // Custom colors apply to all piece types
                entry.pieces.add("helmet");
                entry.pieces.add("chestplate");
                entry.pieces.add("leggings");
                entry.pieces.add("boots");

                customEntries.add(entry);
            }

            // Add Custom category to categories map
            categories.put("Custom", customEntries);

            // Add "Custom" to the normal page order (at the end, before "Other" if it exists, or just at the end)
            int otherIndex = normalPageOrder.indexOf("Other Armor");
            if (otherIndex != -1) {
                // Insert before "Other Armor"
                normalPageOrder.add(otherIndex, "Custom");
            } else {
                // Add at the end
                normalPageOrder.add("Custom");
            }

            SeymourAnalyzer.LOGGER.info("Loaded {} custom colors for checklist", customEntries.size());

        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Error loading custom colors for checklist", e);
        }
    }

    /**
     * Reload custom colors if they were modified
     */
    private void reloadCustomColorsIfNeeded() {
        if (customColorsNeedReload) {
            loadCustomColors();

            // Update page order
            pageOrder = fadeDyeMode ? fadeDyePageOrder : normalPageOrder;

            // Recalculate matches for the Custom category if we're viewing it
            if (!fadeDyeMode && pageOrder.contains("Custom")) {
                String currentCategory = currentPage < pageOrder.size() ? pageOrder.get(currentPage) : null;
                if ("Custom".equals(currentCategory)) {
                    calculateOptimalMatches();
                }
            }

            customColorsNeedReload = false;
            SeymourAnalyzer.LOGGER.info("Reloaded custom colors in checklist GUI");
        }
    }

    private void calculateOptimalMatches() {
        Map<String, ArmorPiece> collection = CollectionManager.getInstance().getCollection();
        ChecklistCache cache = ChecklistCache.getInstance();

        if (pageOrder.isEmpty() || currentPage >= pageOrder.size()) return;

        String currentCategory = pageOrder.get(currentPage);
        List<ChecklistEntry> entries = categories.get(currentCategory);
        if (entries == null) return;

        // Check if we have cached data for this category
        ChecklistCache.CategoryCache categoryCache;
        if (fadeDyeMode) {
            categoryCache = cache.getFadeDyeOptimalCache(currentCategory);
        } else {
            categoryCache = cache.getNormalColorCache(currentCategory);
        }

        // If we have valid cached data, use it
        if (categoryCache != null && categoryCache.matchesByIndex != null) {
            // Validate that cached hex values match current entries
            boolean cacheValid = true;
            for (int i = 0; i < entries.size(); i++) {
                ChecklistEntry entry = entries.get(i);
                ChecklistCache.StageMatches stageMatches = categoryCache.matchesByIndex.get(i);
                if (stageMatches != null && stageMatches.stageHex != null) {
                    // Compare hex values (case-insensitive)
                    if (!stageMatches.stageHex.equalsIgnoreCase(entry.hex)) {
                        SeymourAnalyzer.LOGGER.info("Cache invalidated: hex mismatch for stage {}, cached={}, current={}",
                            i, stageMatches.stageHex, entry.hex);
                        cacheValid = false;
                        break;
                    }
                }
            }

            if (cacheValid) {
                SeymourAnalyzer.LOGGER.info("Using cached matches for category: {}", currentCategory);

                // Restore matches from cache
                for (int i = 0; i < entries.size(); i++) {
                    ChecklistEntry entry = entries.get(i);
                    entry.foundPieces.clear();
                    entry.foundPieceUuids.clear();

                    ChecklistCache.StageMatches stageMatches = categoryCache.matchesByIndex.get(i);
                    if (stageMatches != null && stageMatches.calculated) {
                    // Restore each piece match
                    if (stageMatches.helmet != null) {
                        ArmorPiece piece = collection.get(stageMatches.helmet.uuid);
                        if (piece != null) {
                            entry.foundPieces.put("helmet", piece);
                            entry.foundPieceUuids.put("helmet", stageMatches.helmet.uuid);
                        }
                    }
                    if (stageMatches.chestplate != null) {
                        ArmorPiece piece = collection.get(stageMatches.chestplate.uuid);
                        if (piece != null) {
                            entry.foundPieces.put("chestplate", piece);
                            entry.foundPieceUuids.put("chestplate", stageMatches.chestplate.uuid);
                        }
                    }
                    if (stageMatches.leggings != null) {
                        ArmorPiece piece = collection.get(stageMatches.leggings.uuid);
                        if (piece != null) {
                            entry.foundPieces.put("leggings", piece);
                            entry.foundPieceUuids.put("leggings", stageMatches.leggings.uuid);
                        }
                    }
                    if (stageMatches.boots != null) {
                        ArmorPiece piece = collection.get(stageMatches.boots.uuid);
                        if (piece != null) {
                            entry.foundPieces.put("boots", piece);
                            entry.foundPieceUuids.put("boots", stageMatches.boots.uuid);
                        }
                    }
                }
            }

                return; // Cache hit, no need to recalculate
            } else {
                // Cache invalid, clear it for this category
                if (fadeDyeMode) {
                    cache.getFadeDyeOptimalCache().remove(currentCategory);
                } else {
                    cache.getNormalColorCache().remove(currentCategory);
                }
                SeymourAnalyzer.LOGGER.info("Cache cleared for category {} due to hex value changes", currentCategory);
            }
        }

        // No cache - calculate optimal matches
        SeymourAnalyzer.LOGGER.info("Calculating optimal matches for category: {}", currentCategory);

        // Create new category cache
        categoryCache = new ChecklistCache.CategoryCache();
        categoryCache.category = currentCategory;
        categoryCache.isCalculating = true;

        // For each piece type, build a list of all candidates across all stages

        for (String pieceType : PieceTypeUtil.pieceTypes) {
            List<CandidateMatch> candidates = new ArrayList<>();

            // Build candidate list for ALL entries (not just those that need this piece type)
            for (int stageIdx = 0; stageIdx < entries.size(); stageIdx++) {
                ChecklistEntry entry = entries.get(stageIdx);

                // Find all matching pieces for this stage and piece type
                for (Map.Entry<String, ArmorPiece> collectionEntry : collection.entrySet()) {
                    String uuid = collectionEntry.getKey();
                    ArmorPiece piece = collectionEntry.getValue();
                    String pieceName = piece.getPieceName().toLowerCase();
                    boolean typeMatches = false;

                    if (pieceType.equals("helmet") && (pieceName.contains("helm") || pieceName.contains("hat") || pieceName.contains("hood") || pieceName.contains("crown"))) {
                        typeMatches = true;
                    } else if (pieceType.equals("chestplate") && (pieceName.contains("chest") || pieceName.contains("tunic") || pieceName.contains("jacket"))) {
                        typeMatches = true;
                    } else if (pieceType.equals("leggings") && (pieceName.contains("legging") || pieceName.contains("pants") || pieceName.contains("trousers"))) {
                        typeMatches = true;
                    } else if (pieceType.equals("boots") && (pieceName.contains("boot") || pieceName.contains("shoes") || pieceName.contains("sneakers"))) {
                        typeMatches = true;
                    }

                    if (typeMatches) {
                        double deltaE = ColorMath.calculateDeltaE(entry.hex, piece.getHexcode());
                        if (deltaE <= 5.0) {
                            // Mark if this piece is actually needed for this entry
                            boolean isNeeded = entry.pieces.contains(pieceType);
                            candidates.add(new CandidateMatch(stageIdx, uuid, piece, deltaE, isNeeded));
                        }
                    }
                }
            }

            // Sort candidates: first by priority (needed pieces first), then by deltaE
            candidates.sort((a, b) -> {
                if (a.isNeeded != b.isNeeded) {
                    return a.isNeeded ? -1 : 1; // Needed pieces first
                }
                return Double.compare(a.deltaE, b.deltaE); // Then by quality
            });

            // Greedy assignment: assign each piece to the best available stage
            Set<String> usedPieces = new HashSet<>();
            Set<Integer> assignedStages = new HashSet<>();

            for (CandidateMatch candidate : candidates) {
                String uuid = candidate.uuid;

                if (!usedPieces.contains(uuid) && !assignedStages.contains(candidate.stageIndex)) {
                    // Assign this piece to this stage
                    ChecklistEntry targetEntry = entries.get(candidate.stageIndex);
                    targetEntry.foundPieces.put(pieceType, candidate.piece);
                    targetEntry.foundPieceUuids.put(pieceType, uuid);
                    usedPieces.add(uuid);
                    assignedStages.add(candidate.stageIndex);
                }
            }
        }

        // Save to cache
        for (int i = 0; i < entries.size(); i++) {
            ChecklistEntry entry = entries.get(i);
            ChecklistCache.StageMatches stageMatches = new ChecklistCache.StageMatches();
            stageMatches.stageHex = entry.hex;
            stageMatches.calculated = true;

            ArmorPiece helmet = entry.foundPieces.get("helmet");
            if (helmet != null) {
                String uuid = entry.foundPieceUuids.get("helmet");
                stageMatches.helmet = new ChecklistCache.MatchInfo(
                    helmet.getPieceName(),
                    helmet.getHexcode(),
                    ColorMath.calculateDeltaE(entry.hex, helmet.getHexcode()),
                    uuid
                );
            }

            ArmorPiece chestplate = entry.foundPieces.get("chestplate");
            if (chestplate != null) {
                String uuid = entry.foundPieceUuids.get("chestplate");
                stageMatches.chestplate = new ChecklistCache.MatchInfo(
                    chestplate.getPieceName(),
                    chestplate.getHexcode(),
                    ColorMath.calculateDeltaE(entry.hex, chestplate.getHexcode()),
                    uuid
                );
            }

            ArmorPiece leggings = entry.foundPieces.get("leggings");
            if (leggings != null) {
                String uuid = entry.foundPieceUuids.get("leggings");
                stageMatches.leggings = new ChecklistCache.MatchInfo(
                    leggings.getPieceName(),
                    leggings.getHexcode(),
                    ColorMath.calculateDeltaE(entry.hex, leggings.getHexcode()),
                    uuid
                );
            }

            ArmorPiece boots = entry.foundPieces.get("boots");
            if (boots != null) {
                String uuid = entry.foundPieceUuids.get("boots");
                stageMatches.boots = new ChecklistCache.MatchInfo(
                    boots.getPieceName(),
                    boots.getHexcode(),
                    ColorMath.calculateDeltaE(entry.hex, boots.getHexcode()),
                    uuid
                );
            }

            categoryCache.matchesByIndex.put(i, stageMatches);
        }

        categoryCache.isCalculating = false;

        // Store in cache system
        if (fadeDyeMode) {
            cache.setFadeDyeOptimalCache(currentCategory, categoryCache);
        } else {
            cache.setNormalColorCache(currentCategory, categoryCache);
        }

        // Save to disk
        cache.save();

        SeymourAnalyzer.LOGGER.info("Cached optimal matches for category: {}", currentCategory);
    }

    // Helper class for optimal matching
    private static class CandidateMatch {
        int stageIndex;
        String uuid;
        ArmorPiece piece;
        double deltaE;
        boolean isNeeded; // True if this piece type is actually needed for this stage

        CandidateMatch(int stageIndex, String uuid, ArmorPiece piece, double deltaE, boolean isNeeded) {
            this.stageIndex = stageIndex;
            this.uuid = uuid;
            this.piece = piece;
            this.deltaE = deltaE;
            this.isNeeded = isNeeded;
        }
    }

    @Override
    protected void init() {
        super.init();

        // Back to database button
        Button backBtn = Button.builder(Component.literal("← Back to Database"),
            button -> this.minecraft.setScreen(parent))
            .bounds(20, 10, 150, 20).build();
        this.addRenderableWidget(backBtn);

        // Remember page toggle (right of back button)
        Button rememberBtn = Button.builder(
            Component.literal(rememberPage ? "§aPinned: ON" : "§7Pinned: OFF"),
            button -> {
                rememberPage = !rememberPage;
                if (rememberPage) {
                    savedPage = currentPage;
                    savedChecklistScroll = scrollOffset;
                    savedFadeDyeMode = fadeDyeMode;
                }
                this.rebuildWidgets();
            })
            .bounds(175, 10, 100, 20).build();
        this.addRenderableWidget(rememberBtn);

        // Piece filter toggle button (only shown in normal mode)
        if (!fadeDyeMode) {
            String filterLabel = pieceToPieceMode ? "§aPiece Filter: §aON" : "Piece Filter: §7OFF";
            Button filterBtn = Button.builder(Component.literal(filterLabel),
                button -> {
                    pieceToPieceMode = !pieceToPieceMode;
                    calculateOptimalMatches();
                    this.rebuildWidgets();
                })
                .bounds(this.width - 200, 10, 180, 20).build();
            this.addRenderableWidget(filterBtn);
        }

        // Fade Dye mode toggle button (bottom right, above page buttons)
        String modeLabel = fadeDyeMode ? "§dMode: §dFade Dyes" : "Mode: §9Normal";
        Button fadeDyeBtn = Button.builder(Component.literal(modeLabel),
            button -> {
                fadeDyeMode = !fadeDyeMode;
                pageOrder = fadeDyeMode ? fadeDyePageOrder : normalPageOrder;
                currentPage = 0;
                scrollOffset = 0;
                calculateOptimalMatches();
                this.rebuildWidgets();
            })
            .bounds(this.width - 140, this.height - 110, 120, 20).build();
        this.addRenderableWidget(fadeDyeBtn);

        // Copy Missing button (above mode toggle)
        Button copyMissingBtn = Button.builder(Component.literal("§eCopy Missing"),
            button -> copyMissingToClipboard())
            .bounds(this.width - 140, this.height - 135, 120, 20).build();
        this.addRenderableWidget(copyMissingBtn);

        // Category page buttons (bottom two rows)
        initPageButtons();
    }

    private void initPageButtons() {
        int buttonWidth = 80;
        int buttonHeight = 16;
        int buttonSpacing = 7;
        int buttonsPerRow = 9;
        int rowStep = buttonHeight + 4;  // 20 px between row tops
        int bottomAnchor = 30;           // px from bottom of screen to bottom row

        int totalPages = pageOrder.size();
        int numRows = (int) Math.ceil((double) totalPages / buttonsPerRow);

        // Rows grow upward from the bottom of the screen
        for (int row = 0; row < numRows; row++) {
            int rowStart = row * buttonsPerRow;
            int rowCount = Math.min(buttonsPerRow, totalPages - rowStart);
            int rowY = this.height - bottomAnchor - (numRows - 1 - row) * rowStep;

            int totalWidth = rowCount * (buttonWidth + buttonSpacing) - buttonSpacing;
            int startX = (this.width - totalWidth) / 2;

            for (int i = 0; i < rowCount; i++) {
                int pageIndex = rowStart + i;
                String categoryName = pageOrder.get(pageIndex);
                String shortName = getShortenedName(categoryName);
                int x = startX + i * (buttonWidth + buttonSpacing);

                Button btn = Button.builder(Component.literal(shortName),
                    button -> {
                        currentPage = pageIndex;
                        scrollOffset = 0;
                        calculateOptimalMatches();
                    })
                    .bounds(x, rowY, buttonWidth, buttonHeight).build();
                this.addRenderableWidget(btn);
            }
        }
    }

    private String getShortenedName(String name) {
        return switch (name) {
            case "Pure Colors" -> "Pure";
            case "Exo Pure Dyes" -> "Exo Pure";
            case "1.12+ Pure Dyes" -> "1.12+ Pure";
            case "Other In-Game Dyes" -> "Dyes";
            case "Great Spook" -> "G.Spook";
            case "Ghostly Boots" -> "G.Boots";
            case "White-Black" -> "B-White";
            case "Slayer/Mytho" -> "Slayer";

            // Fade dye abbreviations
            case "Black Ice" -> "BIce";
            case "Black Opal" -> "BOpal";
            case "Dusk Dye" -> "Dusk";
            case "Forest Dye" -> "Forest";
            case "Hellebore" -> "Helle";
            case "Kingfisher" -> "Kingf";
            case "Pastel Sky" -> "PSky";
            case "Red Tulip" -> "RTulip";
            case "Snowflake" -> "Snowf";
            case "Sunflower" -> "Sunf";
            default -> name;
        };
    }

    @Override
    public void renderContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        // Check if custom colors need reloading (e.g., from /seymour add command)
        reloadCustomColorsIfNeeded();

        // Title
        String titleStr = "§l§nArmor Set Checklist";
        int titleWidth = this.font.width(titleStr);
        guiGraphics.text(this.font, titleStr, this.width / 2 - titleWidth / 2, 10, 0xFFFFFFFF);

        if (pageOrder.isEmpty() || currentPage >= pageOrder.size()) {
            guiGraphics.text(this.font, "No checklist data loaded!", this.width / 2 - 70, this.height / 2, 0xFFFF5555);
            super.renderContent(guiGraphics, mouseX, mouseY, delta);
            return;
        }

        // Current page info
        String currentCategory = pageOrder.get(currentPage);
        String pageInfo = "§7Page " + (currentPage + 1) + "/" + pageOrder.size() + " - §e" + currentCategory;
        int pageInfoWidth = this.font.width(pageInfo);
        guiGraphics.text(this.font, pageInfo, this.width / 2 - pageInfoWidth / 2, 30, 0xFFFFFFFF);

        // Draw checklist entries
        List<ChecklistEntry> entries = categories.get(currentCategory);
        if (entries != null) {
            drawChecklist(guiGraphics, entries);
            drawStatsCounter(guiGraphics, entries);
        }

        // Draw context menu on top if open
        if (contextMenu != null) {
            drawContextMenu(guiGraphics);
        }

        // Footer
        guiGraphics.text(this.font, "§7Press §eESC §7to close | Click pages below to switch", this.width / 2 - 120, this.height - 10, 0xFFFFFFFF);

        super.renderContent(guiGraphics, mouseX, mouseY, delta);
    }

    private void drawContextMenu(GuiGraphicsExtractor guiGraphics) {
        int x = contextMenu.x;
        int y = contextMenu.y;
        int w = contextMenu.width;
        int optionHeight = 20;
        int h = optionHeight * 2; // 2 options

        // Background
        guiGraphics.fill(x, y, x + w, y + h, 0xF0282828);

        // Border
        guiGraphics.fill(x, y, x + w, y + 2, 0xFF646464);
        guiGraphics.fill(x, y + h - 2, x + w, y + h, 0xFF646464);
        guiGraphics.fill(x, y, x + 2, y + h, 0xFF646464);
        guiGraphics.fill(x + w - 2, y, x + w, y + h, 0xFF646464);

        if (contextMenu.isRowMenu) {
            // Row menu: "Find all pieces" and "Find in Database"
            guiGraphics.text(this.font, "Find all pieces", x + 5, y + 6, 0xFFFFFFFF);
            guiGraphics.text(this.font, "Find in Database", x + 5, y + 26, 0xFFFFFFFF);
        } else {
            // Piece menu: "Find Piece" and "Find in Database"
            guiGraphics.text(this.font, "Find Piece", x + 5, y + 6, 0xFFFFFFFF);
            guiGraphics.text(this.font, "Find in Database", x + 5, y + 26, 0xFFFFFFFF);
        }
    }

    private void drawStatsCounter(GuiGraphicsExtractor guiGraphics, List<ChecklistEntry> entries) {
        int boxWidth = 180;
        int boxHeight = 40;
        int boxX = this.width - boxWidth - 20;
        int boxY = 35;

        // Background
        guiGraphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xC8282828);

        // Border
        guiGraphics.fill(boxX, boxY, boxX + boxWidth, boxY + 2, 0xFF646464);
        guiGraphics.fill(boxX, boxY + boxHeight - 2, boxX + boxWidth, boxY + boxHeight, 0xFF646464);
        guiGraphics.fill(boxX, boxY, boxX + 2, boxY + boxHeight, 0xFF646464);
        guiGraphics.fill(boxX + boxWidth - 2, boxY, boxX + boxWidth, boxY + boxHeight, 0xFF646464);

        // Calculate stats
        int t1Count = 0, t2Count = 0, missingCount = 0, totalSlots = 0;

        for (ChecklistEntry entry : entries) {
            for (String pieceType : PieceTypeUtil.pieceTypes) {
                // Skip if piece-to-piece filter is on and piece not needed
                if (pieceToPieceMode && !entry.pieces.contains(pieceType)) {
                    continue;
                }

                totalSlots++;
                ArmorPiece match = entry.foundPieces.get(pieceType);

                if (match == null) {
                    missingCount++;
                } else {
                    double deltaE = ColorMath.calculateDeltaE(entry.hex, match.getHexcode());
                    if (deltaE <= 2) {
                        t1Count++;
                    } else if (deltaE <= 5) {
                        t2Count++;
                    }
                }
            }
        }

        int filledCount = t1Count + t2Count;
        String percentStr = totalSlots > 0 ? String.format("%.1f", (filledCount * 100.0 / totalSlots)) : "0.0";

        // Calculate T1 percentage for color coding
        double t1Percent = totalSlots > 0 ? (t1Count * 100.0 / totalSlots) : 0;
        String t1Color = t1Percent >= 50 ? "§a" : (t1Percent >= 35 ? "§e" : "§c");
        String t1PercentStr = totalSlots > 0 ? String.format("%.1f", t1Percent) : "0.0";
        String t2PercentStr = totalSlots > 0 ? String.format("%.1f", (t2Count * 100.0 / totalSlots)) : "0.0";

        // Line 1: T1 and T2 with percentages
        String line1 = "§7T1: §c" + t1Count + " §7(" + t1Color + t1PercentStr + "%§7) | T2: §6" + t2Count + " §7(§f" + t2PercentStr + "%§7)";
        int line1Width = this.font.width(line1);
        int line1X = boxX + (boxWidth - line1Width) / 2;
        guiGraphics.text(this.font, line1, line1X, boxY + 6, 0xFFFFFFFF);

        // Line 2: Missing and total filled
        String line2 = "§7Missing: §c" + missingCount + " §7| §e" + filledCount + "/" + totalSlots + " §7(§f" + percentStr + "%§7)";
        int line2Width = this.font.width(line2);
        int line2X = boxX + (boxWidth - line2Width) / 2;
        guiGraphics.text(this.font, line2, line2X, boxY + 22, 0xFFFFFFFF);
    }

    private void drawChecklist(GuiGraphicsExtractor guiGraphics, List<ChecklistEntry> entries) {
        // Draw headers
        guiGraphics.text(this.font, "§l§7Target Color", 80, START_Y - 15, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§l§7Helmet", 250, START_Y - 15, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§l§7Chestplate", 370, START_Y - 15, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§l§7Leggings", 490, START_Y - 15, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§l§7Boots", 610, START_Y - 15, 0xFFFFFFFF);

        int availableHeight = this.height - START_Y - BOTTOM_MARGIN;
        int maxVisible = Math.max(1, availableHeight / ROW_HEIGHT);

        int endIndex = Math.min(scrollOffset + maxVisible, entries.size());

        for (int i = scrollOffset; i < endIndex; i++) {
            ChecklistEntry entry = entries.get(i);
            int y = START_Y + ((i - scrollOffset) * ROW_HEIGHT);
            drawChecklistRow(guiGraphics, entry, y);
        }

        // Scroll indicator
        if (entries.size() > maxVisible) {
            String scrollText = "§7(" + (scrollOffset + 1) + "-" + endIndex + " of " + entries.size() + ") §eScroll for more";
            guiGraphics.text(this.font, scrollText, 20, START_Y + (maxVisible * ROW_HEIGHT) + 5, 0xFFFFFFFF);

            // Draw scrollbar
            int scrollbarX = this.width - 15;
            int scrollbarY = START_Y;
            int scrollbarHeight = maxVisible * ROW_HEIGHT;
            ScrollbarRenderer.renderVerticalScrollbar(guiGraphics, scrollbarX, scrollbarY, scrollbarHeight,
                scrollOffset, entries.size(), maxVisible);
        }
    }

    private void drawChecklistRow(GuiGraphicsExtractor guiGraphics, ChecklistEntry entry, int y) {
        // Draw hex color box (50px wide to fit hex code)
        ColorMath.RGB rgb = ColorMath.hexToRgb(entry.hex);
        int color = 0xFF000000 | (rgb.r() << 16) | (rgb.g() << 8) | rgb.b();
        guiGraphics.fill(20, y, 70, y + 20, color);

        // Draw hex text on the color box
        boolean isDark = ColorMath.isColorDark(entry.hex);
        int textColor = isDark ? 0xFFFFFFFF : 0xFF000000;
        String hexText = "#" + entry.hex;
        guiGraphics.text(this.font, hexText, 22, y + 6, textColor);

        // Draw armor set name
        String displayName = entry.name;
        if (displayName.length() > 25) {
            displayName = displayName.substring(0, 25) + "...";
        }
        guiGraphics.text(this.font, displayName, 80, y + 6, 0xFFFFFFFF);

        // Draw piece match boxes (helmet, chestplate, leggings, boots)
        int[] xPositions = {250, 370, 490, 610};

        for (int i = 0; i < PieceTypeUtil.pieceTypes.length; i++) {
            String pieceType = PieceTypeUtil.pieceTypes[i];
            int boxX = xPositions[i];

            // Check if this piece is required for this armor set
            boolean isRequired = entry.pieces.contains(pieceType);

            // Only hide non-required pieces when piece filter is enabled
            if (!isRequired && pieceToPieceMode) {
                // Gray out - not needed for this set (only when filter is ON)
                guiGraphics.fill(boxX, y, boxX + 100, y + 20, 0xB0606060);
                guiGraphics.text(this.font, "§8-", boxX + 45, y + 6, 0xFF666666);
                continue;
            }

            // Check if we have a match
            ArmorPiece match = entry.foundPieces.get(pieceType);

            if (match == null) {
                // No match found - RED
                guiGraphics.fill(boxX, y, boxX + 100, y + 20, 0xFFC80000);
                guiGraphics.text(this.font, "§c✗ Missing", boxX + 5, y + 6, 0xFFFFFFFF);
            } else {
                // Match found - show with quality color
                double deltaE = ColorMath.calculateDeltaE(entry.hex, match.getHexcode());

                int qualityColor;
                if (deltaE == 0) {
                    qualityColor = 0xFF800080; // Purple for exact match
                } else if (deltaE <= 2) {
                    qualityColor = 0xFF00C800; // Green for great match
                } else {
                    qualityColor = 0xFFC8C800; // Yellow for good match
                }

                guiGraphics.fill(boxX, y, boxX + 100, y + 20, qualityColor);

                // Show piece name and delta to row hex
                String pieceHexAndDelta = match.getHexcode().concat(" - ").concat(String.format("ΔE: %.2f", deltaE));
                guiGraphics.text(this.font, pieceHexAndDelta, boxX + 3, y + 6, 0xFFFFFFFF);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean isOutOfBounds) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        // Check context menu clicks first
        if (contextMenu != null) {
            if (handleContextMenuClick(mouseX, mouseY, button)) {
                return true;
            }
        }

        // Check scrollbar click (left click only)
        if (button == 0) {
            if (pageOrder.isEmpty() || currentPage >= pageOrder.size()) {
                // Continue to other handlers
            } else {
                String currentCategory = pageOrder.get(currentPage);
                List<ChecklistEntry> entries = categories.get(currentCategory);

                if (entries != null) {
                    int availableHeight = this.height - START_Y - BOTTOM_MARGIN;
                    int maxVisible = Math.max(1, availableHeight / ROW_HEIGHT);

                    if (entries.size() > maxVisible) {
                        int scrollbarX = this.width - 15;
                        int scrollbarY = START_Y;
                        int scrollbarHeight = maxVisible * ROW_HEIGHT;

                        if (ScrollbarRenderer.isMouseOverScrollbar(mouseX, mouseY, scrollbarX, scrollbarY, scrollbarHeight)) {
                            isDraggingScrollbar = true;
                            // Calculate scroll position from click
                            int maxScroll = Math.max(0, entries.size() - maxVisible);
                            scrollOffset = ScrollbarRenderer.calculateScrollFromDrag(mouseY, scrollbarY, scrollbarHeight,
                                entries.size(), maxVisible);
                            scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
                            return true;
                        }
                    }
                }
            }
        }

        // Right click - open context menu on piece slots
        if (button == 1) {
            if (handleRightClick(mouseX, mouseY)) {
                return true;
            }
        }

        // Left click closes context menu if clicking elsewhere
        if (button == 0 && contextMenu != null) {
            contextMenu = null;
            return true;
        }

        return super.mouseClicked(click, isOutOfBounds);
    }

    private boolean handleRightClick(double mouseX, double mouseY) {
        if (pageOrder.isEmpty() || currentPage >= pageOrder.size()) return false;

        String currentCategory = pageOrder.get(currentPage);
        List<ChecklistEntry> entries = categories.get(currentCategory);
        if (entries == null) return false;

        int availableHeight = this.height - START_Y - BOTTOM_MARGIN;
        int maxVisible = Math.max(1, availableHeight / ROW_HEIGHT);
        int visibleCount = Math.min(maxVisible, entries.size() - scrollOffset);

        // Calculate which row was clicked
        int relativeRow = (int) Math.floor((mouseY - START_Y) / ROW_HEIGHT);
        if (relativeRow < 0 || relativeRow >= visibleCount) {
            return false;
        }

        int entryIndex = scrollOffset + relativeRow;
        if (entryIndex < 0 || entryIndex >= entries.size()) return false;

        ChecklistEntry entry = entries.get(entryIndex);

        // Check if clicked on hex/name area (left side of the row)
        if (mouseX >= 10 && mouseX <= 230) {
            // Right-clicked on hex/name area - show "Find all pieces" menu
            List<ArmorPiece> allPieces = new ArrayList<>();

            // Collect all pieces that exist in this row
            for (String pieceType : PieceTypeUtil.pieceTypes) {
                ArmorPiece piece = entry.foundPieces.get(pieceType);
                if (piece != null) {
                    allPieces.add(piece);
                }
            }

            if (allPieces.isEmpty()) {
                if (minecraft != null && minecraft.player != null) {
                    ChatUtils.sendSystemMessage(minecraft.player, Component.literal("§c[Armor Checklist] No pieces found for this entry!"));
                }
                return false;
            }

            // Show row context menu
            contextMenu = new ContextMenu();
            contextMenu.allPieces = allPieces;
            contextMenu.targetHex = entry.hex;
            contextMenu.x = (int) mouseX;
            contextMenu.y = (int) mouseY;
            contextMenu.isRowMenu = true;

            return true;
        }

        // Check which piece column was clicked
        String clickedPieceType = null;
        if (mouseX >= 250 && mouseX <= 350) {
            clickedPieceType = "helmet";
        } else if (mouseX >= 370 && mouseX <= 470) {
            clickedPieceType = "chestplate";
        } else if (mouseX >= 490 && mouseX <= 590) {
            clickedPieceType = "leggings";
        } else if (mouseX >= 610 && mouseX <= 710) {
            clickedPieceType = "boots";
        }

        if (clickedPieceType == null) return false;

        // Check if we have a match for this piece
        ArmorPiece match = entry.foundPieces.get(clickedPieceType);
        if (match == null) {
            if (minecraft != null && minecraft.player != null) {
                ChatUtils.sendSystemMessage(minecraft.player, Component.literal("§c[Armor Checklist] No piece found for this slot!"));
            }
            return false;
        }

        // Show piece context menu
        contextMenu = new ContextMenu();
        contextMenu.piece = match;
        contextMenu.targetHex = entry.hex;
        contextMenu.x = (int) mouseX;
        contextMenu.y = (int) mouseY;
        contextMenu.isRowMenu = false;

        return true;
    }

    private boolean handleContextMenuClick(double mouseX, double mouseY, int button) {
        if (contextMenu == null || button != 0) return false;

        int x = contextMenu.x;
        int y = contextMenu.y;
        int w = contextMenu.width;
        int optionHeight = 20;
        int h = optionHeight * 2;

        // Check if click is outside menu
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
            contextMenu = null;
            return false;
        }

        // Check which option was clicked
        if (mouseY >= y && mouseY < y + optionHeight) {
            // Option 1: "Find Piece" or "Find all pieces"
            if (contextMenu.isRowMenu) {
                // Find all pieces in this row
                if (minecraft != null && minecraft.player != null && contextMenu.allPieces != null && !contextMenu.allPieces.isEmpty()) {
                    // Build hex list from all pieces
                    List<String> hexList = new ArrayList<>();
                    for (ArmorPiece piece : contextMenu.allPieces) {
                        if (!hexList.contains(piece.getHexcode())) {
                            hexList.add(piece.getHexcode());
                        }
                    }

                    String hexString = String.join(" ", hexList);
                    ChatUtils.sendSystemMessage(minecraft.player, Component.literal("§a[Armor Checklist] Searching for " + contextMenu.allPieces.size() + " piece(s)..."));
                    // Execute search command with all hexes
                    minecraft.player.connection.sendCommand("seymour search " + hexString);
                }
            } else {
                // Find single piece
                if (minecraft != null && minecraft.player != null && contextMenu.piece != null) {
                    ChatUtils.sendSystemMessage(minecraft.player, Component.literal("§a[Armor Checklist] Searching for pieces with hex " + contextMenu.piece.getHexcode() + "..."));
                    // Execute search command
                    minecraft.player.connection.sendCommand("seymour search " + contextMenu.piece.getHexcode());
                }
            }
            contextMenu = null;
            return true;
        } else if (mouseY >= y + optionHeight && mouseY < y + h) {
            // Option 2: "Find in Database"
            String hexToSearch = contextMenu.isRowMenu ? contextMenu.targetHex :
                                (contextMenu.piece != null ? contextMenu.piece.getHexcode() : null);

            if (hexToSearch != null) {
                DatabaseScreen dbScreen = new DatabaseScreen(this);
                dbScreen.setHexSearch(hexToSearch);
                if (minecraft != null) {
                    minecraft.setScreen(dbScreen);
                }
            }
            contextMenu = null;
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (pageOrder.isEmpty() || currentPage >= pageOrder.size()) return false;

        String currentCategory = pageOrder.get(currentPage);
        List<ChecklistEntry> entries = categories.get(currentCategory);

        if (entries == null) return false;

        int availableHeight = this.height - START_Y - BOTTOM_MARGIN;
        int maxVisible = Math.max(1, availableHeight / ROW_HEIGHT);
        int maxScroll = Math.max(0, entries.size() - maxVisible);

        if (verticalAmount > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        } else if (verticalAmount < 0) {
            scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        }

        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (isDraggingScrollbar && click.button() == 0) {
            if (pageOrder.isEmpty() || currentPage >= pageOrder.size()) {
                isDraggingScrollbar = false;
                return false;
            }

            String currentCategory = pageOrder.get(currentPage);
            List<ChecklistEntry> entries = categories.get(currentCategory);

            if (entries != null) {
                int availableHeight = this.height - START_Y - BOTTOM_MARGIN;
                int maxVisible = Math.max(1, availableHeight / ROW_HEIGHT);
                int scrollbarY = START_Y;
                int scrollbarHeight = maxVisible * ROW_HEIGHT;

                int maxScroll = Math.max(0, entries.size() - maxVisible);
                scrollOffset = ScrollbarRenderer.calculateScrollFromDrag(click.y(), scrollbarY, scrollbarHeight,
                    entries.size(), maxVisible);
                scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
            }

            return true;
        }

        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (click.button() == 0 && isDraggingScrollbar) {
            isDraggingScrollbar = false;
            return true;
        }

        return super.mouseReleased(click);
    }

    private void copyMissingToClipboard() {
        // Ensure ALL categories have their matches calculated, not just the current page
        int savedPage = currentPage;
        for (int p = 0; p < pageOrder.size(); p++) {
            currentPage = p;
            calculateOptimalMatches();
        }
        currentPage = savedPage;

        StringBuilder sb = new StringBuilder();
        sb.append("Seymour Missing Checklist Entries\n");
        sb.append("=================================\n");
        sb.append("Mode: ").append(fadeDyeMode ? "Fade Dyes" : "Normal").append("\n\n");

        int totalMissing = 0;

        // Iterate all categories in current page order
        for (String category : pageOrder) {
            List<ChecklistEntry> entries = categories.get(category);
            if (entries == null) continue;

            // Ensure matches are calculated for this category
            // (they may not be if the user hasn't visited this page yet)
            // We check all entries for missing pieces
            List<String> missingLines = new ArrayList<>();

            for (ChecklistEntry entry : entries) {
                List<String> missingPieces = new ArrayList<>();

                for (String pieceType : PieceTypeUtil.pieceTypes) {
                    if (pieceToPieceMode && !entry.pieces.contains(pieceType)) {
                        continue;
                    }
                    ArmorPiece match = entry.foundPieces.get(pieceType);
                    if (match == null) {
                        missingPieces.add(pieceType);
                    }
                }

                if (!missingPieces.isEmpty()) {
                    missingLines.add("  #" + entry.hex + " " + entry.name + " | Missing: " + String.join(", ", missingPieces));
                    totalMissing += missingPieces.size();
                }
            }

            if (!missingLines.isEmpty()) {
                sb.append("[").append(category).append("]\n");
                for (String line : missingLines) {
                    sb.append(line).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("Total missing slots: ").append(totalMissing);

        try {
            this.minecraft.keyboardHandler.setClipboard(sb.toString());
            if (minecraft != null && minecraft.player != null) {
                ChatUtils.sendSystemMessage(minecraft.player, 
                    Component.literal("\u00a7a[Seymour] \u00a77Copied " + totalMissing + " missing checklist slots to clipboard!"));
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onClose() {
        if (rememberPage) {
            savedPage = currentPage;
            savedChecklistScroll = scrollOffset;
            savedFadeDyeMode = fadeDyeMode;
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

