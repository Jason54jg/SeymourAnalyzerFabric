package schnerry.seymouranalyzer.data;

import schnerry.seymouranalyzer.util.ColorMath;
import schnerry.seymouranalyzer.util.PieceTypeUtil;

import java.util.*;
import java.util.function.IntConsumer;

/**
 * Non-GUI implementation of Best Sets' pairwise ΔE and greedy non-overlap semantics.
 * The workbook does not define "Abs" for sets; it is conservatively interpreted as
 * the average pairwise Manhattan RGB distance, using ColorMath's existing Abs metric.
 */
public final class BestSetCalculator {
    public static final double MAX_DELTA_E = 5.0;
    private BestSetCalculator() {}

    public record SetScore(List<ArmorPiece> pieces, double averageDeltaE, double averageAbsoluteDistance) {}
    private record PieceMetrics(ColorMath.LAB lab, ColorMath.RGB rgb) {}

    public static List<SetScore> findUnderTwoSets(Collection<ArmorPiece> input) {
        return findUnderTwoSets(input, ignored -> {});
    }

    public static List<SetScore> findUnderTwoSets(Collection<ArmorPiece> input, IntConsumer progress) {
        Map<String, List<ArmorPiece>> byType = new HashMap<>();
        for (String type : PieceTypeUtil.pieceTypes) byType.put(type, new ArrayList<>());
        for (ArmorPiece piece : input) {
            if (piece.getHexcode() == null || piece.getUuid() == null) continue;
            String type = PieceTypeUtil.detectPieceType(piece.getPieceName());
            if (type != null) byType.get(type).add(piece);
        }
        Map<ArmorPiece, PieceMetrics> metrics = new HashMap<>();
        byType.values().forEach(pieces -> pieces.forEach(piece ->
            metrics.put(piece, new PieceMetrics(ColorMath.hexToLab(piece.getHexcode()), ColorMath.hexToRgb(piece.getHexcode())))));
        progress.accept(5);

        Map<ArmorPiece, Set<ArmorPiece>> helmetChests = compatiblePairs(byType.get("helmet"), byType.get("chestplate"), metrics, progress, 5, 12);
        Map<ArmorPiece, Set<ArmorPiece>> helmetLeggings = compatiblePairs(byType.get("helmet"), byType.get("leggings"), metrics, progress, 12, 19);
        Map<ArmorPiece, Set<ArmorPiece>> helmetBoots = compatiblePairs(byType.get("helmet"), byType.get("boots"), metrics, progress, 19, 26);
        Map<ArmorPiece, Set<ArmorPiece>> chestLeggings = compatiblePairs(byType.get("chestplate"), byType.get("leggings"), metrics, progress, 26, 33);
        Map<ArmorPiece, Set<ArmorPiece>> chestBoots = compatiblePairs(byType.get("chestplate"), byType.get("boots"), metrics, progress, 33, 40);
        Map<ArmorPiece, Set<ArmorPiece>> leggingsBoots = compatiblePairs(byType.get("leggings"), byType.get("boots"), metrics, progress, 40, 47);

        List<SetScore> candidates = new ArrayList<>();
        long estimatedCandidates = 0;
        for (ArmorPiece helmet : byType.get("helmet")) {
            long chestCount = helmetChests.getOrDefault(helmet, Set.of()).size();
            long leggingsCount = helmetLeggings.getOrDefault(helmet, Set.of()).size();
            long bootsCount = helmetBoots.getOrDefault(helmet, Set.of()).size();
            estimatedCandidates = Math.min(Long.MAX_VALUE / 2, estimatedCandidates + chestCount * leggingsCount * bootsCount);
        }
        long processedCandidates = 0;
        int lastProgress = 47;
        for (ArmorPiece h : byType.get("helmet")) for (ArmorPiece c : helmetChests.getOrDefault(h, Set.of()))
            for (ArmorPiece l : helmetLeggings.getOrDefault(h, Set.of())) {
                Set<ArmorPiece> boots = helmetBoots.getOrDefault(h, Set.of());
                if (!chestLeggings.getOrDefault(c, Set.of()).contains(l)) {
                    processedCandidates += boots.size();
                    continue;
                }
                for (ArmorPiece b : boots) {
                    processedCandidates++;
                    int candidateProgress = 47 + (int) Math.min(45, processedCandidates * 45.0 / Math.max(1, estimatedCandidates));
                    if (candidateProgress > lastProgress) {
                        progress.accept(candidateProgress);
                        lastProgress = candidateProgress;
                    }
                    if (!chestBoots.getOrDefault(c, Set.of()).contains(b)
                        || !leggingsBoots.getOrDefault(l, Set.of()).contains(b)) {
                        continue;
                    }
                if (!hasFourDistinctPieces(h, c, l, b)) continue;
                List<ArmorPiece> pieces = List.of(h, c, l, b);
                double sumDelta = 0;
                int sumAbs = 0;
                boolean valid = true;
                for (int i = 0; i < pieces.size(); i++) for (int j = i + 1; j < pieces.size(); j++) {
                    double delta = deltaE(metrics.get(pieces.get(i)), metrics.get(pieces.get(j)));
                    if (delta > MAX_DELTA_E) valid = false;
                    sumDelta += delta;
                    sumAbs += absoluteDistance(metrics.get(pieces.get(i)), metrics.get(pieces.get(j)));
                }
                double average = sumDelta / 6.0;
                if (valid && average < 2.0) candidates.add(new SetScore(pieces, average, sumAbs / 6.0));
                }
            }
        progress.accept(94);
        candidates.sort(Comparator.comparingDouble(SetScore::averageDeltaE));
        Set<String> used = new HashSet<>();
        List<SetScore> selected = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            SetScore candidate = candidates.get(index);
            if (candidate.pieces().stream().anyMatch(piece -> used.contains(piece.getUuid()))) continue;
            candidate.pieces().forEach(piece -> used.add(piece.getUuid()));
            selected.add(candidate);
            if ((index + 1) % Math.max(1, candidates.size() / 6) == 0 || index + 1 == candidates.size()) {
                progress.accept(94 + (index + 1) * 6 / Math.max(1, candidates.size()));
            }
        }
        progress.accept(100);
        return selected;
    }

    private static Map<ArmorPiece, Set<ArmorPiece>> compatiblePairs(List<ArmorPiece> first,
                                                                       List<ArmorPiece> second, Map<ArmorPiece, PieceMetrics> metrics, IntConsumer progress,
                                                                       int startProgress, int endProgress) {
        Map<ArmorPiece, Set<ArmorPiece>> pairs = new HashMap<>();
        for (int index = 0; index < first.size(); index++) {
            ArmorPiece source = first.get(index);
            Set<ArmorPiece> matches = new LinkedHashSet<>();
            for (ArmorPiece candidate : second) {
                if (deltaE(metrics.get(source), metrics.get(candidate)) <= MAX_DELTA_E) matches.add(candidate);
            }
            if (!matches.isEmpty()) pairs.put(source, matches);
            if ((index + 1) % Math.max(1, first.size() / 20) == 0 || index + 1 == first.size()) {
                progress.accept(startProgress + (index + 1) * (endProgress - startProgress) / Math.max(1, first.size()));
            }
        }
        return pairs;
    }

    private static boolean hasFourDistinctPieces(ArmorPiece first, ArmorPiece second, ArmorPiece third, ArmorPiece fourth) {
        return !first.getUuid().equals(second.getUuid()) && !first.getUuid().equals(third.getUuid())
            && !first.getUuid().equals(fourth.getUuid()) && !second.getUuid().equals(third.getUuid())
            && !second.getUuid().equals(fourth.getUuid()) && !third.getUuid().equals(fourth.getUuid());
    }

    private static double deltaE(PieceMetrics first, PieceMetrics second) {
        return ColorMath.calculateDeltaEWithLab(first.lab, second.lab);
    }

    private static int absoluteDistance(PieceMetrics first, PieceMetrics second) {
        return Math.abs(first.rgb.r() - second.rgb.r()) + Math.abs(first.rgb.g() - second.rgb.g())
            + Math.abs(first.rgb.b() - second.rgb.b());
    }
}
