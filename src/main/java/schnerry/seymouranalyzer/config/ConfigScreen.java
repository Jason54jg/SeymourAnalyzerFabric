package schnerry.seymouranalyzer.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import schnerry.seymouranalyzer.render.HexTooltipRenderer;

/**
 * Cloth Config GUI screen provider
 */
public class ConfigScreen {

    public static Screen createConfigScreen(Screen parent) {
        ClothConfig config = ClothConfig.getInstance();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Seymour Analyzer Config"))
                .setSavingRunnable(config::save);

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // Analysis Features Category
        ConfigCategory analysisCategory = builder.getOrCreateCategory(Component.literal("Analysis Features"));

        analysisCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Info Box"),
                config.isInfoBoxEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Show detailed color analysis info box when hovering items"))
                .setSaveConsumer(config::setInfoBoxEnabled)
                .build());


        analysisCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Item Highlights"),
                config.isHighlightsEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Highlight items in chests based on tier and duplicate status"))
                .setSaveConsumer(config::setHighlightsEnabled)
                .build());

        analysisCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Word Matches"),
                config.isWordsEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Detect and highlight hexes that spell words"))
                .setSaveConsumer(config::setWordsEnabled)
                .build());

        analysisCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Pattern Detection"),
                config.isPatternsEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Detect special patterns (palindrome, repeating, etc.)"))
                .setSaveConsumer(config::setPatternsEnabled)
                .build());

        analysisCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Duplicate Detection"),
                config.isDupesEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Warn when you have duplicate colors in your collection"))
                .setSaveConsumer(config::setDupesEnabled)
                .build());

        analysisCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Seymour Only Hex"),
                config.isSeymourOnlyHex())
                .setDefaultValue(false)
                .setTooltip(Component.literal("Only show hex code tooltip on Seymour armor pieces instead of all leather armor"))
                .setSaveConsumer(config::setSeymourOnlyHex)
                .build());

        analysisCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Colored Hex Text"),
                config.isColoredHexText())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Show hex codes in their actual color. Disable for better readability on dark colors."))
                .setSaveConsumer(config::setColoredHexText)
                .build());

        // DB Compare Category
        ConfigCategory dbCompareCategory = builder.getOrCreateCategory(Component.literal("DB Compare"));

        dbCompareCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Enabled"),
                config.isDbCompareEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("When holding Shift on a Seymour piece, show the 3 closest pieces from your database by ΔE"))
                .setSaveConsumer(config::setDbCompareEnabled)
                .build());

        dbCompareCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("DB Compare Current Profile Only"),
                config.isDbCompareCurrentProfileOnly())
                .setDefaultValue(false)
                .setTooltip(Component.literal("Only compare pieces stored on the Hypixel SkyBlock profile you are currently playing."))
                .setSaveConsumer(value -> {
                    config.setDbCompareCurrentProfileOnly(value);
                    HexTooltipRenderer.getInstance().clearDbCache();
                })
                .build());

        dbCompareCategory.addEntry(entryBuilder.startEnumSelector(
                Component.literal("DB Compare Mode"),
                DBCompareMode.class,
                config.getDbCompareMode())
                .setDefaultValue(DBCompareMode.NORMAL)
                .setTooltip(Component.literal("normal: closest by ΔE | different: exclude same piece type | unique: always one closest of each other piece type"))
                .setSaveConsumer(value -> {
                    config.setDbCompareMode(value);
                    HexTooltipRenderer.getInstance().clearDbCache();
                })
                .build());

        // Match Priority Editor - Create a subcategory
        ConfigCategory priorityCategory = builder.getOrCreateCategory(Component.literal("Match Priorities"));

        priorityCategory.addEntry(entryBuilder.startTextDescription(
                Component.literal("§7Configure which match types take priority for highlights.\n§7Drag items to reorder - higher = priority.")
        ).build());

        // Current priority order display
        StringBuilder priorityDisplay = new StringBuilder("§eCurrent Order:\n");
        for (int i = 0; i < config.getMatchPriorities().size(); i++) {
            MatchPriority priority = config.getMatchPriorities().get(i);
            priorityDisplay.append("§7").append(i + 1).append(". §f").append(priority.getDisplayName()).append("\n");
        }

        priorityCategory.addEntry(entryBuilder.startTextDescription(
                Component.literal(priorityDisplay.toString())
        ).build());

        priorityCategory.addEntry(entryBuilder.startTextDescription(
                Component.literal("§c(Note: This config is available with /seymour priorities)"))
                .build());

        // Filter Options Category
        ConfigCategory filterCategory = builder.getOrCreateCategory(Component.literal("Filter Options"));

        filterCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Fade Dyes"),
                config.isFadeDyesEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Include fade dyes in color matching"))
                .setSaveConsumer(config::setFadeDyesEnabled)
                .build());

        filterCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Custom Colors"),
                config.isCustomColorsEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Include custom colors in color matching"))
                .setSaveConsumer(config::setCustomColorsEnabled)
                .build());

        filterCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Show High Fades"),
                config.isShowHighFades())
                .setDefaultValue(false)
                .setTooltip(Component.literal("Show fade dye matches with ΔE > 2.00 (T2+)"))
                .setSaveConsumer(config::setShowHighFades)
                .build());

        filterCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Show High Customs"),
                config.isShowHighCustoms())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Show custom color matches with ΔE > 2.00 (T2+)"))
                .setSaveConsumer(config::setShowHighCustoms)
                .build());

        filterCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Show High Fakes"),
                config.isShowHighFakes())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Show fake piece matches with ΔE > 2.00 (T2+)"))
                .setSaveConsumer(config::setShowHighFakes)
                .build());

        filterCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("3-Piece Sets"),
                config.isThreePieceSetsEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Show matches for 3-piece sets (chestplate + leggings + boots) on helmets"))
                .setSaveConsumer(config::setThreePieceSetsEnabled)
                .build());

        filterCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Piece Specific"),
                config.isPieceSpecificEnabled())
                .setDefaultValue(false)
                .setTooltip(Component.literal("Only show matches for the specific piece type"))
                .setSaveConsumer(config::setPieceSpecificEnabled)
                .build());

        // Scanning Category
        ConfigCategory scanningCategory = builder.getOrCreateCategory(Component.literal("Scanning"));

        scanningCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Item Frames"),
                config.isItemFramesEnabled())
                .setDefaultValue(false)
                .setTooltip(Component.literal("Enable scanning of items in item frames"))
                .setSaveConsumer(config::setItemFramesEnabled)
                .build());

        scanningCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Only scan on your Island"),
                config.isScanOnlyOwnIsland())
                .setDefaultValue(false)
                .setTooltip(Component.literal("Only scan Item Frames on your own Island"))
                .setSaveConsumer(config::setScanOnlyOwnIsland)
                .build());

        // Gambling Category
        ConfigCategory gamblingCategory = builder.getOrCreateCategory(Component.literal("Gambling"));

        gamblingCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Auto Roll on Visitor"),
                config.isAutoRollOnVisitor())
                .setDefaultValue(false)
                .setTooltip(Component.literal("Automatically play the roll animation when accepting a Seymour visitor"))
                .setSaveConsumer(config::setAutoRollOnVisitor)
                .build());

        gamblingCategory.addEntry(entryBuilder.startTextDescription(
                Component.literal("§7Use §e/seymour roll §7to manually trigger the roll animation.")
        ).build());

        // GUI Category
        ConfigCategory guiCategory = builder.getOrCreateCategory(Component.literal("GUI"));

        guiCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Auto Pin GUI"),
                config.isAutoPinGui())
                .setDefaultValue(false)
                .setTooltip(Component.literal("Always remember scroll position, search text and filters in Database/Checklist GUIs (same as the Pinned: ON button)"))
                .setSaveConsumer(value -> {
                    config.setAutoPinGui(value);
                    schnerry.seymouranalyzer.gui.DatabaseScreen.setRememberPosition(value);
                    schnerry.seymouranalyzer.gui.ArmorChecklistScreen.setRememberPage(value);
                })
                .build());

        guiCategory.addEntry(entryBuilder.startBooleanToggle(
                Component.literal("Best Sets: ΔE < 2 Only"),
                config.isBestSetsUnderDeltaTwoOnly())
                .setDefaultValue(false)
                .setTooltip(Component.literal("Show only best sets with average ΔE below 2.0 instead of top 100 sets"))
                .setSaveConsumer(config::setBestSetsUnderDeltaTwoOnly)
                .build());

        return builder.build();
    }
}
