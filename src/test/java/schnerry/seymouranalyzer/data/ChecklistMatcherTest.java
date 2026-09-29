package schnerry.seymouranalyzer.data;

import org.junit.jupiter.api.Test;
import schnerry.seymouranalyzer.util.PieceTypeUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecklistMatcherTest {
    @Test
    void assignsRequiredSlotsBeforeOptionalSlotsWithoutReusingPieces() {
        List<ChecklistMatcher.Stage> stages = List.of(
            new ChecklistMatcher.Stage("000000", List.of()),
            new ChecklistMatcher.Stage("000000", List.of("helmet")));
        Map<String, ArmorPiece> collection = new LinkedHashMap<>();
        collection.put("first", piece("first", "Seymour Helmet", "000000"));

        List<Map<String, ChecklistMatcher.Match>> assignments = ChecklistMatcher.assign(
            stages, collection, PieceTypeUtil::matchesPieceType);
        assertFalse(assignments.get(0).containsKey("helmet"));
        assertEquals("first", assignments.get(1).get("helmet").uuid());

        collection.put("second", piece("second", "Seymour Helmet", "000000"));
        assignments = ChecklistMatcher.assign(stages, collection, PieceTypeUtil::matchesPieceType);
        assertEquals("second", assignments.get(0).get("helmet").uuid());
        assertEquals("first", assignments.get(1).get("helmet").uuid());
        assertEquals("000000", ChecklistMatcher.toCacheStage(stages.get(1), assignments.get(1)).helmet.hex);
    }

    @Test
    void respectsTheCallersPieceTypeRule() {
        List<ChecklistMatcher.Stage> stages = List.of(new ChecklistMatcher.Stage("000000", List.of("helmet")));
        Map<String, ArmorPiece> collection = Map.of("cap", piece("cap", "Seymour Cap", "000000"));

        assertTrue(ChecklistMatcher.assign(stages, collection, PieceTypeUtil::matchesPieceType)
            .getFirst().containsKey("helmet"));
        assertFalse(ChecklistMatcher.assign(stages, collection, (name, type) -> false)
            .getFirst().containsKey("helmet"));
    }

    private static ArmorPiece piece(String uuid, String name, String hex) {
        ArmorPiece piece = new ArmorPiece();
        piece.setUuid(uuid);
        piece.setPieceName(name);
        piece.setHexcode(hex);
        return piece;
    }
}
