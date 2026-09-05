package schnerry.seymouranalyzer.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import schnerry.seymouranalyzer.util.SkyBlockProfileTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

final class ProfileDropdown {
    private static final int OPTION_HEIGHT = 20;

    private ProfileDropdown() {
    }

    static List<Button> createOptions(int x, int y, int width, String selectedProfileId,
                                      Consumer<String> onSelect) {
        List<Button> options = new ArrayList<>();
        int optionY = y;
        for (String profileId : SkyBlockProfileTracker.getProfileOptions()) {
            String label = SkyBlockProfileTracker.getProfileLabel(profileId);
            if (profileId.equals(selectedProfileId)) {
                label = "\u00a7a" + label;
            }
            String selectedId = profileId;
            options.add(Button.builder(Component.literal(label), button -> onSelect.accept(selectedId))
                .bounds(x, optionY, width, OPTION_HEIGHT)
                .build());
            optionY += OPTION_HEIGHT;
        }
        return options;
    }

    static void renderModal(GuiGraphicsExtractor guiGraphics, Font font, int screenWidth, int screenHeight,
                            int x, int y, int width, int selectorHeight, String selectedProfileId) {
        guiGraphics.fill(0, 0, screenWidth, screenHeight, 0x90000000);
        drawButton(guiGraphics, font, x, y, width, selectorHeight,
            "Profile: " + SkyBlockProfileTracker.getProfileLabel(selectedProfileId), false);

        int optionY = y + selectorHeight;
        for (String profileId : SkyBlockProfileTracker.getProfileOptions()) {
            drawButton(guiGraphics, font, x, optionY, width, OPTION_HEIGHT,
                SkyBlockProfileTracker.getProfileLabel(profileId), profileId.equals(selectedProfileId));
            optionY += OPTION_HEIGHT;
        }
    }

    static Optional<String> getSelectedOption(double mouseX, double mouseY, int x, int y, int width,
                                              int selectorHeight) {
        List<String> profileOptions = SkyBlockProfileTracker.getProfileOptions();
        int optionsY = y + selectorHeight;
        if (mouseX < x || mouseX > x + width || mouseY < optionsY
                || mouseY >= optionsY + profileOptions.size() * OPTION_HEIGHT) {
            return Optional.empty();
        }
        return Optional.of(profileOptions.get(((int) mouseY - optionsY) / OPTION_HEIGHT));
    }

    static boolean isOverSelector(double mouseX, double mouseY, int x, int y, int width, int selectorHeight) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY < y + selectorHeight;
    }

    private static void drawButton(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width,
                                   int height, String label, boolean selected) {
        guiGraphics.fill(x, y, x + width, y + height, 0xFF808080);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF303030);
        guiGraphics.centeredText(font, selected ? "\u00a7a" + label : label, x + width / 2,
            y + (height - font.lineHeight) / 2, 0xFFFFFFFF);
    }
}
