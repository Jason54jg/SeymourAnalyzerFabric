package schnerry.seymouranalyzer.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import schnerry.seymouranalyzer.analyzer.PatternDetector;
import schnerry.seymouranalyzer.data.ArmorPiece;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.util.ColorMath;

import java.util.*;

/**
 * Pattern Matches GUI - shows all pieces with special hex patterns
 */
public class PatternMatchesScreen extends ModScreen {
    private List<PatternMatchEntry> patternMatches = new ArrayList<>();
    private int scrollOffset = 0;
    private Map<String, Integer> patternCounts = new LinkedHashMap<>();

    // Scrollbar dragging
    private boolean isDraggingScrollbar = false;

    // For row click handling
    private List<PatternRow> cachedRows = new ArrayList<>();

    // guiGraphics menu state
    private ContextMenu contextMenu = null;

    private static class ContextMenu {
        PatternRow row;
        int x, y;
        int width = 120;
        int height = 20;
    }

    private static class PatternMatchEntry {
        String patternType;
        List<ArmorPiece> pieces = new ArrayList<>();
    }

    public PatternMatchesScreen(Screen parent) {
        super(Component.literal("Pattern Matches"), parent);
        loadPatternMatches();
    }

    private void loadPatternMatches() {
        Map<String, PatternMatchEntry> patternMap = new HashMap<>();
        Map<String, ArmorPiece> collection = CollectionManager.getInstance().getCollection();
        PatternDetector detector = PatternDetector.getInstance();

        for (ArmorPiece piece : collection.values()) {
            String pattern = detector.detectPattern(piece.getHexcode());
            if (pattern != null) {
                PatternMatchEntry entry = patternMap.computeIfAbsent(pattern, k -> {
                    PatternMatchEntry e = new PatternMatchEntry();
                    e.patternType = k;
                    return e;
                });
                entry.pieces.add(piece);
            }
        }

        patternMatches = new ArrayList<>(patternMap.values());

        // Sort: AxBxCx patterns by hex character, then others alphabetically
        patternMatches.sort((a, b) -> {
            boolean aIsAxBxCx = a.patternType.startsWith("axbxcx_");
            boolean bIsAxBxCx = b.patternType.startsWith("axbxcx_");

            if (aIsAxBxCx && bIsAxBxCx) {
                return a.patternType.compareTo(b.patternType);
            } else if (aIsAxBxCx) {
                return -1;
            } else if (bIsAxBxCx) {
                return 1;
            } else {
                return a.patternType.compareTo(b.patternType);
            }
        });

        // Calculate counts
        for (PatternMatchEntry entry : patternMatches) {
            patternCounts.put(entry.patternType, entry.pieces.size());
        }
    }

    @Override
    protected void init() {
        super.init();

        // Back button
        Button backBtn = Button.builder(Component.literal("← Back to Database"), button -> {
            this.minecraft.gui.setScreen(parent);
        }).bounds(20, 10, 150, 20).build();
        this.addRenderableWidget(backBtn);

        // Copy All button
        Button copyBtn = Button.builder(Component.literal("§eCopy All"),
            button -> copyAllToClipboard())
            .bounds(this.width - 100, 10, 80, 20).build();
        this.addRenderableWidget(copyBtn);
    }

    @Override
    public void render(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        // Don't fill background - let text render properly

        // Title
        guiGraphics.centeredText(this.font, "§l§nPattern Matches", this.width / 2, 10, 0xFFFFFFFF);

        // Count
        int totalPieces = patternMatches.stream().mapToInt(e -> e.pieces.size()).sum();
        String info = "§7Total: §e" + patternMatches.size() + " §7pattern types | §e" + totalPieces + " §7pieces";
        guiGraphics.centeredText(this.font, info, this.width / 2, 30, 0xFFFFFFFF);

        if (patternMatches.isEmpty()) {
            guiGraphics.centeredText(this.font, "§7No pattern matches found!", this.width / 2, this.height / 2, 0xFFFFFFFF);
        } else {
            drawPatternList(guiGraphics);
            drawPatternCounter(guiGraphics);
        }

        // Draw guiGraphics menu on top if open
        if (contextMenu != null) {
            drawContextMenu(guiGraphics);
        }

        super.render(guiGraphics, mouseX, mouseY, delta);
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

    private void drawPatternList(GuiGraphicsExtractor guiGraphics) {
        int startY = 55;
        int rowHeight = 20;

        // Headers
        guiGraphics.text(this.font, "§l§7Pattern Type", 20, startY, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§l§7Description", 180, startY, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§l§7Piece Name", 370, startY, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§l§7Hex", 580, startY, 0xFFFFFFFF);

        // Separator
        guiGraphics.fill(20, startY + 12, this.width - 20, startY + 13, 0xFF555555);

        // Build flat list of rows
        List<PatternRow> rows = new ArrayList<>();
        for (PatternMatchEntry entry : patternMatches) {
            for (int i = 0; i < entry.pieces.size(); i++) {
                PatternRow row = new PatternRow();
                row.patternType = entry.patternType;
                row.piece = entry.pieces.get(i);
                row.isFirst = (i == 0);
                row.description = getPatternDescription(entry.patternType, row.piece.getHexcode());
                rows.add(row);
            }
        }

        // Cache visible rows for click detection
        cachedRows.clear();
        int maxVisible = (this.height - 100) / rowHeight;
        int endIndex = Math.min(scrollOffset + maxVisible, rows.size());

        for (int i = scrollOffset; i < endIndex; i++) {
            PatternRow row = rows.get(i);
            int y = startY + 20 + ((i - scrollOffset) * rowHeight);

            // Add click area bounds to the row
            row.clickY = y;
            row.clickHeight = rowHeight;
            cachedRows.add(row);

            drawPatternRow(guiGraphics, row, y);
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

    private void drawPatternRow(GuiGraphicsExtractor guiGraphics, PatternRow row, int y) {
        // Pattern type (only on first piece)
        if (row.isFirst) {
            String patternName = getPatternName(row.patternType);
            guiGraphics.text(this.font, "§5§l" + patternName, 20, y, 0xFFFFFFFF);
        }

        // Description
        guiGraphics.text(this.font, "§f" + row.description, 180, y, 0xFFFFFFFF);

        // Piece name
        String name = row.piece.getPieceName();
        if (name.length() > 30) {
            name = name.substring(0, 30) + "...";
        }
        guiGraphics.text(this.font, "§7" + name, 370, y, 0xFFFFFFFF);

        // Hex box
        ColorMath.RGB rgb = ColorMath.hexToRgb(row.piece.getHexcode());
        int color = 0xFF000000 | (rgb.r() << 16) | (rgb.g() << 8) | rgb.b();
        guiGraphics.fill(580, y - 2, 665, y + 12, color);

        // Hex text - with alpha channel
        String hexText = "#" + row.piece.getHexcode();
        if (ColorMath.isColorDark(row.piece.getHexcode())) {
            guiGraphics.text(this.font, hexText, 582, y, 0xFFFFFFFF);
        } else {
            guiGraphics.text(this.font, hexText, 582, y, 0xFF000000);
        }
    }

    private void drawPatternCounter(GuiGraphicsExtractor guiGraphics) {
        int boxX = this.width - 170;
        int boxY = 70;
        int boxWidth = 150;

        int rowCount = patternCounts.size();
        int boxHeight = (rowCount * 12) + 20;

        // Background
        guiGraphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xCC282828);

        // Border
        guiGraphics.fill(boxX, boxY, boxX + boxWidth, boxY + 2, 0xFF646464);
        guiGraphics.fill(boxX, boxY + boxHeight - 2, boxX + boxWidth, boxY + boxHeight, 0xFF646464);
        guiGraphics.fill(boxX, boxY, boxX + 2, boxY + boxHeight, 0xFF646464);
        guiGraphics.fill(boxX + boxWidth - 2, boxY, boxX + boxWidth, boxY + boxHeight, 0xFF646464);

        // Title
        guiGraphics.text(this.font, "§l§7Pattern Counts", boxX + 5, boxY + 5, 0xFFFFFFFF);

        int currentY = boxY + 18;
        for (Map.Entry<String, Integer> entry : patternCounts.entrySet()) {
            String label = getPatternName(entry.getKey());
            String text = "§5" + label + ": §f" + entry.getValue();

            // Special rendering for AxBxCx with colored background
            if (entry.getKey().startsWith("axbxcx_")) {
                char c = entry.getKey().charAt(7);
                String hex = String.valueOf(c).repeat(6);
                ColorMath.RGB rgb = ColorMath.hexToRgb(hex);
                int color = 0xFF000000 | (rgb.r() << 16) | (rgb.g() << 8) | rgb.b();
                guiGraphics.fill(boxX + 10, currentY, boxX + 55, currentY + 10, color);

                boolean isDark = ColorMath.isColorDark(hex);
                guiGraphics.text(this.font, label + ":", boxX + 12, currentY + 1, isDark ? 0xFFFFFFFF : 0xFF000000);
                guiGraphics.text(this.font, "§f" + entry.getValue(), boxX + 60, currentY + 1, 0xFFFFFFFF);
            } else {
                guiGraphics.text(this.font, text, boxX + 10, currentY, 0xFFFFFFFF);
            }

            currentY += 12;
        }
    }

    private String getPatternName(String patternType) {
        switch (patternType) {
            case "paired" -> {
                return "PAIRED";
            }
            case "repeating" -> {
                return "REPEATING";
            }
            case "palindrome" -> {
                return "PALINDROME";
            }
        }
        if (patternType.startsWith("axbxcx_")) {
            char c = patternType.charAt(7);
            return c + "x" + c + "x" + c + "x";
        }
        return "AxBxCx";
    }

    private String getPatternDescription(String patternType, String hex) {
        switch (patternType) {
            case "paired" -> {
                return "AABBCC";
            }
            case "repeating" -> {
                return "ABCABC";
            }
            case "palindrome" -> {
                return "ABCCBA";
            }
        }
        if (patternType.startsWith("axbxcx_")) {
            char c = patternType.charAt(7);
            return c + "x" + c + "x" + c + "x";
        }
        return "AxBxCx";
    }

    private static class PatternRow {
        String patternType;
        String description;
        ArmorPiece piece;
        boolean isFirst;

        // Click detection bounds
        int clickY;
        int clickHeight;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean isOutOfBounds) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        // Handle guiGraphics menu clicks first
        if (contextMenu != null) {
            if (handleContextMenuClick(mouseX, mouseY, button)) {
                return true;
            }
        }

        // Check scrollbar click (left click only)
        if (button == 0) {
            int totalRows = patternMatches.stream().mapToInt(e -> e.pieces.size()).sum();
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
            for (PatternRow row : cachedRows) {
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

    private void showContextMenu(PatternRow row, int x, int y) {
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
        sb.append("Seymour Pattern Matches Export\n");
        sb.append("==============================\n\n");

        int totalPieces = 0;
        for (PatternMatchEntry entry : patternMatches) {
            String patternName = getPatternName(entry.patternType);
            sb.append("Pattern: ").append(patternName).append(" (").append(entry.pieces.size()).append(" pieces)\n");
            for (ArmorPiece piece : entry.pieces) {
                String desc = getPatternDescription(entry.patternType, piece.getHexcode());
                sb.append("  ").append(piece.getPieceName())
                  .append(" | #").append(piece.getHexcode())
                  .append(" | ").append(desc).append("\n");
                totalPieces++;
            }
            sb.append("\n");
        }

        sb.append("Total: ").append(patternMatches.size()).append(" patterns, ").append(totalPieces).append(" pieces");

        copyToClipboard(sb.toString());
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.sendSystemMessage(
                Component.literal("\u00a7a[Seymour] \u00a77Copied " + patternMatches.size() + " pattern matches to clipboard!"));
        }
    }

    private void copyToClipboard(String text) {
        try {
            this.minecraft.keyboardHandler.setClipboard(text);
        } catch (Exception e) {
            // Ignore clipboard errors
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Count total rows
        int totalRows = patternMatches.stream().mapToInt(e -> e.pieces.size()).sum();
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
            int totalRows = patternMatches.stream().mapToInt(e -> e.pieces.size()).sum();
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

