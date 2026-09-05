package schnerry.seymouranalyzer.data;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Represents a scanned armor piece with color analysis
 */
@Getter
@Setter
public class ArmorPiece {
    private String uuid;
    private String pieceName;
    private String hexcode;
    private ChestLocation chestLocation;
    private BestMatch bestMatch;
    private List<ColorMatch> allMatches;
    private String wordMatch;
    private String specialPattern;
    private long timestamp; // Hypixel Skyblock timestamp
    private String profileId;

    // Transient fields for hex search (not serialized)
    private transient String cachedSearchHex;
    private transient Double cachedSearchDeltaE;
    private transient Integer cachedSearchDistance;

    public static class ChestLocation {
        public int x, y, z;

        public ChestLocation(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String toString() {
            return x + ", " + y + ", " + z;
        }
    }

    public static class BestMatch {
        public String colorName;
        public String targetHex;
        public double deltaE;
        public int absoluteDistance;
        public int tier;

        public BestMatch(String colorName, String targetHex, double deltaE, int absoluteDistance, int tier) {
            this.colorName = colorName;
            this.targetHex = targetHex;
            this.deltaE = deltaE;
            this.absoluteDistance = absoluteDistance;
            this.tier = tier;
        }
    }

    public static class ColorMatch {
        public String colorName;
        public String targetHex;
        public double deltaE;
        public int absoluteDistance;
        public int tier;
        public boolean isCustom;
        public boolean isFade;

        public ColorMatch(String colorName, String targetHex, double deltaE, int absoluteDistance, int tier) {
            this.colorName = colorName;
            this.targetHex = targetHex;
            this.deltaE = deltaE;
            this.absoluteDistance = absoluteDistance;
            this.tier = tier;
        }
    }

    // Convenience method for rebuild commands
    public void setBestMatch(String colorName, String targetHex, double deltaE, int absoluteDistance, int tier) {
        this.bestMatch = new BestMatch(colorName, targetHex, deltaE, absoluteDistance, tier);
    }
}
