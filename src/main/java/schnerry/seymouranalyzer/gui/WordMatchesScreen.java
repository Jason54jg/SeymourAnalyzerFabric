package schnerry.seymouranalyzer.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import schnerry.seymouranalyzer.data.ArmorPiece;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.util.ColorMath;
import schnerry.seymouranalyzer.util.SkyBlockProfileTracker;

import java.util.*;

/**
 * Word Matches GUI - shows all pieces with word patterns
 */
public class WordMatchesScreen extends ModScreen {
    private List<WordMatchEntry> wordMatches = new ArrayList<>();
    private int scrollOffset = 0;
    private String selectedProfileId;
    private boolean profileDropdownOpen;

    // Scrollbar dragging
    private boolean isDraggingScrollbar = false;

    // For row click handling
    private final List<WordRow> cachedRows = new ArrayList<>();

    // guiGraphics menu state
    private ContextMenu contextMenu = null;

    private static class ContextMenu {
        WordRow row;
        int x, y;
        int width = 120;
        int height = 20;
    }

    private static class WordMatchEntry {
        String word;
        List<ArmorPiece> pieces = new ArrayList<>();
    }

    public WordMatchesScreen(Screen parent) {
        this(parent, SkyBlockProfileTracker.ALL_PROFILES);
    }

    public WordMatchesScreen(Screen parent, String selectedProfileId) {
        super(Component.literal("Word Matches"), parent);
        this.selectedProfileId = selectedProfileId;
        loadWordMatches();
    }

    private void loadWordMatches() {
        Map<String, WordMatchEntry> wordMap = new HashMap<>();
        Map<String, ArmorPiece> collection = CollectionManager.getInstance().getCollection();

        for (ArmorPiece piece : collection.values()) {
            if (!SkyBlockProfileTracker.ALL_PROFILES.equals(selectedProfileId)
                    && !selectedProfileId.equals(piece.getProfileId())) {
                continue;
            }
            String wordMatch = piece.getWordMatch();
            if (wordMatch != null && !wordMatch.isEmpty()) {
                WordMatchEntry entry = wordMap.computeIfAbsent(wordMatch, k -> {
                    WordMatchEntry e = new WordMatchEntry();
                    e.word = k;
                    return e;
                });
                entry.pieces.add(piece);
            }
        }

        wordMatches = new ArrayList<>(wordMap.values());
        wordMatches.sort(Comparator.comparing(e -> e.word));
    }

    @Override
    protected void init() {
        super.init();

        // Back button
        Button backBtn = Button.builder(Component.literal("← Back to Database"), button -> {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(parent);
            }
        }).bounds(20, 10, 150, 20).build();
        this.addRenderableWidget(backBtn);

        Button profileSelector = Button.builder(
            Component.literal("Profile: " + SkyBlockProfileTracker.getProfileLabel(selectedProfileId)),
            button -> {
                profileDropdownOpen = !profileDropdownOpen;
                this.rebuildWidgets();
            })
            .bounds(175, 10, 145, 20)
            .build();
        this.addRenderableWidget(profileSelector);

        // Copy All button
        Button copyBtn = Button.builder(Component.literal("§eCopy All"),
            button -> copyAllToClipboard())
            .bounds(this.width - 100, 10, 80, 20).build();
        this.addRenderableWidget(copyBtn);
        addProfileDropdownOptions();
    }

    private void addProfileDropdownOptions() {
        if (!profileDropdownOpen) return;
        for (Button option : ProfileDropdown.createOptions(175, 30, 145, selectedProfileId, profileId -> {
            selectedProfileId = profileId;
            profileDropdownOpen = false;
            scrollOffset = 0;
            contextMenu = null;
            loadWordMatches();
            this.rebuildWidgets();
        })) {
            this.addRenderableWidget(option);
        }
    }

    @Override
    public void render(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        // Don't fill background - let text render properly

        // Title
        guiGraphics.centeredText(this.font, "§l§nWord Matches", this.width / 2, 10, 0xFFFFFFFF);

        // Count
        int totalPieces = wordMatches.stream().mapToInt(e -> e.pieces.size()).sum();
        String info = "§7Found §e" + wordMatches.size() + " §7words with §e" + totalPieces + " §7pieces total";
        guiGraphics.centeredText(this.font, info, this.width / 2, 30, 0xFFFFFFFF);

        if (wordMatches.isEmpty()) {
            guiGraphics.centeredText(this.font, "§7No word matches found!", this.width / 2, this.height / 2, 0xFFFFFFFF);
            guiGraphics.centeredText(this.font, "§7Use §e/seymour word add <word> <pattern> §7to add words", this.width / 2, this.height / 2 + 15, 0xFFFFFFFF);
        } else {
            drawWordList(guiGraphics);
        }

        // Draw guiGraphics menu on top if open
        if (contextMenu != null) {
            drawContextMenu(guiGraphics);
        }

        super.render(guiGraphics, mouseX, mouseY, delta);
        if (profileDropdownOpen) {
            ProfileDropdown.renderModal(guiGraphics, this.font, this.width, this.height, 175, 10, 145, 20, selectedProfileId);
        }
    }

    private void drawContextMenu(GuiGraphicsExtractor guiGraphics) {
        int x = contextMenu.x;
        int y = contextMenu.y;
        int w = contextMenu.width;
        int h = contextMenu.height;

        // Background
        guiGraphics.fill(x, y, x + w, y + h, 0xF0282828);

        // Border
        guiGraphics.fill(x, y, x + w, y + 2, 0xFF646464);
        guiGraphics.fill(x, y + h - 2, x + w, y + h, 0xFF646464);
        guiGraphics.fill(x, y, x + 2, y + h, 0xFF646464);
        guiGraphics.fill(x + w - 2, y, x + w, y + h, 0xFF646464);

        // Option text
        guiGraphics.text(this.font, "Find in Database", x + 5, y + 6, 0xFFFFFFFF);
    }

    private void drawWordList(GuiGraphicsExtractor guiGraphics) {
        int startY = 55;
        int rowHeight = 20;

        // Headers
        guiGraphics.text(this.font, "§l§7Word", 20, startY, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§l§7Piece Name", 150, startY, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§l§7Hex", 400, startY, 0xFFFFFFFF);

        // Separator
        guiGraphics.fill(20, startY + 12, this.width - 20, startY + 13, 0xFF555555);

        // Build flat list of rows
        List<WordRow> rows = new ArrayList<>();
        for (WordMatchEntry entry : wordMatches) {
            for (int i = 0; i < entry.pieces.size(); i++) {
                WordRow row = new WordRow();
                row.word = entry.word;
                row.piece = entry.pieces.get(i);
                row.isFirst = (i == 0);
                rows.add(row);
            }
        }

        int maxVisible = (this.height - 100) / rowHeight;
        int endIndex = Math.min(scrollOffset + maxVisible, rows.size());

        // Cache visible rows for click handling
        cachedRows.clear();

        for (int i = scrollOffset; i < endIndex; i++) {
            WordRow row = rows.get(i);
            int y = startY + 20 + ((i - scrollOffset) * rowHeight);
            row.clickY = y;
            row.clickHeight = rowHeight;
            cachedRows.add(row);
            drawWordRow(guiGraphics, row, y);
        }

        // Draw scrollbar if needed
        if (rows.size() > maxVisible) {
            int scrollbarX = this.width - 15;
            int scrollbarY = startY + 20;
            int scrollbarHeight = maxVisible * rowHeight;
            ScrollbarRenderer.renderVerticalScrollbar(guiGraphics, scrollbarX, scrollbarY, scrollbarHeight,
                scrollOffset, rows.size(), maxVisible);
        }

        // Footer
        String footer = "§7Showing " + (scrollOffset + 1) + "-" + endIndex + " of " + rows.size();
        guiGraphics.centeredText(this.font, footer, this.width / 2, this.height - 25, 0xFFFFFFFF);
    }

    private void drawWordRow(GuiGraphicsExtractor guiGraphics, WordRow row, int y) {
        // Word (only on first piece)
        if (row.isFirst) {
            guiGraphics.text(this.font, "§d§l" + row.word, 20, y, 0xFFFFFFFF);
        }

        // Piece name
        String name = row.piece.getPieceName();
        if (name.length() > 35) {
            name = name.substring(0, 35) + "...";
        }
        guiGraphics.text(this.font, "§7" + name, 150, y, 0xFFFFFFFF);

        // Hex box
        ColorMath.RGB rgb = ColorMath.hexToRgb(row.piece.getHexcode());
        int color = 0xFF000000 | (rgb.r() << 16) | (rgb.g() << 8) | rgb.b();
        guiGraphics.fill(400, y - 2, 485, y + 12, color);

        // Hex text - with alpha channel
        String hexText = "#" + row.piece.getHexcode();
        if (ColorMath.isColorDark(row.piece.getHexcode())) {
            guiGraphics.text(this.font, hexText, 402, y, 0xFFFFFFFF);
        } else {
            guiGraphics.text(this.font, hexText, 402, y, 0xFF000000);
        }
    }

    private static class WordRow {
        String word;
        ArmorPiece piece;
        boolean isFirst;

        int clickY;
        int clickHeight;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Count total rows
        int totalRows = wordMatches.stream().mapToInt(e -> e.pieces.size()).sum();
        int maxScroll = Math.max(0, totalRows - 20);

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
            int totalRows = wordMatches.stream().mapToInt(e -> e.pieces.size()).sum();
            int maxVisible = (this.height - 100) / 20;
            int startY = 55;
            int rowHeight = 20;
            int scrollbarY = startY + 20;
            int scrollbarHeight = maxVisible * rowHeight;

            int maxScroll = Math.max(0, totalRows - maxVisible);
            scrollOffset = ScrollbarRenderer.calculateScrollFromDrag(click.y(), scrollbarY, scrollbarHeight,
                totalRows, maxVisible);
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

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean isOutOfBounds) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        if (profileDropdownOpen) {
            if (button == 0) {
                ProfileDropdown.getSelectedOption(mouseX, mouseY, 175, 10, 145, 20).ifPresent(profileId -> {
                    selectedProfileId = profileId;
                    scrollOffset = 0;
                    contextMenu = null;
                    loadWordMatches();
                });
            }
            profileDropdownOpen = false;
            this.rebuildWidgets();
            return true;
        }

        // Handle guiGraphics menu clicks first
        if (contextMenu != null) {
            if (handleContextMenuClick(mouseX, mouseY, button)) {
                return true;
            }
        }

        // Check scrollbar click (left click only)
        if (button == 0) {
            int totalRows = wordMatches.stream().mapToInt(e -> e.pieces.size()).sum();
            int maxVisible = (this.height - 100) / 20;

            if (totalRows > maxVisible) {
                int startY = 55;
                int rowHeight = 20;
                int scrollbarX = this.width - 15;
                int scrollbarY = startY + 20;
                int scrollbarHeight = maxVisible * rowHeight;

                if (ScrollbarRenderer.isMouseOverScrollbar(mouseX, mouseY, scrollbarX, scrollbarY, scrollbarHeight)) {
                    isDraggingScrollbar = true;
                    int maxScroll = Math.max(0, totalRows - maxVisible);
                    scrollOffset = ScrollbarRenderer.calculateScrollFromDrag(mouseY, scrollbarY, scrollbarHeight,
                        totalRows, maxVisible);
                    scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
                    return true;
                }
            }
        }

        // Right click on rows opens guiGraphics menu
        if (button == 1) {
            for (WordRow row : cachedRows) {
                if (mouseY >= row.clickY && mouseY <= row.clickY + row.clickHeight) {
                    showContextMenu(row, (int) mouseX, (int) mouseY);
                    return true;
                }
            }
        }

        // Left click closes guiGraphics menu if clicking elsewhere
        if (button == 0 && contextMenu != null) {
            contextMenu = null;
            return true;
        }

        return super.mouseClicked(click, isOutOfBounds);
    }

    private void showContextMenu(WordRow row, int x, int y) {
        contextMenu = new ContextMenu();
        contextMenu.row = row;
        contextMenu.x = x;
        contextMenu.y = y;
    }

    private boolean handleContextMenuClick(double mouseX, double mouseY, int button) {
        if (contextMenu == null) return false;

        int x = contextMenu.x;
        int y = contextMenu.y;
        int w = contextMenu.width;
        int h = contextMenu.height;

        // Check if click is inside menu
        if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h) {
            if (button == 0) {
                // "Find in Database" - open database with hex search
                String hex = contextMenu.row.piece.getHexcode();
                contextMenu = null;

                // Open database screen with hex search pre-filled
                if (minecraft != null) {
                    DatabaseScreen dbScreen = new DatabaseScreen();
                    dbScreen.setHexSearch(hex);
                    minecraft.gui.setScreen(dbScreen);
                }
                return true;
            }
        }

        // Click outside closes menu
        contextMenu = null;
        return false;
    }

    private void copyAllToClipboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("Seymour Word Matches Export\n");
        sb.append("===========================\n\n");

        int totalPieces = 0;
        for (WordMatchEntry entry : wordMatches) {
            sb.append("Word: ").append(entry.word).append(" (").append(entry.pieces.size()).append(" pieces)\n");
            for (ArmorPiece piece : entry.pieces) {
                sb.append("  ").append(piece.getPieceName())
                  .append(" | #").append(piece.getHexcode()).append("\n");
                totalPieces++;
            }
            sb.append("\n");
        }

        sb.append("Total: ").append(wordMatches.size()).append(" words, ").append(totalPieces).append(" pieces");

        copyToClipboard(sb.toString());
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.sendSystemMessage(
                Component.literal("\u00a7a[Seymour] \u00a77Copied " + wordMatches.size() + " word matches to clipboard!"));
        }
    }

    private void copyToClipboard(String text) {
        try {
            this.minecraft.keyboardHandler.setClipboard(text);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
