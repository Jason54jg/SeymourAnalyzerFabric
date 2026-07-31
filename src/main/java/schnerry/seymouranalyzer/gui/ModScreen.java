package schnerry.seymouranalyzer.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Base screen class for all Seymour Analyzer GUIs
 * Automatically manages GUI scale forcing and middle mouse scrolling
 *
 * Features:
 * - Forces GUI scale to 2 when opened and restores on close
 * - Middle mouse button (mouse wheel click) for drag-to-scroll
 * - All child screens inherit these features automatically
 */
public abstract class ModScreen extends Screen {
    protected Screen parent;

    // Middle mouse scrolling
    private boolean isMiddleMouseScrolling = false;
    private double lastMiddleMouseY = 0;
    private static final double MIDDLE_MOUSE_SENSITIVITY = 0.5; // Adjust scroll speed

    protected ModScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        // Force GUI scale to 2 when opened
        GuiScaleManager.getInstance().onModGuiOpen();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }

        // Restore GUI scale AFTER switching screens so the check works correctly
        GuiScaleManager.getInstance().onModGuiClose();
    }

    public void render(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        // Check scale is still correct each frame
        GuiScaleManager.getInstance().tick();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, delta);
        render(guiGraphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean isOutOfBounds) {
        // Middle mouse button (button 2) activates scrolling mode
        if (click.button() == 2) {
            isMiddleMouseScrolling = true;
            lastMiddleMouseY = click.y();
            return true;
        }
        return super.mouseClicked(click, isOutOfBounds);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        // Handle middle mouse scrolling
        if (isMiddleMouseScrolling && click.button() == 2) {
            double mouseDelta = lastMiddleMouseY - click.y();
            lastMiddleMouseY = click.y();

            // Convert mouse movement to scroll amount
            if (Math.abs(mouseDelta) > 0.1) {
                double scrollAmount = mouseDelta * MIDDLE_MOUSE_SENSITIVITY;
                // Trigger scroll events
                mouseScrolled(click.x(), click.y(), 0, scrollAmount);
            }
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        // Release middle mouse scrolling
        if (click.button() == 2 && isMiddleMouseScrolling) {
            isMiddleMouseScrolling = false;
            return true;
        }
        return super.mouseReleased(click);
    }
}

