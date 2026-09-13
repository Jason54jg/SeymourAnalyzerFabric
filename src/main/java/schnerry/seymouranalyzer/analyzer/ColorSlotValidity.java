package schnerry.seymouranalyzer.analyzer;

import schnerry.seymouranalyzer.util.PieceTypeUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Shared, configuration-independent slot validity used by weight calculations. */
public final class ColorSlotValidity {
    private static final int HELMET = 1;
    private static final int CHESTPLATE = 1 << 1;
    private static final int LEGGINGS = 1 << 2;
    private static final int BOOTS = 1 << 3;
    private static final Map<String, Integer> SLOT_MASKS = new ConcurrentHashMap<>();

    private ColorSlotValidity() {}

    public static boolean isValidForPiece(String colorName, String pieceName) {
        String pieceType = PieceTypeUtil.detectPieceType(pieceName);
        return isValidForPieceType(colorName, pieceType);
    }

    public static boolean isValidForPieceType(String colorName, String pieceType) {
        if (pieceType == null || colorName == null) return true;
        String lower = colorName.toLowerCase();
        if (lower.contains("3p")) return !"helmet".equals(pieceType);
        int slotMask = SLOT_MASKS.computeIfAbsent(lower, ColorSlotValidity::buildSlotMask);
        if (slotMask == 0) return true;
        return switch (pieceType) {
            case "helmet" -> (slotMask & HELMET) != 0;
            case "chestplate" -> (slotMask & CHESTPLATE) != 0;
            case "leggings" -> (slotMask & LEGGINGS) != 0;
            case "boots" -> (slotMask & BOOTS) != 0;
            default -> true;
        };
    }

    private static int buildSlotMask(String colorName) {
        int slotMask = 0;
        for (String segment : colorName.split("/")) {
            for (String part : segment.split("\\+")) {
                String value = part.trim();
                if (PieceTypeUtil.matchesPieceType(value, "helmet")) slotMask |= HELMET;
                if (PieceTypeUtil.matchesPieceType(value, "chestplate")) slotMask |= CHESTPLATE;
                if (PieceTypeUtil.matchesPieceType(value, "leggings")) slotMask |= LEGGINGS;
                if (PieceTypeUtil.matchesPieceType(value, "boots")) slotMask |= BOOTS;
            }
        }
        return slotMask;
    }
}
