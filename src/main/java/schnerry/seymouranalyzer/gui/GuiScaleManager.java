package schnerry.seymouranalyzer.gui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import schnerry.seymouranalyzer.SeymourAnalyzer;

/**
 * Manages GUI scale forcing for mod screens
 * Forces GUI scale to 2 when mod GUIs are open, restores original scale when closed
 */
public class GuiScaleManager {
    private static GuiScaleManager INSTANCE;
    private int originalGuiScale = -1;
    private boolean isModGuiOpen = false;

    private GuiScaleManager() {
        // Register tick event to check scale every tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    public static GuiScaleManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new GuiScaleManager();
        }
        return INSTANCE;
    }

    /**
     * Call this when a mod GUI is opened
     */
    public void onModGuiOpen() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) return;

        // Save original scale if not already saved
        if (originalGuiScale == -1) {
            originalGuiScale = client.options.guiScale().get();
            SeymourAnalyzer.LOGGER.info("[GuiScale] Saved original GUI scale: " + originalGuiScale);
        }

        isModGuiOpen = true;

        // Set scale to 2
        if (client.options.guiScale().get() != 2) {
            client.options.guiScale().set(2);
            client.resizeGui();
            SeymourAnalyzer.LOGGER.info("[GuiScale] Set GUI scale to 2");
        }
    }

    /**
     * Call this when a mod GUI is closed
     */
    public void onModGuiClose() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) return;

        isModGuiOpen = false;

        // Check if we're still in a mod GUI (nested GUIs)
        Screen currentScreen = client.gui.screen();
        if (isModScreen(currentScreen)) {
            SeymourAnalyzer.LOGGER.info("[GuiScale] Still in mod GUI, keeping scale at 2");
            return;
        }

        // Restore original scale if we saved one
        if (originalGuiScale != -1) {
            client.options.guiScale().set(originalGuiScale);
            client.resizeGui();
            SeymourAnalyzer.LOGGER.info("[GuiScale] Restored GUI scale to " + originalGuiScale);
            originalGuiScale = -1; // Reset
        }
    }

    /**
     * Check if a screen is a mod screen
     */
    private boolean isModScreen(Screen screen) {
        if (screen == null) return false;

        String className = screen.getClass().getName();
        // Check if it's in gui package or config package (for PriorityEditorScreen)
        return className.startsWith("schnerry.seymouranalyzer.gui.") ||
               className.startsWith("schnerry.seymouranalyzer.config.");
    }

    /**
     * Force update check - call this periodically to ensure scale is correct
     */
    public void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) return;

        Screen currentScreen = client.gui.screen();
        boolean shouldBeModGui = isModScreen(currentScreen);

        // Debug logging
        if (isModGuiOpen && currentScreen == null) {
            SeymourAnalyzer.LOGGER.info("[GuiScale] Detected GUI closed (was mod GUI, now null)");
        }

        // If we should be in mod GUI but aren't marked as such
        if (shouldBeModGui && !isModGuiOpen) {
            SeymourAnalyzer.LOGGER.info("[GuiScale] Detected mod GUI opened in tick()");
            onModGuiOpen();
        }
        // If we shouldn't be in mod GUI but are marked as such
        else if (!shouldBeModGui && isModGuiOpen) {
            SeymourAnalyzer.LOGGER.info("[GuiScale] Detected mod GUI closed in tick()");
            onModGuiClose();
        }
    }

    /**
     * Check if we're currently in a mod GUI
     */
    public boolean isInModGui() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return false;

        return isModScreen(client.gui.screen());
    }

    /**
     * Reset state (for when client closes or switches worlds)
     */
    @SuppressWarnings("unused") // May be used in future for world changes
    public void reset() {
        originalGuiScale = -1;
        isModGuiOpen = false;
    }
}

