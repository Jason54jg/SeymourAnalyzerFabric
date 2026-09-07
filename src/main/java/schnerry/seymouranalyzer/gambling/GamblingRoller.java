package schnerry.seymouranalyzer.gambling;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.DyedItemColor;
import schnerry.seymouranalyzer.config.ClothConfig;
import schnerry.seymouranalyzer.config.MatchPriority;
import schnerry.seymouranalyzer.data.ColorDatabase;
import schnerry.seymouranalyzer.util.ColorMath;

import java.util.*;

public class GamblingRoller {


    public enum Tier {
        ONE_OF_ONE("1/1",     0xFFD700, true),
        T0        ("T0",      0xFF3333, false),
        T1        ("T1",      0xFF80C0, false),
        T2        ("T2",      0xFFD700, false),
        T3        ("T3",      0xCCCCCC, false),
        FADE_T0   ("Fade T0", 0x3355FF, false),
        FADE_T1   ("Fade T1", 0x55CCFF, false),
        FADE_T2   ("Fade T2", 0xFFFF44, false),
        UNKNOWN   ("?",       0x888888, false);

        public final String label;
        public final int displayColor;
        public final boolean animates;

        Tier(String label, int displayColor, boolean animates) {
            this.label = label;
            this.displayColor = displayColor;
            this.animates = animates;
        }

        public int resolveColor(long timeMs) {
            if (!animates) return displayColor;
            float hue = (timeMs % 2000L) / 2000f;
            return hsbToRgb(hue, 1f, 1f);
        }
    }

    public record GamblingMatch(String name, String matchHex, double deltaE, boolean isFade, Tier tier) {}

    public record RollColor(int rgb, String hexString, String closestName, Tier tier,
                            List<GamblingMatch> topMatches) {

        public int resolveTierColor(long timeMs) {
            return tier.resolveColor(timeMs);
        }
    }

    private static final List<Item> ARMOR_TYPES = List.of(
            Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE,
            Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS
    );

    private static final Map<Integer, RollColor> ROLL_COLOR_MAP = new HashMap<>();
    private static final double PRIORITY_DELTA_E_WINDOW = 0.75;

    public static List<ItemStack> buildRollStrip(int count, int winnerIndex) {
        return buildRollStrip(count, winnerIndex, -1, null);
    }

    public static List<ItemStack> buildRollStrip(int count, int winnerIndex,
                                                  int winnerRgb, Item winnerItem) {
        ROLL_COLOR_MAP.clear();
        Random rand = new Random();
        List<ItemStack> strip = new ArrayList<>(count);

        // Use the winner's item type for ALL slots so the strip is visually consistent
        Item fixedItem = (winnerItem != null) ? winnerItem : ARMOR_TYPES.get(rand.nextInt(ARMOR_TYPES.size()));

        for (int i = 0; i < count; i++) {
            boolean isWinner = (i == winnerIndex);
            int rgb;
            Item item = fixedItem;

            if (isWinner && winnerRgb >= 0) {
                rgb = winnerRgb & 0xFFFFFF;
            } else {
                rgb = rand.nextInt(0xFFFFFF + 1);
            }

            while (ROLL_COLOR_MAP.containsKey(rgb)) {
                rgb = rand.nextInt(0xFFFFFF + 1);
            }

            RollColor rc = classifyRandomColor(rgb, isWinner);
            ROLL_COLOR_MAP.put(rgb, rc);

            ItemStack stack = new ItemStack(item);
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
            strip.add(stack);
        }

        // 1/1000 chance: plant a fake 1/1 bait item 1-3 slots away from the winner (if you are reading this, keep the joke up, dont be a silly willy)
        if (rand.nextInt(1000) == 0) {
            int offset = (rand.nextBoolean() ? 1 : -1) * (rand.nextInt(3) + 1);
            int baitIndex = Math.max(0, Math.min(count - 1, winnerIndex + offset));
            if (baitIndex != winnerIndex) {
                ItemStack baitStack = strip.get(baitIndex);
                DyedItemColor baitDyed = baitStack.get(DataComponents.DYED_COLOR);
                if (baitDyed != null) {
                    int baitRgb = baitDyed.rgb() & 0xFFFFFF;
                    RollColor fakeOno = new RollColor(baitRgb, "#" + String.format("%06X", baitRgb),
                            "???", Tier.ONE_OF_ONE, List.of());
                    ROLL_COLOR_MAP.put(baitRgb, fakeOno);
                }
            }
        }

        return strip;
    }

    private record MatchCandidate(String name, String hex, double deltaE, boolean isFade) {}

    private static RollColor classifyRandomColor(int rgb, boolean detailed) {
        String hex = String.format("%06X", rgb & 0xFFFFFF);
        ColorDatabase db = ColorDatabase.getInstance();
        ClothConfig config = ClothConfig.getInstance();
        ColorMath.LAB itemLab = db.getLabForHex(hex);

        MatchCandidate bestNormal = findBestMatch(itemLab, db.getTargetColors(), false);

        MatchCandidate bestFade = null;
        if (config.isFadeDyesEnabled()) {
            bestFade = findBestMatch(itemLab, db.getFadeDyes(), true);
        }

        MatchCandidate chosen = chooseBestCandidate(bestNormal, bestFade);

        if (chosen == null) {
            return new RollColor(rgb, "#" + hex, "Unknown", Tier.UNKNOWN, List.of());
        }

        Tier tier = tierFromDeltaE(chosen.deltaE, chosen.isFade);

        List<GamblingMatch> topMatches;
        if (detailed) {
            topMatches = findTopMatches(itemLab, db, config, 3);
        } else {
            topMatches = List.of(new GamblingMatch(
                    chosen.name, chosen.hex, chosen.deltaE, chosen.isFade, tier
            ));
        }

        return new RollColor(rgb, "#" + hex, chosen.name, tier, topMatches);
    }

    private static MatchCandidate findBestMatch(ColorMath.LAB itemLab,
                                                 Map<String, String> colorMap,
                                                 boolean isFade) {
        ColorDatabase db = ColorDatabase.getInstance();
        MatchCandidate best = null;

        for (Map.Entry<String, String> entry : colorMap.entrySet()) {
            String name = entry.getKey();
            String targetHex = entry.getValue();
            ColorMath.LAB targetLab = db.getLabForHex(targetHex);
            double deltaE = ColorMath.calculateDeltaEWithLab(itemLab, targetLab);

            if (best == null || deltaE < best.deltaE) {
                best = new MatchCandidate(name, targetHex, deltaE, isFade);
            }
        }

        return best;
    }

    private static MatchCandidate chooseBestCandidate(MatchCandidate normal, MatchCandidate fade) {
        if (normal == null && fade == null) return null;
        if (normal == null) return fade;
        if (fade == null) return normal;

        Tier normalTier = tierFromDeltaE(normal.deltaE, false);
        Tier fadeTier   = tierFromDeltaE(fade.deltaE, true);

        boolean normalIsFail = (normalTier == Tier.T3 || normalTier == Tier.UNKNOWN);
        boolean fadeIsFail   = (fadeTier == Tier.T3 || fadeTier == Tier.UNKNOWN);

        if (normalIsFail && fadeIsFail) {
            return normal.deltaE <= fade.deltaE ? normal : fade;
        }
        if (normalIsFail) return fade;
        if (fadeIsFail) return normal;

        MatchPriority normalPrio = tierToMatchPriority(normalTier);
        MatchPriority fadePrio   = tierToMatchPriority(fadeTier);

        if (normalPrio != null && fadePrio != null) {
            ClothConfig config = ClothConfig.getInstance();
            int normalIdx = config.getPriorityIndex(normalPrio);
            int fadeIdx   = config.getPriorityIndex(fadePrio);

            if (normalIdx != fadeIdx) {
                return normalIdx < fadeIdx ? normal : fade;
            }
        }

        if (Math.abs(normal.deltaE - fade.deltaE) <= PRIORITY_DELTA_E_WINDOW) {
            if (normalPrio != null && fadePrio != null) {
                ClothConfig config = ClothConfig.getInstance();
                int normalIdx = config.getPriorityIndex(normalPrio);
                int fadeIdx   = config.getPriorityIndex(fadePrio);
                return normalIdx <= fadeIdx ? normal : fade;
            }
        }

        return normal.deltaE <= fade.deltaE ? normal : fade;
    }

    private static List<GamblingMatch> findTopMatches(ColorMath.LAB itemLab,
                                                       ColorDatabase db,
                                                       ClothConfig config,
                                                       int count) {
        List<GamblingMatch> all = new ArrayList<>();

        for (Map.Entry<String, String> entry : db.getTargetColors().entrySet()) {
            ColorMath.LAB targetLab = db.getLabForHex(entry.getValue());
            double deltaE = ColorMath.calculateDeltaEWithLab(itemLab, targetLab);
            Tier tier = tierFromDeltaE(deltaE, false);
            all.add(new GamblingMatch(entry.getKey(), entry.getValue(), deltaE, false, tier));
        }

        if (config.isFadeDyesEnabled()) {
            for (Map.Entry<String, String> entry : db.getFadeDyes().entrySet()) {
                ColorMath.LAB targetLab = db.getLabForHex(entry.getValue());
                double deltaE = ColorMath.calculateDeltaEWithLab(itemLab, targetLab);
                Tier tier = tierFromDeltaE(deltaE, true);
                all.add(new GamblingMatch(entry.getKey(), entry.getValue(), deltaE, true, tier));
            }
        }

        all.sort(Comparator.comparingDouble(GamblingMatch::deltaE));
        return all.stream().limit(count).toList();
    }

    private static Tier tierFromDeltaE(double deltaE, boolean isFade) {
        if (!isFade) {
            if (deltaE < 0.01) return Tier.ONE_OF_ONE;
            if (deltaE <= 1.0)  return Tier.T0;
            if (deltaE <= 2.0)  return Tier.T1;
            if (deltaE <= 5.0)  return Tier.T2;
        } else {
            if (deltaE <= 1.0)  return Tier.FADE_T0;
            if (deltaE <= 2.0)  return Tier.FADE_T1;
            if (deltaE <= 5.0)  return Tier.FADE_T2;
        }
        return Tier.T3;
    }

    private static MatchPriority tierToMatchPriority(Tier tier) {
        return switch (tier) {
            case ONE_OF_ONE, T0 -> MatchPriority.NORMAL_T0;
            case T1             -> MatchPriority.NORMAL_T1;
            case T2             -> MatchPriority.NORMAL_T2;
            case FADE_T0        -> MatchPriority.FADE_T0;
            case FADE_T1        -> MatchPriority.FADE_T1;
            case FADE_T2        -> MatchPriority.FADE_T2;
            default             -> null;
        };
    }

    public static RollColor getRollColor(ItemStack stack) {
        DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
        if (dyed == null) return null;
        int rgb = dyed.rgb() & 0xFFFFFF;
        return ROLL_COLOR_MAP.get(rgb);
    }

    public static String getHexString(ItemStack stack) {
        RollColor rc = getRollColor(stack);
        if (rc != null) return rc.hexString();
        DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
        return dyed != null ? String.format("#%06X", dyed.rgb() & 0xFFFFFF) : "#FFFFFF";
    }

    public static String getRarityName(ItemStack stack) {
        RollColor rc = getRollColor(stack);
        return rc != null ? rc.closestName() : "Unknown";
    }

    public static String getPieceTypeName(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.LEATHER_HELMET) return "Velvet Top Hat";
        if (item == Items.LEATHER_CHESTPLATE) return "Cashmere Jacket";
        if (item == Items.LEATHER_LEGGINGS) return "Satin Trousers";
        if (item == Items.LEATHER_BOOTS) return "Oxford Shoes";
        return stack.getHoverName().getString();
    }

    public static Tier getTier(ItemStack stack) {
        RollColor rc = getRollColor(stack);
        return rc != null ? rc.tier() : Tier.UNKNOWN;
    }

    public static void invalidateCache() {
        ROLL_COLOR_MAP.clear();
    }

    public static int hsbToRgb(float hue, float sat, float bri) {
        return java.awt.Color.HSBtoRGB(hue, sat, bri) & 0xFFFFFF;
    }
}
