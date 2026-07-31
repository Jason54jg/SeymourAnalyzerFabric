package schnerry.seymouranalyzer.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import schnerry.seymouranalyzer.gui.ModScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom GUI screen for editing match priorities with drag-and-drop reordering
 */
public class PriorityEditorScreen extends ModScreen {
    private final List<MatchPriority> priorities;

    private int draggedIndex = -1;
    private int hoveredIndex = -1;
    private double dragStartY = 0;
    private double currentDragY = 0;

    private static final int ITEM_HEIGHT = 30;
    private static final int ITEM_SPACING = 2;
    private static final int LIST_WIDTH = 400;
    private static final int LIST_START_Y = 60;
    private static final int SWATCH_SIZE = 16;

    public PriorityEditorScreen(Screen parent) {
        super(Component.literal("Match Priority Editor"), parent);
        this.priorities = new ArrayList<>(ClothConfig.getInstance().getMatchPriorities());
    }

    @Override
    protected void init() {
        super.init();

        // Done button
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> {
            ClothConfig.getInstance().setMatchPriorities(priorities);
            ClothConfig.getInstance().save();
            this.onClose();
        }).bounds(this.width / 2 - 155, this.height - 28, 150, 20).build());

        // Cancel button
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> this.onClose())
            .bounds(this.width / 2 + 5, this.height - 28, 150, 20).build());

        // Reset to defaults button – resets both priority order AND all highlight colors
        this.addRenderableWidget(Button.builder(Component.literal("Reset All to Defaults"), button -> {
            priorities.clear();
            priorities.addAll(ClothConfig.getDefaultMatchPriorities());
            for (MatchPriority p : MatchPriority.values()) {
                ClothConfig.getInstance().resetHighlightColor(p);
            }
            ClothConfig.getInstance().save();
        }).bounds(this.width / 2 - 90, this.height - 52, 180, 20).build());
    }

    @Override
    public void render(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        // Title
        guiGraphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);

        // Instructions
        guiGraphics.centeredText(this.font,
            Component.literal("Drag to reorder • Higher = Priority for highlights"),
            this.width / 2, 35, 0xFFAAAAAA);

        // Calculate list position
        int listX = (this.width - LIST_WIDTH) / 2;
        int listY = LIST_START_Y;

        // Update hovered index
        hoveredIndex = -1;
        if (draggedIndex == -1) {
            for (int i = 0; i < priorities.size(); i++) {
                int itemY = listY + i * (ITEM_HEIGHT + ITEM_SPACING);
                if (mouseX >= listX && mouseX <= listX + LIST_WIDTH &&
                    mouseY >= itemY && mouseY <= itemY + ITEM_HEIGHT) {
                    hoveredIndex = i;
                    break;
                }
            }
        }

        // Render priority items
        for (int i = 0; i < priorities.size(); i++) {
            if (i == draggedIndex) continue; // Skip the dragged item for now

            int itemY = listY + i * (ITEM_HEIGHT + ITEM_SPACING);
            renderPriorityItem(guiGraphics, priorities.get(i), listX, itemY, i);
        }

        // Render dragged item on top
        if (draggedIndex >= 0 && draggedIndex < priorities.size()) {
            int dragY = (int)(LIST_START_Y + draggedIndex * (ITEM_HEIGHT + ITEM_SPACING) + (currentDragY - dragStartY));
            renderPriorityItem(guiGraphics, priorities.get(draggedIndex), listX, dragY, draggedIndex);
        }

        super.render(guiGraphics, mouseX, mouseY, delta);
    }

    private void renderPriorityItem(GuiGraphicsExtractor guiGraphics, MatchPriority priority, int x, int y, int index) {
        boolean isHovered = index == hoveredIndex || index == draggedIndex;
        boolean isDragged = index == draggedIndex;

        // Background
        int bgColor = isDragged ? 0x88444444 : (isHovered ? 0x66333333 : 0x44222222);
        guiGraphics.fill(x, y, x + LIST_WIDTH, y + ITEM_HEIGHT, bgColor);

        // Border - draw manually since drawBorder was removed in 1.21.10
        int borderColor = isDragged ? 0xFFFFFFFF : (isHovered ? 0xFF888888 : 0xFF444444);
        guiGraphics.fill(x, y, x + LIST_WIDTH, y + 1, borderColor);
        guiGraphics.fill(x, y + ITEM_HEIGHT - 1, x + LIST_WIDTH, y + ITEM_HEIGHT, borderColor);
        guiGraphics.fill(x, y, x + 1, y + ITEM_HEIGHT, borderColor);
        guiGraphics.fill(x + LIST_WIDTH - 1, y, x + LIST_WIDTH, y + ITEM_HEIGHT, borderColor);

        // Priority number
        String priorityNum = "#" + (index + 1);
        guiGraphics.text(this.font, priorityNum, x + 8, y + 6, 0xFFFFAA00);

        // Display name
        guiGraphics.text(this.font, priority.getDisplayName(), x + 40, y + 6, 0xFFFFFFFF);

        // Description
        guiGraphics.text(this.font, priority.getDescription(), x + 40, y + 17, 0xFF888888);

        // ── Color swatch ─────────────────────────────────────────────────────
        int swatchX = getSwatchX(x);
        int swatchY = y + (ITEM_HEIGHT - SWATCH_SIZE) / 2;
        int color = ClothConfig.getInstance().getHighlightColor(priority);
        // Checkerboard background (to show alpha)
        for (int cx = 0; cx < SWATCH_SIZE; cx += 4) {
            for (int cy = 0; cy < SWATCH_SIZE; cy += 4) {
                boolean checker = ((cx / 4) + (cy / 4)) % 2 == 0;
                guiGraphics.fill(swatchX + cx, swatchY + cy,
                        swatchX + cx + 4, swatchY + cy + 4,
                        checker ? 0xFFAAAAAA : 0xFF666666);
            }
        }
        guiGraphics.fill(swatchX, swatchY, swatchX + SWATCH_SIZE, swatchY + SWATCH_SIZE, color);
        // Swatch border
        guiGraphics.fill(swatchX - 1,             swatchY - 1,             swatchX + SWATCH_SIZE + 1, swatchY,                  0xFFCCCCCC);
        guiGraphics.fill(swatchX - 1,             swatchY + SWATCH_SIZE,   swatchX + SWATCH_SIZE + 1, swatchY + SWATCH_SIZE + 1, 0xFFCCCCCC);
        guiGraphics.fill(swatchX - 1,             swatchY - 1,             swatchX,                   swatchY + SWATCH_SIZE + 1, 0xFFCCCCCC);
        guiGraphics.fill(swatchX + SWATCH_SIZE,   swatchY - 1,             swatchX + SWATCH_SIZE + 1, swatchY + SWATCH_SIZE + 1, 0xFFCCCCCC);

        // Drag handle (three lines)
        //noinspection ConstantValue - isDragged is evaluated at render time and can be true
        if (isHovered || isDragged) {
            int handleX = x + LIST_WIDTH - 20;
            int handleY = y + 10;
            for (int i = 0; i < 3; i++) {
                guiGraphics.fill(handleX, handleY + i * 3, handleX + 12, handleY + i * 3 + 1, 0xFF888888);
            }
        }
    }

    /** Returns the left X of the color swatch for row at list origin x. */
    private int getSwatchX(int rowX) {
        return rowX + LIST_WIDTH - 20 - SWATCH_SIZE - 8;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean isOutOfBounds) {
        if (click.button() == 0) { // Left click
            int listX = (this.width - LIST_WIDTH) / 2;

            for (int i = 0; i < priorities.size(); i++) {
                int itemY = LIST_START_Y + i * (ITEM_HEIGHT + ITEM_SPACING);
                if (click.x() >= listX && click.x() <= listX + LIST_WIDTH &&
                    click.y() >= itemY && click.y() <= itemY + ITEM_HEIGHT) {

                    // Check if click is on the color swatch
                    int swatchX = getSwatchX(listX);
                    int swatchY = itemY + (ITEM_HEIGHT - SWATCH_SIZE) / 2;
                    if (click.x() >= swatchX && click.x() <= swatchX + SWATCH_SIZE &&
                        click.y() >= swatchY && click.y() <= swatchY + SWATCH_SIZE) {
                        // Open color picker for this priority
                        if (this.minecraft != null) {
                            this.minecraft.gui.setScreen(new ColorPickerScreen(this, priorities.get(i)));
                        }
                        return true;
                    }

                    // Otherwise start drag
                    draggedIndex = i;
                    dragStartY = click.y();
                    currentDragY = click.y();
                    return true;
                }
            }
        }

        return super.mouseClicked(click, isOutOfBounds);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (click.button() == 0 && draggedIndex >= 0) {
            // Calculate drop position
            int listY = LIST_START_Y;
            int itemTotalHeight = ITEM_HEIGHT + ITEM_SPACING;
            int draggedItemY = (int)(listY + draggedIndex * itemTotalHeight + (currentDragY - dragStartY));
            int draggedItemCenter = draggedItemY + ITEM_HEIGHT / 2;

            // Find new index based on center position
            int newIndex = Math.max(0, Math.min(priorities.size() - 1,
                (draggedItemCenter - listY + itemTotalHeight / 2) / itemTotalHeight));

            // Reorder the list
            if (newIndex != draggedIndex) {
                MatchPriority item = priorities.remove(draggedIndex);
                priorities.add(newIndex, item);
            }

            draggedIndex = -1;
            return true;
        }

        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (click.button() == 0 && draggedIndex >= 0) {
            currentDragY = click.y();
            return true;
        }

        return super.mouseDragged(click, deltaX, deltaY);
    }
}

