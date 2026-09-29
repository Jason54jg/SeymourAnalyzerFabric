package schnerry.seymouranalyzer.data;

import java.util.Collection;

public final class CollectionFingerprint {
    private CollectionFingerprint() {}

    public static String of(Collection<ArmorPiece> pieces) {
        return pieces.stream()
            .map(piece -> String.valueOf(piece.getUuid()) + "|" + piece.getPieceName() + "|"
                + piece.getHexcode() + "|" + piece.getProfileId())
            .sorted()
            .reduce("", (hash, piece) -> Integer.toHexString((hash + "\n" + piece).hashCode()));
    }
}
