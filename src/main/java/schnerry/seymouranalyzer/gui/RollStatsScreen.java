package schnerry.seymouranalyzer.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import schnerry.seymouranalyzer.gambling.RollStatsManager;
import schnerry.seymouranalyzer.util.ColorMath;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class RollStatsScreen extends ModScreen {
    private static final int HEADER_Y = 55;
    private static final int START_Y = 75;
    private static final int ROW_HEIGHT = 20;
    private static final int FOOTER_HEIGHT = 40;

    private final List<RollStatsManager.RollRecord> allRolls = RollStatsManager.getInstance().getLastRolls(100);
    private final List<RollStatsManager.RollRecord> filteredRolls = new ArrayList<>();
    private int scrollOffset;
    private long expandedRollTimestamp = -1L;
    private boolean isShiftHeld;
    private ContextMenu contextMenu;
    private SourceFilter sourceFilter = SourceFilter.ALL;

    private enum SourceFilter {
        ALL("All"),
        VISITOR("Visitor"),
        COMMAND("Command");

        private final String label;

        SourceFilter(String label) {
            this.label = label;
        }

        private SourceFilter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        private boolean includes(RollStatsManager.Source source) {
            return this == ALL || name().equals(source.name());
        }
    }

    private static final class ContextMenu {
        private final RollStatsManager.RollRecord roll;
        private final int x;
        private final int y;
        private static final int WIDTH = 150;
        private static final int HEIGHT = 20;

        private ContextMenu(RollStatsManager.RollRecord roll, int x, int y) {
            this.roll = roll;
            this.x = x;
            this.y = y;
        }
    }

    public RollStatsScreen(Screen parent) {
        super(Component.literal("Roll Statistics"), parent);
        filterRolls();
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
            Component.literal("Back"), button -> this.minecraft.gui.setScreen(parent))
            .bounds(20, 10, 80, 20)
            .build());
        this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
            Component.literal("Source: " + sourceFilter.label), button -> {
                sourceFilter = sourceFilter.next();
                filterRolls();
                scrollOffset = 0;
                this.rebuildWidgets();
            })
            .bounds(105, 10, 130, 20)
            .build());
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        updateExpandedRoll(mouseX, mouseY);

        graphics.centeredText(this.font, "Roll Statistics", this.width / 2, 5, 0xFFFFFFFF);
        graphics.centeredText(this.font, "Total: " + allRolls.size() + " rolls"
                + (sourceFilter == SourceFilter.ALL ? "" : " (Filtered: " + filteredRolls.size() + ")"),
            this.width / 2, 19, 0xFF888888);

        if (filteredRolls.isEmpty()) {
            graphics.centeredText(this.font, allRolls.isEmpty() ? "No rolls recorded yet." : "No rolls for this source.",
                this.width / 2, this.height / 2, 0xFF888888);
            super.render(graphics, mouseX, mouseY, delta);
            return;
        }

        graphics.text(this.font, "Source", 20, HEADER_Y, 0xFFAAAAAA);
        graphics.text(this.font, "Name", 100, HEADER_Y, 0xFFAAAAAA);
        graphics.text(this.font, "Hex", 250, HEADER_Y, 0xFFAAAAAA);
        graphics.text(this.font, "Match", 350, HEADER_Y, 0xFFAAAAAA);
        graphics.text(this.font, "Delta E", 570, HEADER_Y, 0xFFAAAAAA);
        graphics.text(this.font, "Absolute", 650, HEADER_Y, 0xFFAAAAAA);
        graphics.text(this.font, "Time", 740, HEADER_Y, 0xFFAAAAAA);
        graphics.fill(20, HEADER_Y + 12, this.width - 20, HEADER_Y + 13, 0xFF646464);

        int availableHeight = this.height - START_Y - FOOTER_HEIGHT;
        int maxVisibleRows = Math.max(1, availableHeight / ROW_HEIGHT);
        int endIndex = Math.min(scrollOffset + maxVisibleRows, filteredRolls.size());
        int currentY = START_Y;

        for (int index = scrollOffset; index < endIndex && currentY < this.height - FOOTER_HEIGHT; index++) {
            RollStatsManager.RollRecord roll = filteredRolls.get(index);
            drawRollRow(graphics, roll, currentY);
            currentY += getActualRowHeight(roll);
        }

        if (filteredRolls.size() > maxVisibleRows) {
            ScrollbarRenderer.renderVerticalScrollbar(graphics, this.width - 15, START_Y, maxVisibleRows * ROW_HEIGHT,
                scrollOffset, filteredRolls.size(), maxVisibleRows);
        }

        String footer = "Showing " + (scrollOffset + 1) + "-" + endIndex + " of " + filteredRolls.size();
        graphics.centeredText(this.font, footer, this.width / 2, this.height - 25, 0xFF888888);
        graphics.centeredText(this.font, "Hold SHIFT over a roll to show its top 3 matches", this.width / 2,
            this.height - 10, 0xFF888888);

        if (contextMenu != null) drawContextMenu(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void drawRollRow(GuiGraphicsExtractor graphics, RollStatsManager.RollRecord roll, int y) {
        int highlight = tierHighlightColor(roll.tier);
        if (highlight != 0) graphics.fill(560, y - 2, 710, y + ROW_HEIGHT - 2, highlight);

        graphics.text(this.font, roll.source == RollStatsManager.Source.VISITOR ? "Visitor" : "Command",
            20, y + 4, roll.source == RollStatsManager.Source.VISITOR ? 0xFF55FF55 : 0xFF55FFFF);
        graphics.text(this.font, truncate(roll.pieceType, 22), 100, y + 4, 0xFFFFFFFF);

        ColorMath.RGB rgb = ColorMath.hexToRgb(roll.hex.replace("#", ""));
        int color = 0xFF000000 | (rgb.r() << 16) | (rgb.g() << 8) | rgb.b();
        graphics.fill(250, y, 330, y + 16, color);
        graphics.text(this.font, roll.hex, 252, y + 4,
            ColorMath.isColorDark(roll.hex.replace("#", "")) ? 0xFFFFFFFF : 0xFF000000);

        graphics.text(this.font, truncate(roll.closestName, 28), 350, y + 4, 0xFF55FFFF);
        graphics.text(this.font, String.format(isShiftHeld ? "%.5f" : "%.2f", roll.deltaE), 570, y + 4,
            tierTextColor(roll.tier));
        graphics.text(this.font, String.valueOf(roll.absoluteDistance), 650, y + 4, 0xFFAAAAAA);
        graphics.text(this.font, new SimpleDateFormat("MM/dd HH:mm").format(new Date(roll.timestamp)), 740, y + 4,
            0xFFAAAAAA);

        if (roll.timestamp == expandedRollTimestamp) drawExpandedMatches(graphics, roll, y + ROW_HEIGHT);
    }

    private void drawExpandedMatches(GuiGraphicsExtractor graphics, RollStatsManager.RollRecord roll, int startY) {
        int y = startY;
        for (int index = 0; index < Math.min(3, roll.topMatches.size()); index++) {
            RollStatsManager.MatchRecord match = roll.topMatches.get(index);
            int highlight = tierHighlightColor(match.tier);
            if (highlight != 0) graphics.fill(560, y, 710, y + 16, highlight);

            ColorMath.RGB rgb = ColorMath.hexToRgb(match.hex.replace("#", ""));
            int color = 0xFF000000 | (rgb.r() << 16) | (rgb.g() << 8) | rgb.b();
            graphics.fill(30, y, 90, y + 14, color);
            graphics.text(this.font, (index + 1) + ". " + truncate(match.name, 35), 95, y + 3, 0xFF55FFFF);
            graphics.text(this.font, String.format(isShiftHeld ? "%.5f" : "%.2f", match.deltaE), 570, y + 3,
                tierTextColor(match.tier));
            graphics.text(this.font, String.valueOf(match.absoluteDistance), 650, y + 3, 0xFFAAAAAA);
            y += ROW_HEIGHT;
        }
    }

    private void drawContextMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = contextMenu.x;
        int y = contextMenu.y;
        int width = ContextMenu.WIDTH;
        int height = ContextMenu.HEIGHT;

        graphics.fill(x, y, x + width, y + height, 0xF0282828);
        graphics.fill(x, y, x + width, y + 2, 0xFF646464);
        graphics.fill(x, y + height - 2, x + width, y + height, 0xFF646464);
        graphics.fill(x, y, x + 2, y + height, 0xFF646464);
        graphics.fill(x + width - 2, y, x + width, y + height, 0xFF646464);
        if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height) {
            graphics.fill(x, y, x + width, y + height, 0x80505050);
        }
        graphics.text(this.font, "Search in Database", x + 5, y + 6, 0xFFFFFFFF);
    }

    private void updateExpandedRoll(int mouseX, int mouseY) {
        Window window = Minecraft.getInstance().getWindow();
        isShiftHeld = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
        if (!isShiftHeld) {
            expandedRollTimestamp = -1L;
            return;
        }

        RollStatsManager.RollRecord roll = getRollAt(mouseY);
        expandedRollTimestamp = roll == null ? -1L : roll.timestamp;
    }

    private int getActualRowHeight(RollStatsManager.RollRecord roll) {
        return roll.timestamp == expandedRollTimestamp ? ROW_HEIGHT + Math.min(3, roll.topMatches.size()) * ROW_HEIGHT
            : ROW_HEIGHT;
    }

    private RollStatsManager.RollRecord getRollAt(double mouseY) {
        if (mouseY < START_Y || mouseY > this.height - FOOTER_HEIGHT) return null;

        int availableHeight = this.height - START_Y - FOOTER_HEIGHT;
        int maxVisibleRows = Math.max(1, availableHeight / ROW_HEIGHT);
        int endIndex = Math.min(scrollOffset + maxVisibleRows, filteredRolls.size());
        int currentY = START_Y;
        for (int index = scrollOffset; index < endIndex; index++) {
            RollStatsManager.RollRecord roll = filteredRolls.get(index);
            int rowHeight = getActualRowHeight(roll);
            if (mouseY >= currentY && mouseY < currentY + rowHeight) return roll;
            currentY += rowHeight;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean isOutOfBounds) {
        if (contextMenu != null) {
            if (click.button() == 0 && isInsideContextMenu(click.x(), click.y())) {
                openDatabaseSearch(contextMenu.roll);
                contextMenu = null;
                return true;
            }
            contextMenu = null;
            return true;
        }

        if (click.button() == 1) {
            RollStatsManager.RollRecord roll = getRollAt(click.y());
            if (roll != null && roll.source == RollStatsManager.Source.VISITOR) {
                contextMenu = new ContextMenu(roll, (int) click.x(), (int) click.y());
                return true;
            }
        }
        return super.mouseClicked(click, isOutOfBounds);
    }

    private boolean isInsideContextMenu(double mouseX, double mouseY) {
        return mouseX >= contextMenu.x && mouseX <= contextMenu.x + ContextMenu.WIDTH
            && mouseY >= contextMenu.y && mouseY <= contextMenu.y + ContextMenu.HEIGHT;
    }

    private void openDatabaseSearch(RollStatsManager.RollRecord roll) {
        DatabaseScreen database = new DatabaseScreen(this);
        database.setHexSearch(roll.hex);
        this.minecraft.gui.setScreen(database);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int availableHeight = this.height - START_Y - FOOTER_HEIGHT;
        int maxVisibleRows = Math.max(1, availableHeight / ROW_HEIGHT);
        int maxScroll = Math.max(0, filteredRolls.size() - maxVisibleRows);
        if (verticalAmount > 0) scrollOffset = Math.max(0, scrollOffset - 1);
        if (verticalAmount < 0) scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        return true;
    }

    private static String truncate(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength - 3) + "..." : text;
    }

    private void filterRolls() {
        filteredRolls.clear();
        allRolls.stream()
            .filter(roll -> sourceFilter.includes(roll.source))
            .forEach(filteredRolls::add);
    }

    private static int tierTextColor(String tier) {
        if (tier == null) return 0xFFAAAAAA;
        if (tier.contains("T0")) return tier.startsWith("Fade") ? 0xFF5555FF : 0xFFFF5555;
        if (tier.contains("T1")) return tier.startsWith("Fade") ? 0xFF55FFFF : 0xFFFF55FF;
        if (tier.contains("T2")) return 0xFFFFAA00;
        if ("1/1".equals(tier)) return 0xFFFFD700;
        return 0xFFAAAAAA;
    }

    private static int tierHighlightColor(String tier) {
        if (tier == null) return 0;
        if (tier.startsWith("Fade T0")) return 0x480000FF;
        if (tier.startsWith("Fade T1")) return 0x4887CEFA;
        if (tier.startsWith("Fade T2")) return 0x48FFFF00;
        if ("T0".equals(tier)) return 0x48FF0000;
        if ("T1".equals(tier)) return 0x48FF69B4;
        if ("T2".equals(tier)) return 0x48FFA500;
        return 0;
    }
}
