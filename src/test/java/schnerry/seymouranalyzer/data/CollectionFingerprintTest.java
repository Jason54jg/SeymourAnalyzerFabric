package schnerry.seymouranalyzer.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CollectionFingerprintTest {
    @Test
    void ignoresCollectionOrderButDetectsReplacementAtTheSameSize() {
        ArmorPiece first = piece("first", "Seymour Helmet", "123456", "profile-a");
        ArmorPiece second = piece("second", "Seymour Boots", "654321", "profile-a");
        ArmorPiece replacement = piece("second", "Seymour Boots", "ABCDEF", "profile-a");

        assertEquals(CollectionFingerprint.of(List.of(first, second)),
            CollectionFingerprint.of(List.of(second, first)));
        assertNotEquals(CollectionFingerprint.of(List.of(first, second)),
            CollectionFingerprint.of(List.of(first, replacement)));
    }

    @Test
    void detectsProfileChanges() {
        ArmorPiece first = piece("first", "Seymour Helmet", "123456", "profile-a");
        ArmorPiece moved = piece("first", "Seymour Helmet", "123456", "profile-b");

        assertNotEquals(CollectionFingerprint.of(List.of(first)), CollectionFingerprint.of(List.of(moved)));
    }

    private static ArmorPiece piece(String uuid, String name, String hex, String profileId) {
        ArmorPiece piece = new ArmorPiece();
        piece.setUuid(uuid);
        piece.setPieceName(name);
        piece.setHexcode(hex);
        piece.setProfileId(profileId);
        return piece;
    }
}
