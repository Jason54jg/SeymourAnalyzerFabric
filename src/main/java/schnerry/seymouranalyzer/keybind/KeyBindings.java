package schnerry.seymouranalyzer.keybind;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import schnerry.seymouranalyzer.command.SeymourCommand;
import schnerry.seymouranalyzer.config.ConfigScreen;
import schnerry.seymouranalyzer.debug.ItemDebugger;
import schnerry.seymouranalyzer.gui.ArmorChecklistScreen;
import schnerry.seymouranalyzer.gui.DatabaseScreen;
import schnerry.seymouranalyzer.render.InfoBoxRenderer;
import schnerry.seymouranalyzer.render.ItemSlotHighlighter;
import schnerry.seymouranalyzer.util.ItemStackUtils;

/**
 * Keybinding to open GUIs - alternative to commands
 */
public class KeyBindings {

    // Category name for key bindings
    private static final KeyMapping.Category SEYMOURANALYZER_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("seymouranalyzer", "main"));

    private static KeyMapping openDatabaseGuiKey;
    private static KeyMapping openConfigGuiKey;
    private static KeyMapping openChecklistGuiKey;
    private static KeyMapping debugCaptureKey;
    private static KeyMapping copyPieceKey;
    private static KeyMapping openPieceInDBKey;

    public static void register() {
        // O for Database GUI
        openDatabaseGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.seymouranalyzer.opendatabasegui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            SEYMOURANALYZER_CATEGORY
        ));

        // I for Config GUI
        openConfigGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.seymouranalyzer.openconfiggui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_I,
            SEYMOURANALYZER_CATEGORY
        ));

        // P for Checklist GUI
        openChecklistGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.seymouranalyzer.openchecklistgui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            SEYMOURANALYZER_CATEGORY
        ));

        // No default key for debug capture (configurable in keybind settings)
        debugCaptureKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.seymouranalyzer.debugcapture",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            SEYMOURANALYZER_CATEGORY
        ));

        // No default key for copying piece hex (configurable in keybind settings)
        copyPieceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.seymouranalyzer.copypiece",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            SEYMOURANALYZER_CATEGORY
        ));

        // No default key for opening piece in database search (configurable in keybind settings)
        openPieceInDBKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.seymouranalyzer.openpieceindb",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            SEYMOURANALYZER_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isKeyMappingBound(openDatabaseGuiKey) && openDatabaseGuiKey.consumeClick()) {
                client.gui.setScreen(new DatabaseScreen(null));
            }

            if (isKeyMappingBound(openConfigGuiKey) && openConfigGuiKey.consumeClick()) {
                client.gui.setScreen(ConfigScreen.createConfigScreen(client.gui.screen()));
            }

            if (isKeyMappingBound(openChecklistGuiKey) && openChecklistGuiKey.consumeClick()) {
                client.gui.setScreen(new ArmorChecklistScreen(null));
            }
        });

        // debugCaptureKey needs to work while a screen is open.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenKeyboardEvents.afterKeyPress(screen).register(
                    (Screen s, KeyEvent context) -> {
                        InputConstants.Key boundKey = KeyMappingHelper.getBoundKeyOf(debugCaptureKey);
                        if (isBoundKeyPress(boundKey, context)) {
                            ItemDebugger.getInstance().onCaptureKeyPressed();
                        }
                    }
                );
            }
        });

        // copyPieceKey needs to work while a screen is open.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenKeyboardEvents.afterKeyPress(screen).register(
                    (Screen s, KeyEvent context) -> {
                        InputConstants.Key boundKey = KeyMappingHelper.getBoundKeyOf(copyPieceKey);
                        if (isBoundKeyPress(boundKey, context)) {
                            ItemDebugger.getInstance().copyCurrentPieceHexToClipboard();
                        }
                    }
                );
            }
        });

        // openPieceInDBKey needs to work while a screen is open.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenKeyboardEvents.afterKeyPress(screen).register(
                    (Screen s, KeyEvent context) -> {
                        InputConstants.Key boundKey = KeyMappingHelper.getBoundKeyOf(openPieceInDBKey);
                        if (isBoundKeyPress(boundKey, context)) {
                            String hex = ItemStackUtils.extractHex(InfoBoxRenderer.getInstance().getLastHoveredStack());
                            Minecraft mc = Minecraft.getInstance();
                            DatabaseScreen dbScreen = new DatabaseScreen(screen);
                            dbScreen.setInitialSearch(hex);
                            mc.gui.setScreen(dbScreen);
                        }
                    }
                );
            }
        });
    }

    private static boolean isKeyMappingBound(KeyMapping keyMapping) {
        InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(keyMapping);
        return key != InputConstants.UNKNOWN;
    }

    private static boolean isBoundKeyPress(InputConstants.Key boundKey, KeyEvent context) {
        return boundKey != InputConstants.UNKNOWN
            && boundKey.getType() == InputConstants.Type.KEYSYM
            && boundKey.getValue() == context.key();
    }
}

