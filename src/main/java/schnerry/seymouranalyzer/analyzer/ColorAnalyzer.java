package schnerry.seymouranalyzer.analyzer;

import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.data.ColorDatabase;
import schnerry.seymouranalyzer.config.ClothConfig;
import schnerry.seymouranalyzer.config.MatchPriority;
import schnerry.seymouranalyzer.util.ColorMath;
import schnerry.seymouranalyzer.util.PieceTypeUtil;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes armor colors and finds best matches from the database
 */
public class ColorAnalyzer {
    private static ColorAnalyzer INSTANCE;
    private static final double PRIORITY_DELTA_E_WINDOW = 0.75;
    private final ColorDatabase colorDatabase;

    private ColorAnalyzer() {
        this.colorDatabase = ColorDatabase.getInstance();
    }

    public static ColorAnalyzer getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ColorAnalyzer();
        }
        return INSTANCE;
    }

    /**
     * Analyze an armor piece and find best color matches
     * <p>
     * Strategy:
     * 1. Collect matches from each category (customs, normals, fades) separately
     * 2. Sort each category by deltaE
     * 3. Take top 5 from each category to prevent any one category from crowding out others
     * 4. Combine all selected matches and take top 10 by deltaE
     * 5. Separate exact matches (always prioritized)
     * 6. Apply user-defined priority order to non-exact matches
     * 7. Return top 3 matches after prioritization
     * <p>
     * This ensures that custom colors and normal colors aren't excluded when there are
     * many fade dye matches, which was causing issues when showHighFades was enabled.
     */
    public AnalysisResult analyzeArmorColor(String hexcode, String pieceName) {
        ClothConfig config = ClothConfig.getInstance();
        String pieceType = PieceTypeUtil.detectPieceType(pieceName);

        // Collect matches from each category separately to prevent one category from crowding out others
        List<ColorMatch> customMatches = new ArrayList<>();
        List<ColorMatch> normalMatches;
        List<ColorMatch> fadeMatches = new ArrayList<>();

        // Check custom colors first if enabled
        if (config.isCustomColorsEnabled()) {
            customMatches = findMatchesInMap(hexcode, pieceType, config.getCustomColors(), true, false);

            // Apply high custom filtering - only show T0/T1 customs (deltaE <= 2.0) when disabled
            if (!config.isShowHighCustoms()) {
                customMatches = customMatches.stream()
                        .filter(m -> m.deltaE <= 2.0) // Only keep T0/T1 customs (deltaE <= 2.0)
                        .collect(Collectors.toList());
            }

            customMatches.sort(Comparator.comparingDouble(m -> m.deltaE));
        }

        // Check target colors
        normalMatches = findMatchesInMap(hexcode, pieceType, colorDatabase.getTargetColors(), false, false);
        normalMatches.sort(Comparator.comparingDouble(m -> m.deltaE));

        // Check fade dyes if enabled
        if (config.isFadeDyesEnabled()) {
            fadeMatches = findMatchesInMap(hexcode, pieceType, colorDatabase.getFadeDyes(), false, true);

            // Apply high fade filtering - only show T0/T1 fades (deltaE <= 2.0) when disabled
            if (!config.isShowHighFades()) {
                fadeMatches = fadeMatches.stream()
                    .filter(m -> m.deltaE <= 2.0) // Only keep T0/T1 fades (deltaE <= 2.0)
                    .collect(Collectors.toList());
            }

            fadeMatches.sort(Comparator.comparingDouble(m -> m.deltaE));
        }

        // Take top 5 from each category to prevent any single category from dominating
        // This ensures customs and normals aren't crowded out by fades
        List<ColorMatch> allMatches = new ArrayList<>();
        allMatches.addAll(customMatches.stream().limit(5).toList());
        allMatches.addAll(normalMatches.stream().limit(5).toList());
        allMatches.addAll(fadeMatches.stream().limit(5).toList());

        // Apply high fake filtering - only show T0/T1 fake matches (deltaE <= 2.0) when disabled
        // (only relevant when pieceSpecificEnabled is false, since fakes are hard-excluded otherwise)
        if (!config.isShowHighFakes()) {
            allMatches = allMatches.stream()
                .filter(m -> !m.isFake() || m.deltaE <= 2.0)
                .collect(Collectors.toList());
        }

        // Step 1: Sort all selected matches by deltaE
        allMatches.sort(Comparator.comparingDouble(m -> m.deltaE));

        // Step 2: Take top 10 closest matches by deltaE
        List<ColorMatch> top10ByDeltaE = allMatches.stream()
            .limit(10)
            .toList();

        // Step 3: Separate exact matches (deltaE ~= 0) from regular matches
        // Exact matches should ALWAYS be prioritized above everything else
        List<ColorMatch> exactMatches = new ArrayList<>();
        List<ColorMatch> regularMatches = new ArrayList<>();

        for (ColorMatch match : top10ByDeltaE) {
            if (match.deltaE < 0.01) { // Exact match (accounting for floating point precision)
                exactMatches.add(match);
            } else {
                regularMatches.add(match);
            }
        }

        // Step 4: Split regular matches into prioritized (tier 0-2) and unprioritized (tier 3+)
        List<ColorMatch> prioritizedMatches = new ArrayList<>();
        List<ColorMatch> unprioritizedMatches = new ArrayList<>();

        for (ColorMatch match : regularMatches) {
            if (match.tier <= 2) {
                prioritizedMatches.add(match);
            } else {
                unprioritizedMatches.add(match);
            }
        }

        // Step 5: Sort prioritized matches by priority, then by deltaE
        prioritizedMatches.sort((m1, m2) -> {
            MatchPriority p1 = getMatchPriority(m1);
            MatchPriority p2 = getMatchPriority(m2);

            ClothConfig clothConfig = ClothConfig.getInstance();
            int idx1 = clothConfig.getPriorityIndex(p1);
            int idx2 = clothConfig.getPriorityIndex(p2);

            // If priorities are different, sort by priority (lower index = higher priority)
            if (idx1 != idx2) {
                return Integer.compare(idx1, idx2);
            }

            // Same priority, sort by deltaE
            return Double.compare(m1.deltaE, m2.deltaE);
        });

        // Step 6: Combine lists - exact matches FIRST, then prioritized, then unprioritized
        unprioritizedMatches.sort(Comparator.comparingDouble(m -> m.deltaE));
        List<ColorMatch> finalList = new ArrayList<>();
        finalList.addAll(exactMatches);  // Exact matches always first
        finalList.addAll(prioritizedMatches);
        finalList.addAll(unprioritizedMatches);

        // Step 7: Apply safety guards to avoid inaccurate best-match picks
        // - Do not allow a T3+ result if any T0-T2 candidate exists
        // - Do not let priority override a materially closer normal/custom candidate
        finalList = applySelectionGuards(finalList);

        // Step 8: Build result list - up to 10 non-T3 matches, minimum 3 total
        List<ColorMatch> nonT3 = finalList.stream()
            .filter(m -> m.tier <= 2)
            .limit(10)
            .collect(Collectors.toList());

        List<ColorMatch> top3;
        if (nonT3.size() >= 3) {
            top3 = nonT3;
        } else {
            // Pad with T3+ entries to reach at least 3
            top3 = new ArrayList<>(nonT3);
            finalList.stream()
                .filter(m -> m.tier > 2)
                .limit(Math.max(0, 3 - nonT3.size()))
                .forEach(top3::add);
        }

        if (top3.isEmpty()) {
            SeymourAnalyzer.LOGGER.warn("[ColorAnalyzer] No matches found for hex: {}", hexcode);
            return null;
        }

        ColorMatch best = top3.getFirst();
        int tier = calculateTier(best.deltaE, best.isCustom, best.isFade);

        return new AnalysisResult(best, top3, tier);
    }

    /**
     * Guardrails for final selection to reduce false positives where priority pushes a worse match to the top.
     */
    private List<ColorMatch> applySelectionGuards(List<ColorMatch> input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        List<ColorMatch> guarded = new ArrayList<>(input);
        ColorMatch currentBest = guarded.getFirst();

        // Guard 1: if best is T3+, prefer the closest T0-T2 candidate if one exists.
        if (currentBest.tier > 2) {
            Optional<ColorMatch> bestTier2OrLower = guarded.stream()
                .filter(m -> m.tier <= 2)
                .min(Comparator.comparingDouble(m -> m.deltaE));

            if (bestTier2OrLower.isPresent()) {
                ColorMatch better = bestTier2OrLower.get();
                guarded.remove(better);
                guarded.addFirst(better);
                currentBest = better;
            }
        }

        // Guard 2: if best is fade, but a normal/custom match is close in deltaE, prefer non-fade.
        // This avoids selecting fade T2 over plausible normal T1/T2 due to priority settings.
        if (currentBest.isFade) {
            double maxAllowedDeltaE = currentBest.deltaE + PRIORITY_DELTA_E_WINDOW;
            Optional<ColorMatch> bestNonFadeNear = guarded.stream()
                .filter(m -> !m.isFade && m.tier <= 2 && m.deltaE <= maxAllowedDeltaE)
                .min(Comparator.comparingDouble(m -> m.deltaE));

            if (bestNonFadeNear.isPresent()) {
                ColorMatch better = bestNonFadeNear.get();
                guarded.remove(better);
                guarded.addFirst(better);
            }
        }

        return guarded;
    }

    private List<ColorMatch> findMatchesInMap(String itemHex, String pieceType,
                                               Map<String, String> colorMap,
                                               boolean isCustom, boolean isFade) {
        ClothConfig config = ClothConfig.getInstance();
        List<ColorMatch> matches = new ArrayList<>();
        ColorMath.LAB itemLab = colorDatabase.getLabForHex(itemHex);

        for (Map.Entry<String, String> entry : colorMap.entrySet()) {
            String colorName = entry.getKey();
            String targetHex = entry.getValue();

            // Determine if this match is a "fake" (piece-specific color matched against the wrong slot)
            boolean isFake = pieceType != null && !canMatchPiece(colorName, pieceType);

            // Piece-specific filtering: when enabled, hard-exclude fake matches
            if (config.isPieceSpecificEnabled() && isFake) {
                continue;
            }

            // 3-piece set filtering: skip 3p entries on helmets when disabled
            if (!config.isThreePieceSetsEnabled() && pieceType != null && pieceType.equals("helmet")) {
                if (colorName.contains("3p")) {
                    continue;
                }
            }

            ColorMath.LAB targetLab = colorDatabase.getLabForHex(targetHex);
            double deltaE = ColorMath.calculateDeltaEWithLab(itemLab, targetLab);

            // Always add to matches - filtering is now done at the category level in analyzeArmorColor
            int absoluteDist = ColorMath.calculateAbsoluteDistance(itemHex, targetHex);
            int tier = calculateTier(deltaE, isCustom, isFade);

            ColorMatch match = new ColorMatch(colorName, targetHex, deltaE, absoluteDist, tier, isCustom, isFade, isFake);
            matches.add(match);
        }

        return matches;
    }

    private boolean canMatchPiece(String colorName, String pieceType) {
        if (pieceType == null) return true;

        String lower = colorName.toLowerCase();

        // 3p entries are multi-piece sets (chestplate/leggings/boots), never helmets.
        if (lower.contains("3p")) {
            return !"helmet".equals(pieceType);
        }

        // Build the union of all explicitly mentioned piece types by splitting on
        // "/" (alternative names for same hex) and "+" (multi-slot compounds).
        // Parts with no slot keywords don't contribute — they're just name aliases.
        Set<String> mentionedSlots = new HashSet<>();

        for (String segment : colorName.split("/")) {
            for (String part : segment.split("\\+")) {
                part = part.trim();
                if (PieceTypeUtil.matchesPieceType(part, "helmet"))     mentionedSlots.add("helmet");
                if (PieceTypeUtil.matchesPieceType(part, "chestplate")) mentionedSlots.add("chestplate");
                if (PieceTypeUtil.matchesPieceType(part, "leggings"))   mentionedSlots.add("leggings");
                if (PieceTypeUtil.matchesPieceType(part, "boots"))      mentionedSlots.add("boots");
            }
        }

        // If no slot keywords found anywhere → generic color → allow for all slots
        if (mentionedSlots.isEmpty()) return true;

        return mentionedSlots.contains(pieceType);
    }

    private int calculateTier(double deltaE, boolean isCustom, boolean isFade) {
        if (deltaE <= 1) return 0;  // T1<
        if (deltaE <= 2) return 1;  // T1
        if (deltaE <= 5) return 2;  // T2
        return 3;
    }

    /**
     * Determine the MatchPriority enum value for a ColorMatch
     * This is used to sort matches according to user priority settings
     */
    private MatchPriority getMatchPriority(ColorMatch match) {
        if (match.isCustom) {
            if (match.tier == 1 || match.tier == 0) return MatchPriority.CUSTOM_T1;
            if (match.tier == 2) return MatchPriority.CUSTOM_T2;
        }

        if (match.isFade) {
            if (match.tier == 0) return MatchPriority.FADE_T0;
            if (match.tier == 1) return MatchPriority.FADE_T1;
            if (match.tier == 2) return MatchPriority.FADE_T2;
        }

        // Normal colors
        if (match.tier == 0) return MatchPriority.NORMAL_T0;
        if (match.tier == 1) return MatchPriority.NORMAL_T1;
        if (match.tier == 2) return MatchPriority.NORMAL_T2;

        // Fallback to lowest priority
        return MatchPriority.NORMAL_T2;
    }

    public record AnalysisResult(ColorMatch bestMatch, List<ColorMatch> top3Matches, int tier) {
    }

    public record ColorMatch(String name, String targetHex, double deltaE, int absoluteDistance, int tier,
                             boolean isCustom, boolean isFade, boolean isFake) {
    }
}

