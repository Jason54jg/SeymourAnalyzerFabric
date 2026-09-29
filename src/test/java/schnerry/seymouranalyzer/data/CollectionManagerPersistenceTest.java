package schnerry.seymouranalyzer.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionManagerPersistenceTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesLegacyPiecesAndWritesTheCurrentSchema() throws Exception {
        File file = tempDir.resolve("collection.json").toFile();
        Files.writeString(file.toPath(), """
            {"piece-1":{"uuid":"piece-1","pieceName":"Seymour Helmet","hexcode":"123456","profileId":"profile-a"}}
            """);

        CollectionManager manager = new CollectionManager(file);
        assertEquals("profile-a", manager.getPiece("piece-1").getProfileId());

        assertTrue(manager.updatePieceLocation("piece-1", new ArmorPiece.ChestLocation(1, 2, 3)));
        manager.forceSync();

        JsonObject saved = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
        assertEquals(DataVersions.DATABASE, saved.get("databaseVersion").getAsInt());
        assertEquals("profile-a", saved.getAsJsonObject("pieces").getAsJsonObject("piece-1").get("profileId").getAsString());
        assertEquals(2, new CollectionManager(file).getPiece("piece-1").getChestLocation().y);
    }

    @Test
    void refusesToOverwriteANewerDatabaseSchema() throws Exception {
        File file = tempDir.resolve("collection.json").toFile();
        String newerDatabase = "{\"databaseVersion\":" + (DataVersions.DATABASE + 1) + ",\"pieces\":{}}";
        Files.writeString(file.toPath(), newerDatabase);

        CollectionManager manager = new CollectionManager(file);
        manager.save();

        assertFalse(manager.hasPiece("missing"));
        assertEquals(newerDatabase, Files.readString(file.toPath()));
    }
}
