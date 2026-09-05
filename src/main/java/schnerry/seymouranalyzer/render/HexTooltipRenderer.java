package schnerry.seymouranalyzer.render;

import com.mojang.blaze3d.platform.Window;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.gui.Font;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import schnerry.seymouranalyzer.analyzer.ColorAnalyzer;
import schnerry.seymouranalyzer.config.ClothConfig;
import schnerry.seymouranalyzer.config.DBCompareMode;
import schnerry.seymouranalyzer.data.ArmorPiece;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.util.ColorMath;
import schnerry.seymouranalyzer.util.ItemStackUtils;
import schnerry.seymouranalyzer.util.PieceTypeUtil;
import schnerry.seymouranalyzer.util.SkyBlockProfileTracker;
import schnerry.seymouranalyzer.util.StringUtility;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds hex code display to colored leather armor tooltips
 * Shows the hex in the actual color (like F3+H in 1.8.9)
 * For Seymour armor pieces, also shows closest match and deltaE
 */
public class HexTooltipRenderer {
    private static HexTooltipRenderer instance;
    @Getter
    @Setter
    private boolean enabled = true;

    /** Cache: hex string → list of 3 closest DB pieces (DbMatch) */
    private final Map<String, List<DbMatch>> dbCompareCache = new ConcurrentHashMap<>();

    private record DbMatch(String pieceName, String hexcode, double deltaE, int absoluteDistance, String pieceType) {}

    private HexTooltipRenderer() {
        // Register tooltip callback
        // ItemTooltipCallback signature: getTooltip(ItemStack stack, TooltipContext context, TooltipFlag type, List<Component> lines)
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) ->
            onTooltip(stack, tooltipType, lines));
    }

    public static HexTooltipRenderer getInstance() {
        if (instance == null) {
            instance = new HexTooltipRenderer();
        }
        return instance;
    }


    /**
     * Called when tooltip is rendered - add hex code line
     */
    @SuppressWarnings("unused")
    private void onTooltip(ItemStack stack, TooltipFlag tooltipType, List<Component> lines) {
        if (!enabled) return;
        if (stack.isEmpty()) return;

        // Check if item has been dyed
        DyeInfo dyeInfo = checkDyeStatus(stack);

        // Determine which hex to show as the main hex
        String displayHex;
        String hexForAnalysis;

        if (dyeInfo.isDyed) {
            // When dyed: show the dyed (fake) hex as main, use original for analysis
            displayHex = dyeInfo.dyedHex;
            hexForAnalysis = dyeInfo.originalHex;
        } else {
            // Not dyed: extract hex normally and use it for both
            displayHex = ItemStackUtils.extractHex(stack);
            hexForAnalysis = displayHex;
        }

        if (displayHex == null) return;

        String itemName = stack.getHoverName().getString();
        String itemType = StringUtility.removeFormatting(itemName);
        boolean isSeymourArmor = StringUtility.isSeymourArmor(itemName);

        // If "Seymour Only Hex" is enabled, skip non-Seymour leather pieces
        if (ClothConfig.getInstance().isSeymourOnlyHex() && !isSeymourArmor) {
            return;
        }

        // Parse hex to RGB for coloring the text
        int rgb = hexToRgb(displayHex);
        boolean useColor = ClothConfig.getInstance().isColoredHexText();

        // Build the first line: "Hex: #XXXXXX"
        // Colors are re-applied at render time by TooltipColorMixin to bypass tooltip caching
        MutableComponent hexLabel = Component.literal("Hex: ");
        hexLabel.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xA8A8A8)).withItalic(false));

        MutableComponent hexValue = Component.literal("#" + displayHex);
        if (useColor) {
            hexValue.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withItalic(false));
        } else {
            hexValue.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)).withItalic(false));
        }

        MutableComponent hexText = Component.empty().append(hexLabel).append(hexValue);

        // If item has been dyed, add a big red warning with the original hex
        if (dyeInfo.isDyed) {
            MutableComponent dyedWarning = Component.literal(" [DYED - Original: #" + dyeInfo.originalHex + "]");
            dyedWarning.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555)).withItalic(false).withBold(true));
            hexText.append(dyedWarning);
        }

        // Insert after the item name (usually line 0) and before stats
        int insertIndex = findInsertionPoint(lines);
        lines.add(insertIndex, hexText);

        // Only show closest match analysis for Seymour armor pieces
        if (isSeymourArmor) {
            // Use original hex for analysis (so closest match is based on original color)

            // Analyze color to get closest match
            ColorAnalyzer.AnalysisResult analysis = ColorAnalyzer.getInstance().analyzeArmorColor(hexForAnalysis, itemName);

            // Track next insert position so DB compare follows immediately
            int nextInsert = insertIndex + 1;

            // Add second line with closest match and deltaE if analysis succeeded
            if (analysis != null && analysis.bestMatch() != null) {
                String matchName = analysis.bestMatch().name();
                double deltaE = analysis.bestMatch().deltaE();

                // Determine closeness color based on deltaE
                int closenessColor = getClosenessColor(deltaE, analysis.tier(), analysis.bestMatch().isFade(),
                    analysis.bestMatch().isCustom());

                // Determine precision based on shift key
                Window window = Minecraft.getInstance().getWindow();
                boolean shiftHeld = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                                 || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
                String deFormat = shiftHeld ? "%.5f" : "%.2f";

                // Build the second line: "Closest: Match Name - ΔE"
                // Use setStyle() with explicit TextColor for robustness
                MutableComponent closestLabel = Component.literal("Closest: ");
                closestLabel.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xA8A8A8)).withItalic(false));

                MutableComponent matchNameComp = Component.literal(matchName);
                matchNameComp.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)).withItalic(false));

                MutableComponent separator = Component.literal(" - ");
                separator.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xA8A8A8)).withItalic(false));

                MutableComponent deltaComp = Component.literal("ΔE: " + String.format(deFormat, deltaE));
                deltaComp.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(closenessColor)).withItalic(false));

                MutableComponent closestText = Component.empty()
                    .append(closestLabel).append(matchNameComp).append(separator).append(deltaComp);

                // Insert right after the hex line
                lines.add(nextInsert, closestText);
                nextInsert++;
            }

            // DB Compare: show 3 closest pieces from user's database when shift is held
            Window window2 = Minecraft.getInstance().getWindow();
            boolean shiftHeld2 = InputConstants.isKeyDown(window2, GLFW.GLFW_KEY_LEFT_SHIFT)
                               || InputConstants.isKeyDown(window2, GLFW.GLFW_KEY_RIGHT_SHIFT);
            if (shiftHeld2 && ClothConfig.getInstance().isDbCompareEnabled()) {
                String selfUuid = ItemStackUtils.getOrCreateItemUUID(stack);
                DBCompareMode mode = ClothConfig.getInstance().getDbCompareMode();
                List<DbMatch> dbMatches = getDbCompareMatches(hexForAnalysis, itemName, selfUuid, mode);
                if (!dbMatches.isEmpty()) {
                    MutableComponent dbHeader = Component.literal("─── DB Compare ───");
                    dbHeader.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x888888)).withItalic(false));
                    lines.add(nextInsert, dbHeader);
                    nextInsert++;

                    // Measure max name width for alignment
                    Font font = Minecraft.getInstance().font;
                    int spaceWidth = font.width(" ");
                    int maxNameWidth = dbMatches.stream()
                        .mapToInt(m -> font.width(m.pieceName()))
                        .max().orElse(0);

                    for (DbMatch match : dbMatches) {
                        int matchRgb = hexToRgb(match.hexcode());
                        int tierColor = getDbTierColor(match.deltaE());
                        Style sepStyle = Style.EMPTY.withColor(TextColor.fromRgb(0x666666)).withItalic(false);

                        // Pad name with spaces to align separators
                        int nameWidth = font.width(match.pieceName());
                        int paddingPixels = maxNameWidth - nameWidth;
                        int spaces = spaceWidth > 0 ? (paddingPixels + spaceWidth - 1) / spaceWidth : 0;
                        String paddedName = match.pieceName() + " ".repeat(spaces);

                        MutableComponent nameComp = Component.literal(paddedName);
                        nameComp.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)).withItalic(false));
                        MutableComponent hexComp = Component.literal("#" + match.hexcode());
                        hexComp.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(matchRgb)).withItalic(false));
                        MutableComponent dEComp = Component.literal("ΔE:" + String.format("%.2f", match.deltaE()));
                        dEComp.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(tierColor)).withItalic(false));
                        MutableComponent absComp = Component.literal("Abs:" + String.format("%3d", match.absoluteDistance()));
                        absComp.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(tierColor)).withItalic(false));

                        MutableComponent line = Component.empty()

                            .append(hexComp)
                            .append(Component.literal(" | ").setStyle(sepStyle))
                            .append(dEComp)
                            .append(Component.literal(" | ").setStyle(sepStyle))
                            .append(absComp)
                            .append(Component.literal(" | ").setStyle(sepStyle))
                            .append(nameComp);
                        lines.add(nextInsert, line);
                        nextInsert++;
                    }
                }
            }
        }
    }

    /**
     * Get or compute the 3 closest pieces from the user's collection by ΔE.
     * Results are cached by "hex:selfUuid:mode:pieceType". The piece with selfUuid is excluded.
     */
    private List<DbMatch> getDbCompareMatches(String hex, String pieceType, String selfUuid, DBCompareMode mode) {
        final DBCompareMode finalMode = mode == null ? DBCompareMode.NORMAL : mode;
        String modeStr = finalMode.name().toLowerCase();
        boolean currentProfileOnly = ClothConfig.getInstance().isDbCompareCurrentProfileOnly();
        String activeProfileId = currentProfileOnly
            ? SkyBlockProfileTracker.getActiveProfileId().orElse("")
            : "";
        String cacheKey = hex + ":" + (selfUuid != null ? selfUuid : "") + ":" + modeStr + ":" + pieceType
            + ":" + currentProfileOnly + ":" + activeProfileId;
        return dbCompareCache.computeIfAbsent(cacheKey, k -> {
            Map<String, ArmorPiece> collection = CollectionManager.getInstance().getCollection();
            if (collection.isEmpty()) return List.of();
            if (currentProfileOnly && activeProfileId.isEmpty()) return List.of();

            String sourcePieceType = PieceTypeUtil.detectPieceType(pieceType);
            List<String> otherPieceTypes = List.of("helmet", "chestplate", "leggings", "boots").stream()
                .filter(t -> sourcePieceType == null || !t.equals(sourcePieceType))
                .toList();

            List<DbMatch> results = new ArrayList<>();
            for (ArmorPiece piece : collection.values()) {
                if (piece.getHexcode() == null || piece.getHexcode().isEmpty()) continue;
                if (currentProfileOnly && !activeProfileId.equals(piece.getProfileId())) continue;
                // Exclude self by UUID
                if (selfUuid != null && selfUuid.equals(piece.getUuid())) continue;
                String candidateType = PieceTypeUtil.detectPieceType(piece.getPieceName());
                if (finalMode == DBCompareMode.DIFFERENT && sourcePieceType != null && sourcePieceType.equals(candidateType)) continue;
                double delta = ColorMath.calculateDeltaE(hex, piece.getHexcode());
                int absDistance = ColorMath.calculateAbsoluteDistance(hex, piece.getHexcode());
                results.add(new DbMatch(piece.getPieceName(), piece.getHexcode(), delta, absDistance, candidateType));
            }

            if (finalMode == DBCompareMode.UNIQUE && sourcePieceType != null) {
                List<DbMatch> uniqueResults = new ArrayList<>();
                for (String targetType : otherPieceTypes) {
                    DbMatch bestForType = results.stream()
                        .filter(r -> targetType.equals(r.pieceType()))
                        .min(Comparator.comparingDouble(DbMatch::deltaE))
                        .orElse(null);
                    if (bestForType != null) uniqueResults.add(bestForType);
                }
                uniqueResults.sort(Comparator.comparingDouble(DbMatch::deltaE));
                return uniqueResults;
            }

            results.sort(Comparator.comparingDouble(DbMatch::deltaE));
            return results.stream().limit(3).toList();
        });
    }

    /**
     * Clear the DB compare cache (call when collection changes).
     */
    public void clearDbCache() {
        dbCompareCache.clear();
    }

    /**
     * Color for DB compare ΔE / Abs values using normal tier thresholds.
     * T0 (≤1): Red | T1 (≤2): Pink | T2 (≤5): Orange | T3+: Gray
     */
    private int getDbTierColor(double deltaE) {
        if (deltaE <= 1.0) return 0xFF5555; // Red  – T0
        if (deltaE <= 2.0) return 0xFF55FF; // Pink – T1
        if (deltaE <= 5.0) return 0xFFAA00; // Orange – T2
        return 0xAAAAAA;                    // Gray – T3+
    }

    /**
     * Get color for deltaE display based on tier and type
     */
    @SuppressWarnings("unused")
    private int getClosenessColor(double deltaE, int tier, boolean isFade, boolean isCustom) {
        if (isCustom) {
            return switch (tier) {
                case 0, 1 -> 0x00AA00; // Dark green for T0/T1
                case 2 -> 0xFFFF55; // Yellow for T2
                default -> 0xAAAAAA; // Gray for T3+
            };
        } else if (isFade) {
            return switch (tier) {
                case 0 -> 0x5555FF; // Blue for T0
                case 1 -> 0x55FFFF; // Cyan for T1
                case 2 -> 0xFFFF55; // Yellow for T2
                default -> 0xAAAAAA; // Gray for T3+
            };
        } else {
            return switch (tier) {
                case 0 -> 0xFF5555; // Red for T0
                case 1 -> 0xFF55FF; // Pink for T1
                case 2 -> 0xFFAA00; // Orange for T2
                default -> 0xAAAAAA; // Gray for T3+
            };
        }
    }

    /**
     * Find the best position to insert the hex line
     * Want it after the name but before stats/abilities
     */
    private int findInsertionPoint(List<Component> lines) {
        // Look for the first empty line or stat line
        for (int i = 1; i < lines.size(); i++) {
            String text = lines.get(i).getString();

            // If we hit an empty line, insert before it
            if (text.trim().isEmpty()) {
                return i;
            }

            // If we hit a stat line (Health:, Defense:, etc.), insert before it
            if (text.contains("Health:") || text.contains("Defense:") ||
                text.contains("Speed:") || text.contains("Strength:") ||
                text.contains("Crit Chance:") || text.contains("Crit Damage:") ||
                text.contains("Ability Damage:") || text.contains("Ferocity:") ||
                text.contains("Magic Find:") || text.contains("Pet Luck:") ||
                text.contains("Gear Score:") || text.contains("Trophy Chance")) {
                return i;
            }
        }

        // Default: insert at position 1 (right after item name)
        return Math.min(1, lines.size());
    }

    /**
     * Convert hex string to RGB integer for text coloring
     */
    private int hexToRgb(String hex) {
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFF; // White fallback
        }
    }

    /**
     * Check if an item has been dyed (has both original color data and dyed_color component)
     * Returns DyeInfo with isDyed flag and both hex values if applicable
     */
    private DyeInfo checkDyeStatus(ItemStack stack) {
        // Check for original color in custom_data (Seymour items store it as "R:G:B")
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag nbt = customData.copyTag();

        String originalHex = null;
        if (nbt.contains("color")) {
            String colorStr = nbt.getString("color").orElse("");
            if (colorStr.contains(":")) {
                originalHex = ColorMath.rgbStringToHex(colorStr);
            }
        }

        // Check for dyed_color component
        DyedItemColor dyedColor = stack.getOrDefault(DataComponents.DYED_COLOR, null);
        String dyedHex = null;
        if (dyedColor != null) {
            int rgb = dyedColor.rgb();
            dyedHex = String.format("%02X%02X%02X", (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        }

        // Item is considered "dyed" if it has BOTH original color data AND a dyed_color component
        // and they are different
        boolean isDyed = originalHex != null && dyedHex != null && !originalHex.equals(dyedHex);

        return new DyeInfo(isDyed, originalHex, dyedHex);
    }


    /**
     * Helper class to hold dye status information
     */
    private static class DyeInfo {
        boolean isDyed;
        String originalHex;
        String dyedHex;

        DyeInfo(boolean isDyed, String originalHex, String dyedHex) {
            this.isDyed = isDyed;
            this.originalHex = originalHex;
            this.dyedHex = dyedHex;
        }
    }
}
