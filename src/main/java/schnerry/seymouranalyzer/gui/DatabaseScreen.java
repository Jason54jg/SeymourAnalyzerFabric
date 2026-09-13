package schnerry.seymouranalyzer.gui;

import lombok.Setter;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.config.ClothConfig;
import schnerry.seymouranalyzer.data.ArmorPiece;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.data.ColorDatabase;
import schnerry.seymouranalyzer.util.ColorMath;
import schnerry.seymouranalyzer.util.SkyBlockProfileTracker;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Database GUI showing all collected armor pieces with full sorting, filtering, and search
 * Ported from ChatTriggers databaseGUI.js with full feature parity
 */
public class DatabaseScreen extends ModScreen {
    private List<ArmorPiece> allPieces = new ArrayList<>();
    private List<ArmorPiece> filteredPieces = new ArrayList<>();
    private int scrollOffset = 0;
    private static final int ROW_HEIGHT = 20;
    private static final int HEADER_Y = 50;
    private static final int START_Y = 70;

    // Search and filters
    private EditBox searchField;
    private EditBox hexSearchField;
    // Wildcard help button (hover to show tooltip)
    private net.minecraft.client.gui.components.Button wildcardHelpButton;
    private static final String[] WILDCARD_HELP_LINES = new String[] {
        "Wildcard search (hex patterns):",
        "X = any hex digit (0-9, A-F)",
        "W / Y / Z = bound wildcard: repeated letters must match the same digit",
        "Search must be exactly 6 characters; allowed: 0-9, A-F, X, W, Y, Z",
        "Examples: ",
        "12XX34 matches any hex starting with 12 and ending with 34",
        "12WWYY matches any hex starting with 12 and having the same characters on 3 and 4",
        "and the same characters on 5 and 6 (e.g. 12AABB or 1233CC, but not 12AB34"
    };

    // Sorting
    private String sortColumn = null; // "name", "hex", "match", "deltaE", "absolute", "distance"
    private boolean sortAscending = true;

    // Filters
    private boolean showDupesOnly = false;
    private VisibilityMode fadesMode = VisibilityMode.SHOW;
    private VisibilityMode customsMode = VisibilityMode.SHOW;
    private String selectedProfileId = SkyBlockProfileTracker.ALL_PROFILES;
    private boolean profileDropdownOpen;

    private enum VisibilityMode {
        SHOW,
        HIDE,
        ONLY;

        private VisibilityMode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        private boolean includes(boolean matchesCategory) {
            return switch (this) {
                case SHOW -> true;
                case HIDE -> !matchesCategory;
                case ONLY -> matchesCategory;
            };
        }
    }

    // Scrollbar dragging
    private boolean isDraggingScrollbar = false;

    // guiGraphics menu
    private ContextMenu contextMenu = null;

    private static class ContextMenu {
        ArmorPiece piece;
        int x, y;
        int width = 150;
        int height = 40; // 2 options * 20px
    }

    // Shift-to-expand feature
    private String expandedPieceUuid = null;

    // Cache for closest dupe pieces (avoids recalculating every frame)
    private record CachedClosePiece(ArmorPiece piece, double deltaE, int absDistance) {}
    private final Map<String, List<CachedClosePiece>> closestDupeCache = new HashMap<>();

    // Track shift state for precision display
    private boolean isShiftHeld = false;

    // Pending hex search (set before init())
    private String pendingHexSearch = null;

    // Pending initial search (set before init())
    private String pendingInitialSearch = null;

    // Active hex search value - persists across rebuildWidgets()/re-init
    // This is the authoritative source for the hex search, synced with hexSearchField
    private String activeHexSearch = "";

    // Active text search value - persists across rebuildWidgets()/re-init
    private String activeTextSearch = "";

    // Track whether we've already processed a command-based search in this instance
    // This prevents rememberPosition from overwriting a fresh search on GUI re-init
    private boolean commandSearchProcessed = false;

    // True after the very first init() call; prevents restoreSavedState() from
    // running on subsequent rebuildWidgets() calls (e.g. dupes-button toggle)
    private boolean hasBeenInitialized = false;

    // Set to true by removed() so init() knows it's a return-from-sub-screen,
    // not a plain rebuildWidgets() call
    private boolean wasRemoved = false;

    // Remember position state (static = persists between opens)
    @Setter
    private static boolean rememberPosition = false;
    private static String savedSearchText = "";
    private static String savedHexSearchText = "";
    private static String savedSortColumn = null;
    private static boolean savedSortAscending = true;
    private static int savedScrollOffset = 0;
    private static boolean savedShowDupesOnly = false;
    private static VisibilityMode savedFadesMode = VisibilityMode.SHOW;
    private static VisibilityMode savedCustomsMode = VisibilityMode.SHOW;

    private void saveCurrentState() {
        savedSearchText    = activeTextSearch;
        savedHexSearchText = activeHexSearch;
        savedSortColumn    = sortColumn;
        savedSortAscending = sortAscending;
        savedScrollOffset  = scrollOffset;
        savedShowDupesOnly = showDupesOnly;
        savedFadesMode     = fadesMode;
        savedCustomsMode   = customsMode;
    }

    private void restoreSavedState() {
        showDupesOnly = savedShowDupesOnly;
        fadesMode     = savedFadesMode;
        customsMode   = savedCustomsMode;
        sortColumn    = savedSortColumn;
        sortAscending = savedSortAscending;
        // Restore active search values
        activeHexSearch  = savedHexSearchText;
        activeTextSearch = savedSearchText;
        if (searchField != null && !savedSearchText.isEmpty())
            searchField.setValue(savedSearchText);
        if (hexSearchField != null && !savedHexSearchText.isEmpty())
            hexSearchField.setValue(savedHexSearchText);
        // scrollOffset restored after filterAndSort()
    }

    public DatabaseScreen() {
        this(null);
    }

    public DatabaseScreen(Screen parent) {
        super(Component.literal("Seymour Database"), parent);
        loadPieces();
    }

    /**
     * Set hex search to be applied when the screen initializes
     */
    public void setHexSearch(String hex) {
        this.pendingHexSearch = hex;
    }

    /**
     * Set initial search - automatically detects if it's a hex search or text search
     * If it's exactly 6 hex characters without 'X', it goes to hex search field
     * Otherwise it goes to the main search field
     */
    public void setInitialSearch(String search) {
        this.pendingInitialSearch = search;
    }

    private void loadPieces() {
        Map<String, ArmorPiece> collection = CollectionManager.getInstance().getCollection();
        allPieces = new ArrayList<>(collection.values());

        // Sort by deltaE on first load (best to worst) - matching JS behavior
        allPieces.sort((a, b) -> {
            double deltaA = a.getBestMatch() != null ? a.getBestMatch().deltaE : 999.0;
            double deltaB = b.getBestMatch() != null ? b.getBestMatch().deltaE : 999.0;
            return Double.compare(deltaA, deltaB);
        });

        // Clear dupe cache since piece list changed
        closestDupeCache.clear();

        SeymourAnalyzer.LOGGER.info("Loaded {} pieces into database GUI", allPieces.size());
        filteredPieces = new ArrayList<>(allPieces);
    }

    @Override
    public void removed() {
        super.removed();
        // Mark that we left this screen (navigated to a sub-screen or away entirely).
        // init() uses this to distinguish a return-from-sub-screen from a rebuildWidgets() call.
        wasRemoved = true;
    }

    @Override
    protected void init() {
        super.init();

        // Is this the very first init() for this screen instance?
        boolean isFirstInit = !hasBeenInitialized;
        // Are we returning from a sub-screen (removed() was called before this init())?
        boolean isReturnFromSubScreen = hasBeenInitialized && wasRemoved;

        hasBeenInitialized = true;
        wasRemoved = false; // reset so rebuildWidgets() won't trigger the sub-screen path

        // Save current search text before re-initialization (in case GUI scale triggers re-init)
        String previousSearchText = searchField != null ? searchField.getValue() : null;
        String previousHexText = hexSearchField != null ? hexSearchField.getValue() : null;

        // Search field (top right)
        searchField = new EditBox(this.font, this.width - 255, 8, 235, 20, Component.literal("Search"));
        searchField.setMaxLength(50);
        searchField.setHint(Component.literal("Search hex/match/delta/pattern..."));
        searchField.setResponder(text -> {
            activeTextSearch = text;
            filterAndSort();
        });
        this.addRenderableWidget(searchField);

        // Wildcard help button placed left of the search field; hover to see usage
        int helpX = Math.max(10, this.width - 275);
        wildcardHelpButton = net.minecraft.client.gui.components.Button.builder(Component.literal("?"), btn -> {
            // on click: show a brief chat message with help as well
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal("Wildcard search help: use X for any hex digit; W/Y/Z are bound wildcards."));
            }
        }).bounds(helpX, 10, 16, 16).build();
        this.addRenderableWidget(wildcardHelpButton);

        // Hex search field (below search)
        hexSearchField = new EditBox(this.font, this.width - 145, 35, 125, 20, Component.literal("Hex Search"));
        hexSearchField.setMaxLength(6);
        hexSearchField.setHint(Component.literal("Hex search (ΔE<5)..."));
        hexSearchField.setResponder(text -> {
            activeHexSearch = text.toUpperCase().replace("#", "");
            filterAndSort();
        });
        this.addRenderableWidget(hexSearchField);

        // Checklist button (top left)
        Button checklistButton = Button.builder(Component.literal("Open Checklist GUI"),
            button -> this.minecraft.gui.setScreen(new ArmorChecklistScreen(this)))
            .bounds(20, 10, 150, 20).build();
        this.addRenderableWidget(checklistButton);

        // Remember Position toggle button (right of checklist button)
        Button rememberBtn = Button.builder(
            Component.literal(rememberPosition ? "§aPinned: ON" : "§7Pinned: OFF"),
            button -> {
                rememberPosition = !rememberPosition;
                if (rememberPosition) saveCurrentState();
                this.rebuildWidgets();
            })
            .bounds(175, 10, 100, 20).build();
        this.addRenderableWidget(rememberBtn);

        Button profileSelector = Button.builder(
            Component.literal("Profile: " + SkyBlockProfileTracker.getProfileLabel(selectedProfileId)),
            button -> {
                profileDropdownOpen = !profileDropdownOpen;
                this.rebuildWidgets();
            })
            .bounds(280, 10, 145, 20)
            .build();
        this.addRenderableWidget(profileSelector);

        Button weightButton = Button.builder(Component.literal("§eGo to Weight"),
            button -> this.minecraft.gui.setScreen(new WeightScreen(this)))
            .bounds(430, 10, 90, 20).build();
        this.addRenderableWidget(weightButton);

        // Word matches button (bottom right)
        Button wordButton = Button.builder(Component.literal("§lWord Matches"),
            button -> this.minecraft.gui.setScreen(new WordMatchesScreen(this, selectedProfileId)))
            .bounds(this.width - 140, this.height - 60, 120, 20).build();
        this.addRenderableWidget(wordButton);

        // Pattern matches button (above word button)
        Button patternButton = Button.builder(Component.literal("§lPattern Matches"),
            button -> this.minecraft.gui.setScreen(new PatternMatchesScreen(this, selectedProfileId)))
            .bounds(this.width - 140, this.height - 35, 120, 20).build();
        this.addRenderableWidget(patternButton);

        // Dupes filter button (bottom left)
        Button dupesButton = Button.builder(Component.literal(showDupesOnly ? "Dupes" : "Show Dupes"), button -> {
            showDupesOnly = !showDupesOnly;
            button.setMessage(Component.literal(showDupesOnly ? "Dupes" : "Show Dupes"));
            filterAndSort();
            this.rebuildWidgets(); // Rebuild to show/hide Copy Dupes button
        }).bounds(20, this.height - 35, 85, 20).build();
        this.addRenderableWidget(dupesButton);

        // Copy Dupes button (shown when dupes mode is active)
        if (showDupesOnly) {
            Button copyDupesButton = Button.builder(Component.literal("§eCopy Dupes"),
                button -> copyDupesToClipboard())
                .bounds(110, this.height - 35, 85, 20).build();
            this.addRenderableWidget(copyDupesButton);
        }

        // Fade filter mode button (Show -> Hide -> Only)
        int fadesX = showDupesOnly ? 200 : 110;
        Button fadesButton = Button.builder(Component.literal("Fades: " + fadesMode), button -> {
            fadesMode = fadesMode.next();
            filterAndSort();
            this.rebuildWidgets();
        }).bounds(fadesX, this.height - 35, 85, 20).build();
        this.addRenderableWidget(fadesButton);

        // Custom filter mode button (Show -> Hide -> Only)
        int customsX = fadesX + 90;
        Button customsButton = Button.builder(Component.literal("Customs: " + customsMode), button -> {
            customsMode = customsMode.next();
            filterAndSort();
            this.rebuildWidgets();
        }).bounds(customsX, this.height - 35, 115, 20).build();
        this.addRenderableWidget(customsButton);

        // Apply pending hex search if set (from guiGraphics menu in other screens)
        if (pendingHexSearch != null) {
            activeHexSearch = pendingHexSearch.toUpperCase().replace("#", "");
            activeTextSearch = "";
            sortColumn = "distance";
            sortAscending = true;
            pendingHexSearch = null;
            commandSearchProcessed = true;
        }
        // Apply pending initial search if set (from command argument)
        // This has SECOND HIGHEST priority - overrides rememberPosition
        else if (pendingInitialSearch != null) {
            String search = pendingInitialSearch.replace("#", "").toUpperCase();
            if (search.length() == 6 && search.matches("[0-9A-F]{6}")) {
                activeHexSearch = search;
                activeTextSearch = "";
                sortColumn = "distance";
                sortAscending = true;
            } else {
                activeTextSearch = pendingInitialSearch; // keep original casing for text search
                activeHexSearch = "";
            }
            pendingInitialSearch = null;
            commandSearchProcessed = true;
        }
        // Returning from a sub-screen (Word Matches, Pattern Matches, etc.) without pinned:
        // reset search so the DB shows in its default state
        else if (isReturnFromSubScreen && !rememberPosition) {
            activeHexSearch = "";
            activeTextSearch = "";
            sortColumn = null;
            commandSearchProcessed = false;
        }
        // Restore pinned state if enabled and no command-based search was just processed
        // Only on the FIRST init() – not on rebuildWidgets() calls (would overwrite live state)
        else if (rememberPosition && !commandSearchProcessed && isFirstInit) {
            restoreSavedState();
            // activeHexSearch/activeTextSearch will be synced from field values set in restoreSavedState
            activeHexSearch = hexSearchField.getValue().toUpperCase().replace("#", "");
            activeTextSearch = searchField.getValue();
        }
        // If no pending search but we had previous text, restore it (GUI scale re-init)
        // isFirstInit is false here on rebuildWidgets, but previousText values are set →
        // still guard with isFirstInit to avoid overwriting user-changed activeHexSearch etc.
        else if (isFirstInit && !commandSearchProcessed && (previousSearchText != null || previousHexText != null)) {
            if (previousHexText != null && !previousHexText.isEmpty()) {
                activeHexSearch = previousHexText.toUpperCase().replace("#", "");
            }
            if (previousSearchText != null && !previousSearchText.isEmpty()) {
                activeTextSearch = previousSearchText;
            }
        }

        // Now sync activeHexSearch / activeTextSearch into the actual fields and apply filters
        if (!activeHexSearch.isEmpty() && activeHexSearch.matches("[0-9A-F]{6}")) {
            hexSearchField.setValue(activeHexSearch);
            this.setFocused(hexSearchField);
            hexSearchField.setFocused(true);
            if (!"distance".equals(sortColumn)) {
                sortColumn = "distance";
                sortAscending = true;
            }
        } else if (!activeTextSearch.isEmpty()) {
            searchField.setValue(activeTextSearch);
            this.setFocused(searchField);
            searchField.setFocused(true);
        }
        filterAndSort();


        // Restore scroll offset after filterAndSort (which resets it to 0)
        if (rememberPosition && pendingHexSearch == null && pendingInitialSearch == null) {
            int maxScroll = Math.max(0, filteredPieces.size() - Math.max(1, (this.height - START_Y - 40) / ROW_HEIGHT));
            scrollOffset = Math.min(savedScrollOffset, maxScroll);
        }

    }

    @Override
    public void render(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        // Don't fill ANY background - let default background show through
        // Text renders correctly without background fills covering it

        // Check if shift is held and update expanded piece
        updateExpandedPiece(mouseX, mouseY);

        // Title - simple white text
        String titleStr = "Seymour Database";
        int titleWidth = this.font.width(titleStr);
        guiGraphics.text(this.font, titleStr, this.width / 2 - titleWidth / 2, 5, 0xFFFFFFFF);

        // Collection size info - calculate total width first, then center
        String totalLabel = "Total: ";
        String totalCount = String.valueOf(allPieces.size());
        String piecesLabel = " pieces";
        String filteredText = "";
        String filteredCount = "";
        String filteredEnd = "";

        int totalInfoWidth = this.font.width(totalLabel) +
                            this.font.width(totalCount) +
                            this.font.width(piecesLabel);

        if (filteredPieces.size() != allPieces.size()) {
            filteredText = " (Filtered: ";
            filteredCount = String.valueOf(filteredPieces.size());
            filteredEnd = ")";
            totalInfoWidth += this.font.width(filteredText) +
                            this.font.width(filteredCount) +
                            this.font.width(filteredEnd);
        }

        // Now draw centered
        int infoX = this.width / 2 - totalInfoWidth / 2;
        guiGraphics.text(this.font, totalLabel, infoX, 19, 0xFF888888);
        infoX += this.font.width(totalLabel);
        guiGraphics.text(this.font, totalCount, infoX, 19, 0xFFFFFF55);
        infoX += this.font.width(totalCount);
        guiGraphics.text(this.font, piecesLabel, infoX, 19, 0xFF888888);

        if (filteredPieces.size() != allPieces.size()) {
            infoX += this.font.width(piecesLabel);
            guiGraphics.text(this.font, filteredText, infoX, 19, 0xFF888888);
            infoX += this.font.width(filteredText);
            guiGraphics.text(this.font, filteredCount, infoX, 19, 0xFFFFFF55);
            infoX += this.font.width(filteredCount);
            guiGraphics.text(this.font, filteredEnd, infoX, 19, 0xFF888888);
        }

        // Calculate tier counts
        int t1Normal = 0, t1Fade = 0, t2Normal = 0, t2Fade = 0, dupes = 0;
        Map<String, Integer> hexCounts = new HashMap<>();

        for (ArmorPiece piece : allPieces) {
            // Count hex occurrences for dupes
            String hex = piece.getHexcode();
            hexCounts.put(hex, hexCounts.getOrDefault(hex, 0) + 1);

            if (piece.getBestMatch() != null) {
                double deltaE = piece.getBestMatch().deltaE;
                boolean isFade = checkFadeDye(piece.getBestMatch().colorName);
                boolean isCustom = ClothConfig.getInstance().getCustomColors().containsKey(piece.getBestMatch().colorName);

                if (deltaE <= 2) {
                    if (isCustom || !isFade) {
                        t1Normal++;
                    } else {
                        t1Fade++;
                    }
                } else if (deltaE <= 5) {
                    if (isFade && !isCustom) {
                        t2Fade++;
                    } else {
                        t2Normal++;
                    }
                }
            }
        }

        // Count actual dupes
        for (ArmorPiece piece : allPieces) {
            if (hexCounts.getOrDefault(piece.getHexcode(), 0) > 1) {
                dupes++;
            }
        }

        // Display tier counts (two rows) - calculate total width first, then center
        // Row 1: T1, T2, Dupes
        String t1Label = "T1: ";
        String t1Value = String.valueOf(t1Normal);
        String t2Label = "T2: ";
        String t2Value = String.valueOf(t2Normal);
        String dupesLabel = "Dupes: ";
        String dupesValue = String.valueOf(dupes);

        int row1Width = this.font.width(t1Label) +
                       this.font.width(t1Value) + 10 +
                       this.font.width(t2Label) +
                       this.font.width(t2Value) + 10 +
                       this.font.width(dupesLabel) +
                       this.font.width(dupesValue);

        int row1X = this.width / 2 - row1Width / 2;
        guiGraphics.text(this.font, t1Label, row1X, 30, 0xFF888888);
        row1X += this.font.width(t1Label);
        guiGraphics.text(this.font, t1Value, row1X, 30, 0xFFFF5555);
        row1X += this.font.width(t1Value) + 10;

        guiGraphics.text(this.font, t2Label, row1X, 30, 0xFF888888);
        row1X += this.font.width(t2Label);
        guiGraphics.text(this.font, t2Value, row1X, 30, 0xFFFFAA00);
        row1X += this.font.width(t2Value) + 10;

        guiGraphics.text(this.font, dupesLabel, row1X, 30, 0xFF888888);
        row1X += this.font.width(dupesLabel);
        guiGraphics.text(this.font, dupesValue, row1X, 30, 0xFFFF55FF);

        // Row 2: T1 Fade, T2 Fade
        String t1FadeLabel = "T1 Fade: ";
        String t1FadeValue = String.valueOf(t1Fade);
        String t2FadeLabel = "T2 Fade: ";
        String t2FadeValue = String.valueOf(t2Fade);

        int row2Width = this.font.width(t1FadeLabel) +
                       this.font.width(t1FadeValue) + 10 +
                       this.font.width(t2FadeLabel) +
                       this.font.width(t2FadeValue);

        int row2X = this.width / 2 - row2Width / 2;
        guiGraphics.text(this.font, t1FadeLabel, row2X, 40, 0xFF888888);
        row2X += this.font.width(t1FadeLabel);
        guiGraphics.text(this.font, t1FadeValue, row2X, 40, 0xFF5555FF);
        row2X += this.font.width(t1FadeValue) + 10;

        guiGraphics.text(this.font, t2FadeLabel, row2X, 40, 0xFF888888);
        row2X += this.font.width(t2FadeLabel);
        guiGraphics.text(this.font, t2FadeValue, row2X, 40, 0xFFFFFF55);

        if (filteredPieces.isEmpty()) {
            String noResultsMsg = !activeTextSearch.isEmpty() || !activeHexSearch.isEmpty()
                ? "No results for search"
                : "No pieces. Use /seymour scan start";
            int msgWidth = this.font.width(noResultsMsg);
            guiGraphics.text(this.font, noResultsMsg, this.width / 2 - msgWidth / 2, this.height / 2, 0xFF888888);

            // DON'T return early - still need to render widgets!
            // Render widgets so buttons still work
            super.render(guiGraphics, mouseX, mouseY, delta);
            renderProfileDropdownOverlay(guiGraphics, mouseX, mouseY);
            return;
        }

        // Headers - plain text with sort arrows
        String nameArrow = sortColumn != null && sortColumn.equals("name") ? (sortAscending ? " ↓" : " ↑") : "";
        String hexArrow = sortColumn != null && sortColumn.equals("hex") ? (sortAscending ? " ↓" : " ↑") : "";
        String matchArrow = sortColumn != null && sortColumn.equals("match") ? (sortAscending ? " ↓" : " ↑") : "";
        String deltaArrow = sortColumn != null && sortColumn.equals("deltaE") ? (sortAscending ? " ↓" : " ↑") : "";
        String absArrow = sortColumn != null && sortColumn.equals("absolute") ? (sortAscending ? " ↓" : " ↑") : "";

        guiGraphics.text(this.font, "Name" + nameArrow, 20, HEADER_Y, 0xFFAAAAAA);
        guiGraphics.text(this.font, "Hex" + hexArrow, 200, HEADER_Y, 0xFFAAAAAA);
        guiGraphics.text(this.font, "Match" + matchArrow, 300, HEADER_Y, 0xFFAAAAAA);
        guiGraphics.text(this.font, "ΔE" + deltaArrow, 550, HEADER_Y, 0xFFAAAAAA);
        guiGraphics.text(this.font, "Absolute" + absArrow, 630, HEADER_Y, 0xFFAAAAAA);

        // Show "Closest" column when hex search is active with 6 digits
        String hexSearchText = !activeHexSearch.isEmpty() ? activeHexSearch
            : (hexSearchField != null ? hexSearchField.getValue().replace("#", "") : "");
        boolean showClosestColumn = hexSearchText.length() == 6 && hexSearchText.matches("[0-9A-Fa-f]{6}");

        if (showClosestColumn) {
            String distanceArrow = sortColumn != null && sortColumn.equals("distance") ? (sortAscending ? " ↓" : " ↑") : "";
            guiGraphics.text(this.font, "Closest" + distanceArrow, 710, HEADER_Y, 0xFFAAAAAA);
        }

        // Separator line
        guiGraphics.fill(20, HEADER_Y + 12, this.width - 20, HEADER_Y + 13, 0xFF646464);

        // Calculate visible rows dynamically based on screen size
        int availableHeight = this.height - START_Y - 40; // 40 for footer
        int maxVisibleRows = Math.max(1, availableHeight / ROW_HEIGHT);

        // Draw pieces - accounting for variable row heights when expanded
        int endIndex = Math.min(scrollOffset + maxVisibleRows, filteredPieces.size());
        int currentY = START_Y;

        for (int i = scrollOffset; i < endIndex && currentY < this.height - 40; i++) {
            ArmorPiece piece = filteredPieces.get(i);
            int rowHeight = getActualRowHeight(piece);

            // Only draw if the row is at least partially visible
            if (currentY + rowHeight > START_Y) {
                drawPieceRow(guiGraphics, piece, currentY);
            }

            currentY += rowHeight;
        }

        // Draw scrollbar if needed
        if (filteredPieces.size() > maxVisibleRows) {
            int scrollbarX = this.width - 15;
            int scrollbarY = START_Y;
            int scrollbarHeight = maxVisibleRows * ROW_HEIGHT;
            ScrollbarRenderer.renderVerticalScrollbar(guiGraphics, scrollbarX, scrollbarY, scrollbarHeight,
                scrollOffset, filteredPieces.size(), maxVisibleRows);
        }

        // Footer - simple text
        String footerStr = "Showing " + (scrollOffset + 1) + "-" + endIndex + " of " + filteredPieces.size();
        int footerWidth = this.font.width(footerStr);
        guiGraphics.text(this.font, footerStr, this.width / 2 - footerWidth / 2, this.height - 25, 0xFF888888);

        // ESC text - draw in segments for color
        int escX = this.width / 2 - 60;
        guiGraphics.text(this.font, "Press ", escX, this.height - 10, 0xFF888888);
        escX += this.font.width("Press ");
        guiGraphics.text(this.font, "ESC", escX, this.height - 10, 0xFFFFFF55);
        escX += this.font.width("ESC");
        guiGraphics.text(this.font, " to close", escX, this.height - 10, 0xFF888888);

        // Draw guiGraphics menu on top if open
        if (contextMenu != null) {
            drawContextMenu(guiGraphics, mouseX, mouseY);
        }

        // Render widgets LAST so they don't cover our text
        super.render(guiGraphics, mouseX, mouseY, delta);

        // Draw wildcard help tooltip when hovering the help button
        int helpX = Math.max(10, this.width - 275);
        int helpY = 13;
        int helpW = 16;
        int helpH = 16;
        if (mouseX >= helpX && mouseX <= helpX + helpW && mouseY >= helpY && mouseY <= helpY + helpH) {
            // Build tooltip box size
            int padding = 6;
            int lineHeight = this.font.lineHeight + 2;
            int maxW = 0;
            for (String line : WILDCARD_HELP_LINES) {
                maxW = Math.max(maxW, this.font.width(line));
            }

            int boxX = Math.min(this.width - 20 - (maxW + padding * 2), (int) mouseX + 12);
            int boxY = (int) mouseY + 12;

            int boxW = maxW + padding * 2;
            int boxH = (WILDCARD_HELP_LINES.length * lineHeight) + padding;

            // Background and border
            guiGraphics.fill(boxX - 3, boxY - 3, boxX + boxW + 3, boxY + boxH + 3, 0xF0282828);
            guiGraphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xCC111111);
            guiGraphics.fill(boxX, boxY, boxX + boxW, boxY + 1, 0xFF646464);

            // Draw lines
            int textY = boxY + 4;
            for (String line : WILDCARD_HELP_LINES) {
                guiGraphics.text(this.font, line, boxX + padding, textY, 0xFFFFFFFF);
                textY += lineHeight;
            }
        }

        renderProfileDropdownOverlay(guiGraphics, mouseX, mouseY);
    }

    private void renderProfileDropdownOverlay(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        if (!profileDropdownOpen) return;

        guiGraphics.fill(0, 0, this.width, this.height, 0x90000000);

        int x = 280;
        int y = 10;
        int width = 145;
        drawProfileDropdownButton(guiGraphics, x, y, width,
            "Profile: " + SkyBlockProfileTracker.getProfileLabel(selectedProfileId), false);

        int optionY = y + 20;
        for (String profileId : SkyBlockProfileTracker.getProfileOptions()) {
            String label = SkyBlockProfileTracker.getProfileLabel(profileId);
            drawProfileDropdownButton(guiGraphics, x, optionY, width, label, profileId.equals(selectedProfileId));
            optionY += 20;
        }
    }

    private void drawProfileDropdownButton(GuiGraphicsExtractor guiGraphics, int x, int y, int width,
                                           String label, boolean selected) {
        guiGraphics.fill(x, y, x + width, y + 20, 0xFF808080);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + 19, 0xFF303030);
        guiGraphics.centeredText(this.font, selected ? "\u00a7a" + label : label, x + width / 2, y + 6, 0xFFFFFFFF);
    }

    private void drawPieceRow(GuiGraphicsExtractor guiGraphics, ArmorPiece piece, int y) {
        boolean isExpanded = piece.getUuid().equals(expandedPieceUuid);

        // Draw highlight backgrounds first
        if (piece.getBestMatch() != null) {
            double deltaE = piece.getBestMatch().deltaE;
            boolean isFade = checkFadeDye(piece.getBestMatch().colorName);
            boolean isCustom = ClothConfig.getInstance().getCustomColors().containsKey(piece.getBestMatch().colorName);

            int highlightColor = 0;

            if (isCustom) {
                if (deltaE <= 2) {
                    highlightColor = 0x48006400;
                } else if (deltaE <= 5) {
                    highlightColor = 0x48556B2F;
                }
            } else if (!isFade) {
                if (deltaE <= 1) {
                    highlightColor = 0x48FF0000;
                } else if (deltaE <= 2) {
                    highlightColor = 0x48FF69B4;
                } else if (deltaE <= 5) {
                    highlightColor = 0x48FFA500;
                }
            } else {
                if (deltaE <= 1) {
                    highlightColor = 0x480000FF;
                } else if (deltaE <= 2) {
                    highlightColor = 0x4887CEFA;
                } else if (deltaE <= 5) {
                    highlightColor = 0x48FFFF00;
                }
            }

            if (highlightColor != 0) {
                guiGraphics.fill(540, y - 2, 680, y + ROW_HEIGHT - 2, highlightColor);
            }
        }

        // Hex color box
        ColorMath.RGB rgb = ColorMath.hexToRgb(piece.getHexcode());
        int color = 0xFF000000 | (rgb.r() << 16) | (rgb.g() << 8) | rgb.b();
        guiGraphics.fill(200, y, 285, y + 16, color);

        // Draw text - using the EXACT same approach as the title/headers that ARE working
        String nameStr = piece.getPieceName();
        if (nameStr.length() > 25) {
            nameStr = nameStr.substring(0, 25) + "...";
        }

        // Draw with alpha channel included
        guiGraphics.text(this.font, nameStr, 20, y + 4, 0xFFFFFFFF);

        // Hex text
        String hexStr = piece.getHexcode();
        if (ColorMath.isColorDark(piece.getHexcode())) {
            guiGraphics.text(this.font, hexStr, 202, y + 4, 0xFFFFFFFF);
        } else {
            guiGraphics.text(this.font, hexStr, 202, y + 4, 0xFF000000);
        }

        // Match name
        if (piece.getBestMatch() != null) {
            String matchStr = piece.getBestMatch().colorName;
            if (matchStr.length() > 35) {
                matchStr = matchStr.substring(0, 35) + "...";
            }
            guiGraphics.text(this.font, matchStr, 300, y + 4, 0xFF55FFFF);

            double deltaE = piece.getBestMatch().deltaE;
            boolean isFade = checkFadeDye(piece.getBestMatch().colorName);
            boolean isCustom = ClothConfig.getInstance().getCustomColors().containsKey(piece.getBestMatch().colorName);

            int deColor;
            if (isCustom) {
                deColor = deltaE < 2 ? 0xFF00AA00 : (deltaE < 5 ? 0xFF55FF55 : 0xFFAAAAAA);
            } else if (deltaE < 1) {
                deColor = isFade ? 0xFF5555FF : 0xFFFF5555;
            } else if (deltaE < 2) {
                deColor = isFade ? 0xFF55FFFF : 0xFFFF55FF;
            } else if (deltaE < 5) {
                deColor = isFade ? 0xFFFFFF55 : 0xFFFFAA00;
            } else {
                deColor = 0xFFAAAAAA;
            }

            String deFormat = isShiftHeld ? "%.5f" : "%.2f";
            guiGraphics.text(this.font, String.format(deFormat, deltaE), 550, y + 4, deColor);

            int absDistance = piece.getBestMatch().absoluteDistance;
            guiGraphics.text(this.font, String.valueOf(absDistance), 630, y + 4, 0xFFAAAAAA);
        } else {
            guiGraphics.text(this.font, "Unknown", 300, y + 4, 0xFFAAAAAA);
            guiGraphics.text(this.font, "-", 550, y + 4, 0xFFAAAAAA);
            guiGraphics.text(this.font, "-", 630, y + 4, 0xFFAAAAAA);
        }

        // Display "Closest" column when hex search is active
        String hexSearchText = !activeHexSearch.isEmpty() ? activeHexSearch
            : (hexSearchField != null ? hexSearchField.getValue().replace("#", "") : "");
        boolean showClosestColumn = hexSearchText.length() == 6 && hexSearchText.matches("[0-9A-Fa-f]{6}");

        if (showClosestColumn && piece.getCachedSearchDeltaE() != null && piece.getCachedSearchDistance() != null) {
            double searchDeltaE = piece.getCachedSearchDeltaE();
            int searchDistance = piece.getCachedSearchDistance();

            // Draw highlight behind the Closest column based on deltaE
            int closestHighlight = 0;
            if (searchDeltaE <= 1) {
                closestHighlight = 0x50FF0000;  // Red
            } else if (searchDeltaE <= 2) {
                closestHighlight = 0x50FF69B4;  // Pink
            } else if (searchDeltaE <= 5) {
                closestHighlight = 0x50FFA500;  // Orange
            }

            if (closestHighlight != 0) {
                guiGraphics.fill(710, y - 2, 800, y + ROW_HEIGHT - 2, closestHighlight);
            }

            // Display as "Δ[deltaE] - [distance]"
            String closestFormat = isShiftHeld ? "Δ%.5f - %d" : "Δ%.2f - %d";
            String closestText = String.format(closestFormat, searchDeltaE, searchDistance);
            guiGraphics.text(this.font, closestText, 720, y + 4, 0xFFAAAAAA);
        }

        // Draw expanded matches if this piece is expanded
        if (isExpanded && piece.getAllMatches() != null && !piece.getAllMatches().isEmpty()) {
            drawExpandedMatches(guiGraphics, piece, y + 20);
        }
    }

    private void drawExpandedMatches(GuiGraphicsExtractor guiGraphics, ArmorPiece piece, int startY) {
        // In dupe mode, show the 3 closest pieces from collection instead of color matches
        if (showDupesOnly) {
            drawClosestCollectionPieces(guiGraphics, piece, startY);
            return;
        }

        List<ArmorPiece.ColorMatch> matches = piece.getAllMatches();
        if (matches == null || matches.isEmpty()) {
            return;
        }

        int currentY = startY;
        int displayNum = 1;
        int numMatches = Math.min(3, matches.size());

        for (int i = 0; i < numMatches; i++) {
            ArmorPiece.ColorMatch match = matches.get(i);
            boolean isFade = checkFadeDye(match.colorName);
            boolean isCustom = ClothConfig.getInstance().getCustomColors().containsKey(match.colorName);

            if (!fadesMode.includes(isFade)) {
                continue;
            }

            if (!customsMode.includes(isCustom)) {
                continue;
            }

            // Draw tier color highlight
            int highlightColor = 0;
            if (!isFade) {
                if (match.deltaE <= 1) {
                    highlightColor = 0x48FF0000; // Red
                } else if (match.deltaE <= 2) {
                    highlightColor = 0x48FF69B4; // Pink
                } else if (match.deltaE <= 5) {
                    highlightColor = 0x48FFA500; // Orange
                }
            } else {
                if (match.deltaE <= 1) {
                    highlightColor = 0x480000FF; // Blue
                } else if (match.deltaE <= 2) {
                    highlightColor = 0x4887CEFA; // Light blue
                } else if (match.deltaE <= 5) {
                    highlightColor = 0x48FFFF00; // Yellow
                }
            }

            if (highlightColor != 0) {
                guiGraphics.fill(540, currentY, 680, currentY + 16, highlightColor);
            }

            // Draw match color box
            ColorMath.RGB matchRgb = ColorMath.hexToRgb(match.targetHex);
            int matchColor = 0xFF000000 | (matchRgb.r() << 16) | (matchRgb.g() << 8) | matchRgb.b();
            guiGraphics.fill(30, currentY, 90, currentY + 14, matchColor);

            // Draw match name
            String matchName = match.colorName;
            if (matchName.length() > 30) {
                matchName = matchName.substring(0, 30) + "...";
            }
            String matchText = displayNum + ". " + matchName;
            guiGraphics.text(this.font, matchText, 95, currentY + 3, 0xFF55FFFF);

            // Draw deltaE with color
            int deColor;
            if (match.deltaE < 1) {
                deColor = 0xFFFF5555;
            } else if (match.deltaE < 2) {
                deColor = 0xFFFF55FF;
            } else if (match.deltaE < 5) {
                deColor = 0xFFFFAA00;
            } else {
                deColor = 0xFFAAAAAA;
            }
            String matchDeFormat = isShiftHeld ? "%.5f" : "%.2f";
            guiGraphics.text(this.font, String.format(matchDeFormat, match.deltaE), 550, currentY + 3, deColor);

            // Draw absolute distance
            guiGraphics.text(this.font, String.valueOf(match.absoluteDistance), 630, currentY + 3, 0xFFAAAAAA);

            currentY += 20;
            displayNum++;
        }
    }

    /**
     * Draw the 3 closest pieces from the collection to this dupe piece's hex.
     * Shows name, hex, and deltaE for each close piece.
     */
    private void drawClosestCollectionPieces(GuiGraphicsExtractor guiGraphics, ArmorPiece piece, int startY) {
        String pieceHex = piece.getHexcode();
        if (pieceHex == null || pieceHex.isEmpty()) return;

        // Use cached results if available, otherwise compute and cache
        List<CachedClosePiece> closest = closestDupeCache.computeIfAbsent(piece.getUuid(), uuid -> {
            return allPieces.stream()
                .filter(p -> !p.getUuid().equals(uuid) && !p.getHexcode().equalsIgnoreCase(pieceHex))
                .map(p -> {
                    double dE = ColorMath.calculateDeltaE(pieceHex, p.getHexcode());
                    int abs = ColorMath.calculateAbsoluteDistance(pieceHex, p.getHexcode());
                    return new CachedClosePiece(p, dE, abs);
                })
                .sorted(Comparator.comparingDouble(CachedClosePiece::deltaE))
                .limit(3)
                .collect(Collectors.toList());
        });

        int currentY = startY;
        int displayNum = 1;

        for (CachedClosePiece cached : closest) {
            double deltaE = cached.deltaE();
            int absDistance = cached.absDistance();
            ArmorPiece closePiece = cached.piece();

            // Draw tier color highlight background based on deltaE
            int highlightColor = 0;
            if (deltaE <= 1) {
                highlightColor = 0x48FF0000; // Red
            } else if (deltaE <= 2) {
                highlightColor = 0x48FF69B4; // Pink
            } else if (deltaE <= 5) {
                highlightColor = 0x48FFA500; // Orange
            }

            if (highlightColor != 0) {
                guiGraphics.fill(540, currentY, 680, currentY + 16, highlightColor);
            }

            // Draw color box
            ColorMath.RGB matchRgb = ColorMath.hexToRgb(closePiece.getHexcode());
            int matchColor = 0xFF000000 | (matchRgb.r() << 16) | (matchRgb.g() << 8) | matchRgb.b();
            guiGraphics.fill(30, currentY, 90, currentY + 14, matchColor);

            // Draw piece name + hex
            String name = closePiece.getPieceName();
            if (name.length() > 25) {
                name = name.substring(0, 25) + "...";
            }
            String matchText = displayNum + ". " + name + " #" + closePiece.getHexcode();
            guiGraphics.text(this.font, matchText, 95, currentY + 3, 0xFF55FFFF);

            // Draw deltaE with color
            int deColor;
            if (deltaE < 1) {
                deColor = 0xFFFF5555;
            } else if (deltaE < 2) {
                deColor = 0xFFFF55FF;
            } else if (deltaE < 5) {
                deColor = 0xFFFFAA00;
            } else {
                deColor = 0xFFAAAAAA;
            }
            String dupeDeFormat = isShiftHeld ? "Δ%.5f" : "Δ%.2f";
            guiGraphics.text(this.font, String.format(dupeDeFormat, deltaE), 550, currentY + 3, deColor);

            // Draw absolute distance
            guiGraphics.text(this.font, String.valueOf(absDistance), 630, currentY + 3, 0xFFAAAAAA);

            currentY += 20;
            displayNum++;
        }
    }

    private void drawContextMenu(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        int x = contextMenu.x;
        int y = contextMenu.y;
        int w = contextMenu.width;
        int h = contextMenu.height;
        int optionHeight = 20;

        // Background
        guiGraphics.fill(x, y, x + w, y + h, 0xF0282828);

        // Border
        guiGraphics.fill(x, y, x + w, y + 2, 0xFF646464);
        guiGraphics.fill(x, y + h - 2, x + w, y + h, 0xFF646464);
        guiGraphics.fill(x, y, x + 2, y + h, 0xFF646464);
        guiGraphics.fill(x + w - 2, y, x + w, y + h, 0xFF646464);

        // Highlight hovered option
        int relativeY = mouseY - y;
        int hoveredOption = relativeY / optionHeight;
        if (hoveredOption >= 0 && hoveredOption < 2 && mouseX >= x && mouseX <= x + w) {
            guiGraphics.fill(x, y + (hoveredOption * optionHeight), x + w, y + ((hoveredOption + 1) * optionHeight), 0x80505050);
        }

        // Option 1: "Find Piece"
        guiGraphics.text(this.font, "Find Piece", x + 5, y + 6, 0xFFFFFFFF);

        // Option 2: "Remove Piece"
        guiGraphics.text(this.font, "Remove Piece", x + 5, y + 26, 0xFFFFFFFF);
    }

    private boolean handleContextMenuClick(double mouseX, double mouseY, int button) {
        if (contextMenu == null || button != 0) return false;

        int x = contextMenu.x;
        int y = contextMenu.y;
        int w = contextMenu.width;
        int h = contextMenu.height;

        // Check if click is outside menu - close it
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
            contextMenu = null;
            return false;
        }

        // Check which option was clicked
        int relativeY = (int) (mouseY - y);
        int optionHeight = 20;
        int clickedOption = relativeY / optionHeight;

        if (clickedOption == 0) {
            // Option 1: "Find Piece" - execute search command
            if (minecraft != null && minecraft.player != null) {
                String hex = contextMenu.piece.getHexcode();
                minecraft.player.sendSystemMessage(Component.literal("§a[Seymour] §7Searching for pieces with hex " + hex + "..."));
                minecraft.player.connection.sendCommand("seymour search " + hex);
            }
            contextMenu = null;
            return true;
        } else if (clickedOption == 1) {
            // Option 2: "Remove Piece" - remove from collection
            String uuid = contextMenu.piece.getUuid();
            String pieceName = contextMenu.piece.getPieceName();
            String hex = contextMenu.piece.getHexcode();

            CollectionManager.getInstance().removePiece(uuid);

            // Rebuild filtered pieces list and clear dupe cache
            allPieces.removeIf(p -> p.getUuid().equals(uuid));
            closestDupeCache.clear();
            filterAndSort();

            if (minecraft != null && minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal("§a[Seymour] §7Removed piece: §f" + pieceName + " §7(" + hex + ")"));
                minecraft.player.sendSystemMessage(Component.literal("§a[Seymour] §7New piece count: §e" + allPieces.size()));
            }

            contextMenu = null;
            return true;
        }

        contextMenu = null;
        return false;
    }

    private boolean handleRightClick(double mouseX, double mouseY) {
        // Check if right-clicking on a piece row (accounting for variable heights)
        if (mouseY < START_Y || mouseY > this.height - 40) {
            return false;
        }

        int availableHeight = this.height - START_Y - 40;
        int maxVisibleRows = Math.max(1, availableHeight / ROW_HEIGHT);
        int endIndex = Math.min(scrollOffset + maxVisibleRows, filteredPieces.size());

        int currentY = START_Y;

        // Walk through visible pieces accounting for actual heights
        for (int i = scrollOffset; i < endIndex; i++) {
            ArmorPiece piece = filteredPieces.get(i);
            int rowHeight = getActualRowHeight(piece);

            if (mouseY >= currentY && mouseY < currentY + rowHeight) {
                // Show guiGraphics menu
                contextMenu = new ContextMenu();
                contextMenu.piece = piece;
                contextMenu.x = (int) mouseX;
                contextMenu.y = (int) mouseY;
                return true;
            }

            currentY += rowHeight;
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Calculate max visible rows dynamically
        int availableHeight = this.height - START_Y - 40;
        int maxVisibleRows = Math.max(1, availableHeight / ROW_HEIGHT);
        int maxScroll = Math.max(0, filteredPieces.size() - maxVisibleRows);

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
            int availableHeight = this.height - START_Y - 40;
            int maxVisibleRows = Math.max(1, availableHeight / ROW_HEIGHT);
            int scrollbarY = START_Y;
            int scrollbarHeight = maxVisibleRows * ROW_HEIGHT;

            int maxScroll = Math.max(0, filteredPieces.size() - maxVisibleRows);
            scrollOffset = ScrollbarRenderer.calculateScrollFromDrag(click.y(), scrollbarY, scrollbarHeight,
                filteredPieces.size(), maxVisibleRows);
            scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
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

    private void filterAndSort() {
        // Start with all pieces
        List<ArmorPiece> result = new ArrayList<>(allPieces);

        if (!SkyBlockProfileTracker.ALL_PROFILES.equals(selectedProfileId)) {
            result = result.stream()
                .filter(piece -> selectedProfileId.equals(piece.getProfileId()))
                .collect(Collectors.toList());
        }

        // Apply dupes filter first if enabled
        if (showDupesOnly) {
            Map<String, Integer> hexCounts = new HashMap<>();
            for (ArmorPiece piece : result) {
                String hex = piece.getHexcode();
                hexCounts.put(hex, hexCounts.getOrDefault(hex, 0) + 1);
            }

            result = result.stream()
                .filter(piece -> hexCounts.getOrDefault(piece.getHexcode(), 0) > 1)
                .collect(Collectors.toList());
        }

        Map<String, String> customColors = ClothConfig.getInstance().getCustomColors();
        result = result.stream()
            .filter(piece -> {
                if (piece.getBestMatch() == null) {
                    return fadesMode != VisibilityMode.ONLY && customsMode != VisibilityMode.ONLY;
                }

                boolean isFade = checkFadeDye(piece.getBestMatch().colorName);
                boolean isCustom = customColors.containsKey(piece.getBestMatch().colorName);
                return fadesMode.includes(isFade) && customsMode.includes(isCustom);
            })
            .collect(Collectors.toList());

        // Apply text search filter
        String searchText = searchField != null ? searchField.getValue() : "";
        if (!searchText.isEmpty()) {
            String searchLower = searchText.toLowerCase();
            String searchUpper = searchText.toUpperCase();

            // Check if this is a hex pattern with wildcards (X/W/Y/Z), length must be 6 and only allowed chars
            boolean hasWildcard = searchUpper.length() == 6 && searchUpper.matches("[0-9A-FWXYZ]+");

            if (hasWildcard) {
                // Build regex pattern from wildcard search. Support bound wildcards W/Y/Z which must match
                // the same hex digit for every occurrence (use capturing groups + backreferences).
                StringBuilder regexBuilder = new StringBuilder("^");
                Map<Character, Integer> groupMap = new HashMap<>();
                int nextGroup = 1;

                for (int i = 0; i < 6; i++) {
                    char c = searchUpper.charAt(i);
                    if (c == 'X') {
                        regexBuilder.append("[0-9A-F]");
                    } else if (c == 'W' || c == 'Y' || c == 'Z') {
                        if (!groupMap.containsKey(c)) {
                            groupMap.put(c, nextGroup++);
                            regexBuilder.append("([0-9A-F])");
                        } else {
                            // backreference to previously captured group
                            regexBuilder.append("\\").append(groupMap.get(c));
                        }
                    } else {
                        // literal hex char
                        regexBuilder.append(c);
                    }
                }
                regexBuilder.append("$");
                final Pattern compiled = Pattern.compile(regexBuilder.toString());

                result = result.stream()
                    .filter(piece -> {
                        String hexClean = piece.getHexcode().replace("#", "").toUpperCase();
                        return compiled.matcher(hexClean).matches();
                    })
                    .collect(Collectors.toList());
            } else {
                // Normal text search
                result = result.stream()
                    .filter(piece -> {
                        String name = piece.getPieceName().toLowerCase();
                        String hex = piece.getHexcode().toLowerCase();

                        if (name.contains(searchLower) || hex.contains(searchLower)) {
                            return true;
                        }

                        if (piece.getBestMatch() != null) {
                            String match = piece.getBestMatch().colorName.toLowerCase();
                            if (match.contains(searchLower)) {
                                return true;
                            }

                            String delta = String.format("%.2f", piece.getBestMatch().deltaE);
                            return delta.contains(searchLower);
                        }

                        return false;
                    })
                    .collect(Collectors.toList());
            }
        }

        // Apply hex search filter (only with exactly 6 hex digits)
        // Use activeHexSearch as authoritative source (fallback to field value if activeHexSearch is empty)
        String hexSearchText = !activeHexSearch.isEmpty()
            ? activeHexSearch
            : (hexSearchField != null ? hexSearchField.getValue().toUpperCase().replace("#", "") : "");
        boolean hasActiveHexSearch = hexSearchText.length() == 6 && hexSearchText.matches("[0-9A-F]{6}");

        if (hasActiveHexSearch) {
            final String searchHex = hexSearchText;
            result = result.stream()
                .filter(piece -> {
                    // Calculate and cache deltaE and distance for this search
                    if (!searchHex.equals(piece.getCachedSearchHex())) {
                        double deltaE = ColorMath.calculateDeltaE(searchHex, piece.getHexcode());
                        int distance = ColorMath.calculateAbsoluteDistance(searchHex, piece.getHexcode());

                        piece.setCachedSearchHex(searchHex);
                        piece.setCachedSearchDeltaE(deltaE);
                        piece.setCachedSearchDistance(distance);
                    }

                    return piece.getCachedSearchDeltaE() <= 5.0;
                })
                .collect(Collectors.toList());

            // Automatically sort by distance when hex search is active
            sortColumn = "distance";
            sortAscending = true;
        }

        // Apply sorting
        if (sortColumn != null) {
            result.sort(getComparator(sortColumn, sortAscending));
        }

        filteredPieces = result;
        scrollOffset = 0;
    }

    private Comparator<ArmorPiece> getComparator(String column, boolean ascending) {
        Comparator<ArmorPiece> comparator = switch (column) {
            case "name" -> Comparator.comparing(p -> p.getPieceName().toLowerCase());
            case "hex" -> Comparator.comparing(ArmorPiece::getHexcode);
            case "match" -> Comparator.comparing(p ->
                p.getBestMatch() != null ? p.getBestMatch().colorName.toLowerCase() : ""
            );
            case "deltaE" -> Comparator.comparingDouble(p ->
                p.getBestMatch() != null ? p.getBestMatch().deltaE : 999.0
            );
            case "absolute" -> Comparator.comparingInt(p ->
                p.getBestMatch() != null ? p.getBestMatch().absoluteDistance : 999
            );
            case "distance" -> Comparator.comparingDouble(p ->
                p.getCachedSearchDeltaE() != null ? p.getCachedSearchDeltaE() : 999.0
            );
            default -> Comparator.comparing(ArmorPiece::getHexcode);
        };

        return ascending ? comparator : comparator.reversed();
    }

    private boolean checkFadeDye(String colorName) {
        // Delegate to ColorDatabase so the check always reflects the actual loaded fade dye list,
        // instead of a hardcoded list that can fall out of sync when new fades are added.
        return ColorDatabase.getInstance().isFadeDye(colorName);
    }

    private void updateExpandedPiece(int mouseX, int mouseY) {
        // Check if shift is held using InputUtil
        Window window = Minecraft.getInstance().getWindow();
        boolean shiftHeld = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                         || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
        isShiftHeld = shiftHeld;

        if (!shiftHeld) {
            expandedPieceUuid = null;
            return;
        }

        // Calculate which piece the mouse is over (accounting for variable row heights)
        if (mouseY < START_Y || mouseY > this.height - 40) {
            expandedPieceUuid = null;
            return;
        }

        int currentY = START_Y;
        int availableHeight = this.height - START_Y - 40;
        int maxVisibleRows = Math.max(1, availableHeight / ROW_HEIGHT);
        int endIndex = Math.min(scrollOffset + maxVisibleRows, filteredPieces.size());

        // Walk through visible pieces accounting for actual heights
        for (int i = scrollOffset; i < endIndex; i++) {
            ArmorPiece piece = filteredPieces.get(i);
            int rowHeight = getActualRowHeight(piece);

            if (mouseY >= currentY && mouseY < currentY + rowHeight) {
                expandedPieceUuid = piece.getUuid();
                return;
            }

            currentY += rowHeight;
        }

        expandedPieceUuid = null;
    }

    private int getActualRowHeight(ArmorPiece piece) {
        if (!piece.getUuid().equals(expandedPieceUuid)) {
            return ROW_HEIGHT;
        }

        // In dupe mode, always show 3 closest pieces
        if (showDupesOnly) {
            return ROW_HEIGHT + (3 * 20);
        }

        // Calculate how many match lines will be visible
        List<ArmorPiece.ColorMatch> matches = piece.getAllMatches();
        if (matches == null || matches.isEmpty()) {
            return ROW_HEIGHT;
        }

        int visibleLines = 0;
        int numMatches = Math.min(3, matches.size());

        for (int i = 0; i < numMatches; i++) {
            ArmorPiece.ColorMatch match = matches.get(i);
            boolean isFade = checkFadeDye(match.colorName);
            boolean isCustom = ClothConfig.getInstance().getCustomColors().containsKey(match.colorName);

            if (fadesMode.includes(isFade) && customsMode.includes(isCustom)) {
                visibleLines++;
            }
        }

        return ROW_HEIGHT + (visibleLines * 20);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean isOutOfBounds) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        if (profileDropdownOpen) {
            handleProfileDropdownClick(mouseX, mouseY, button);
            return true;
        }

        // Check text fields first (highest priority for clicks)
        if (searchField != null && searchField.mouseClicked(click, isOutOfBounds)) {
            this.setFocused(searchField);
            return true;
        }
        if (hexSearchField != null && hexSearchField.mouseClicked(click, isOutOfBounds)) {
            this.setFocused(hexSearchField);
            return true;
        }

        // Handle guiGraphics menu clicks
        if (contextMenu != null) {
            if (handleContextMenuClick(mouseX, mouseY, button)) {
                return true;
            }
        }

        // Right click on piece rows opens guiGraphics menu
        if (button == 1) {
            if (handleRightClick(mouseX, mouseY)) {
                return true;
            }
        }

        // Left click closes guiGraphics menu if clicking elsewhere
        if (button == 0 && contextMenu != null) {
            contextMenu = null;
            return true;
        }

        // Check scrollbar click (left click only)
        if (button == 0) {
            int availableHeight = this.height - START_Y - 40;
            int maxVisibleRows = Math.max(1, availableHeight / ROW_HEIGHT);

            if (filteredPieces.size() > maxVisibleRows) {
                int scrollbarX = this.width - 15;
                int scrollbarY = START_Y;
                int scrollbarHeight = maxVisibleRows * ROW_HEIGHT;

                if (ScrollbarRenderer.isMouseOverScrollbar(mouseX, mouseY, scrollbarX, scrollbarY, scrollbarHeight)) {
                    isDraggingScrollbar = true;
                    int maxScroll = Math.max(0, filteredPieces.size() - maxVisibleRows);
                    scrollOffset = ScrollbarRenderer.calculateScrollFromDrag(mouseY, scrollbarY, scrollbarHeight,
                        filteredPieces.size(), maxVisibleRows);
                    scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
                    return true;
                }
            }
        }

        // Check if clicking on column headers
        if (mouseY >= HEADER_Y && mouseY <= HEADER_Y + 12) {
            String clickedColumn = null;

            if (mouseX >= 20 && mouseX <= 180) {
                clickedColumn = "name";
            } else if (mouseX >= 200 && mouseX <= 285) {
                clickedColumn = "hex";
            } else if (mouseX >= 300 && mouseX <= 540) {
                clickedColumn = "match";
            } else if (mouseX >= 550 && mouseX <= 620) {
                clickedColumn = "deltaE";
            } else if (mouseX >= 630 && mouseX <= 710) {
                clickedColumn = "absolute";
            } else if (mouseX >= 710 && mouseX <= 800) {
                // Check if hex search is active before allowing distance sort
                String hexSearchText = !activeHexSearch.isEmpty() ? activeHexSearch
                    : (hexSearchField != null ? hexSearchField.getValue().replace("#", "") : "");
                if (hexSearchText.length() == 6 && hexSearchText.matches("[0-9A-Fa-f]{6}")) {
                    clickedColumn = "distance";
                }
            }

            if (clickedColumn != null) {
                if (sortColumn != null && sortColumn.equals(clickedColumn)) {
                    // Toggle direction
                    sortAscending = !sortAscending;
                } else {
                    // New column
                    sortColumn = clickedColumn;
                    sortAscending = true;
                }
                filterAndSort();
                return true;
            }
        }

        return super.mouseClicked(click, isOutOfBounds);
    }

    private void handleProfileDropdownClick(double mouseX, double mouseY, int button) {
        if (button != 0) return;

        int x = 280;
        int y = 10;
        int width = 145;
        if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY < y + 20) {
            profileDropdownOpen = false;
            this.rebuildWidgets();
            return;
        }

        List<String> profileOptions = SkyBlockProfileTracker.getProfileOptions();
        int optionsY = y + 20;
        if (mouseX >= x && mouseX <= x + width
                && mouseY >= optionsY && mouseY < optionsY + profileOptions.size() * 20) {
            int optionIndex = ((int) mouseY - optionsY) / 20;
            selectedProfileId = profileOptions.get(optionIndex);
            scrollOffset = 0;
            filterAndSort();
        }

        profileDropdownOpen = false;
        this.rebuildWidgets();
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (searchField != null && searchField.keyPressed(keyEvent)) {
            filterAndSort();
            return true;
        }
        if (hexSearchField != null && hexSearchField.keyPressed(keyEvent)) {
            filterAndSort();
            return true;
        }
        return super.keyPressed(keyEvent);
    }

    @Override
    public boolean charTyped(CharacterEvent charEvent) {
        if (searchField != null && searchField.charTyped(charEvent)) {
            filterAndSort();
            return true;
        }
        if (hexSearchField != null && hexSearchField.charTyped(charEvent)) {
            filterAndSort();
            return true;
        }
        return super.charTyped(charEvent);
    }

    private void copyDupesToClipboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("Seymour Dupes Export\n");
        sb.append("====================\n\n");

        // Group filtered pieces by hex
        Map<String, List<ArmorPiece>> hexGroups = new LinkedHashMap<>();
        for (ArmorPiece piece : filteredPieces) {
            hexGroups.computeIfAbsent(piece.getHexcode(), k -> new ArrayList<>()).add(piece);
        }

        for (Map.Entry<String, List<ArmorPiece>> entry : hexGroups.entrySet()) {
            String hex = entry.getKey();
            List<ArmorPiece> pieces = entry.getValue();
            sb.append("#").append(hex).append(" (x").append(pieces.size()).append(")\n");
            for (ArmorPiece piece : pieces) {
                String matchName = piece.getBestMatch() != null ? piece.getBestMatch().colorName : "Unknown";
                String deltaE = piece.getBestMatch() != null ? String.format("%.2f", piece.getBestMatch().deltaE) : "-";
                sb.append("  ").append(piece.getPieceName())
                  .append(" | Match: ").append(matchName)
                  .append(" | ΔE: ").append(deltaE).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Total: ").append(filteredPieces.size()).append(" dupe pieces");

        try {
            this.minecraft.keyboardHandler.setClipboard(sb.toString());
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.sendSystemMessage(
                    Component.literal("\u00a7a[Seymour] \u00a77Copied " + filteredPieces.size() + " dupe pieces to clipboard!"));
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onClose() {
        if (rememberPosition) saveCurrentState();
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
