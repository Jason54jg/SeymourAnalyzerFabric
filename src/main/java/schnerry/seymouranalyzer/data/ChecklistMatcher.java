package schnerry.seymouranalyzer.data;

import schnerry.seymouranalyzer.util.ColorMath;
import schnerry.seymouranalyzer.util.PieceTypeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

public final class ChecklistMatcher {
    private ChecklistMatcher() {}

    public record Stage(String hex, List<String> requiredPieceTypes) {}
    public record Match(String uuid, ArmorPiece piece) {}
    private record Candidate(int stageIndex, String uuid, ArmorPiece piece, double deltaE, boolean needed) {}

    public static List<Map<String, Match>> assign(List<Stage> stages, Map<String, ArmorPiece> collection,
                                                    BiPredicate<String, String> matchesPieceType) {
        List<Map<String, Match>> assignments = new ArrayList<>(stages.size());
        for (int index = 0; index < stages.size(); index++) assignments.add(new HashMap<>());

        for (String pieceType : PieceTypeUtil.pieceTypes) {
            List<Candidate> candidates = new ArrayList<>();
            for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
                Stage stage = stages.get(stageIndex);
                for (Map.Entry<String, ArmorPiece> entry : collection.entrySet()) {
                    ArmorPiece piece = entry.getValue();
                    if (!matchesPieceType.test(piece.getPieceName(), pieceType)) continue;
                    double deltaE = ColorMath.calculateDeltaE(stage.hex(), piece.getHexcode());
                    if (deltaE <= 5.0) {
                        candidates.add(new Candidate(stageIndex, entry.getKey(), piece, deltaE,
                            stage.requiredPieceTypes().contains(pieceType)));
                    }
                }
            }

            candidates.sort((first, second) -> {
                if (first.needed() != second.needed()) return first.needed() ? -1 : 1;
                return Double.compare(first.deltaE(), second.deltaE());
            });

            Set<String> usedPieces = new HashSet<>();
            Set<Integer> assignedStages = new HashSet<>();
            for (Candidate candidate : candidates) {
                if (!candidate.needed() || usedPieces.contains(candidate.uuid())
                    || assignedStages.contains(candidate.stageIndex())) continue;
                assignments.get(candidate.stageIndex()).put(pieceType, new Match(candidate.uuid(), candidate.piece()));
                usedPieces.add(candidate.uuid());
                assignedStages.add(candidate.stageIndex());
            }

            for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
                if (stages.get(stageIndex).requiredPieceTypes().contains(pieceType)) continue;
                for (Candidate candidate : candidates) {
                    if (!candidate.needed() && candidate.stageIndex() == stageIndex
                        && !usedPieces.contains(candidate.uuid())) {
                        assignments.get(stageIndex).put(pieceType, new Match(candidate.uuid(), candidate.piece()));
                        usedPieces.add(candidate.uuid());
                        break;
                    }
                }
            }
        }
        return assignments;
    }

    public static ChecklistCache.StageMatches toCacheStage(Stage stage, Map<String, Match> assignments) {
        ChecklistCache.StageMatches result = new ChecklistCache.StageMatches();
        result.stageHex = stage.hex();
        result.calculated = true;
        result.helmet = toCacheMatch(stage.hex(), assignments.get("helmet"));
        result.chestplate = toCacheMatch(stage.hex(), assignments.get("chestplate"));
        result.leggings = toCacheMatch(stage.hex(), assignments.get("leggings"));
        result.boots = toCacheMatch(stage.hex(), assignments.get("boots"));
        return result;
    }

    private static ChecklistCache.MatchInfo toCacheMatch(String hex, Match match) {
        if (match == null) return null;
        ArmorPiece piece = match.piece();
        return new ChecklistCache.MatchInfo(piece.getPieceName(), piece.getHexcode(),
            ColorMath.calculateDeltaE(hex, piece.getHexcode()), match.uuid());
    }
}
