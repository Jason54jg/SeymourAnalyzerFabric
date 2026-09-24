package schnerry.seymouranalyzer.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.util.ColorMath;
import schnerry.seymouranalyzer.util.PieceTypeUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Utility class to generate checklist caches for all categories
 * Called on mod init and after collection changes
 */
public class ChecklistCacheGenerator {

    private static class ChecklistEntry {
        String hex;
        String name;
        List<String> pieces; // helmet, chestplate, leggings, boots
    }

    private static class CandidateMatch {
        int stageIndex;
        String uuid;
        ArmorPiece piece;
        double deltaE;
        boolean isNeeded;

        CandidateMatch(int stageIndex, String uuid, ArmorPiece piece, double deltaE, boolean isNeeded) {
            this.stageIndex = stageIndex;
            this.uuid = uuid;
            this.piece = piece;
            this.deltaE = deltaE;
            this.isNeeded = isNeeded;
        }
    }

    /**
     * Generate all checklist caches (both normal and fade dye)
     * This is called on mod init and after collection changes
     */
    public static void generateAllCaches() {
        SeymourAnalyzer.LOGGER.info("Starting full checklist cache generation...");

        Map<String, ArmorPiece> collection = CollectionManager.getInstance().getCollection();
        ChecklistCache cache = ChecklistCache.getInstance();

        // Load checklist data
        Map<String, List<ChecklistEntry>> normalCategories = loadChecklistData();
        if (normalCategories.isEmpty()) {
            SeymourAnalyzer.LOGGER.warn("No checklist data found, skipping cache generation");
            return;
        }

        // Load fade dye categories
        Map<String, List<ChecklistEntry>> fadeDyeCategories = loadFadeDyeData();
        if (fadeDyeCategories.isEmpty()) {
            SeymourAnalyzer.LOGGER.warn("No fade dye data found, skipping fade dye cache generation");
        }

        // Generate normal color caches
        for (Map.Entry<String, List<ChecklistEntry>> categoryEntry : normalCategories.entrySet()) {
            String categoryName = categoryEntry.getKey();
            List<ChecklistEntry> entries = categoryEntry.getValue();

            ChecklistCache.CategoryCache categoryCache = generateCacheForCategory(
                categoryName, entries, collection
            );

            cache.setNormalColorCache(categoryName, categoryCache);
        }

        // Generate fade dye caches
        for (Map.Entry<String, List<ChecklistEntry>> categoryEntry : fadeDyeCategories.entrySet()) {
            String categoryName = categoryEntry.getKey();
            List<ChecklistEntry> entries = categoryEntry.getValue();

            ChecklistCache.CategoryCache categoryCache = generateCacheForCategory(
                categoryName, entries, collection
            );

            cache.setFadeDyeOptimalCache(categoryName, categoryCache);
        }

        // Update collection size and save
        cache.setCollectionSize(collection.size());
        cache.save();

        // Clear InfoBoxRenderer's cached hover data so it will be regenerated with new cache data
        schnerry.seymouranalyzer.render.InfoBoxRenderer.forceCloseHoveredDataCache();

        SeymourAnalyzer.LOGGER.info("Completed full checklist cache generation for {} normal and {} fade dye categories",
            normalCategories.size(), fadeDyeCategories.size());
    }

    /**
     * Generate cache for a single category
     */
    private static ChecklistCache.CategoryCache generateCacheForCategory(
            String categoryName,
            List<ChecklistEntry> entries,
            Map<String, ArmorPiece> collection) {

        ChecklistCache.CategoryCache categoryCache = new ChecklistCache.CategoryCache();
        categoryCache.category = categoryName;
        categoryCache.isCalculating = false;

        String[] pieceTypes = {"helmet", "chestplate", "leggings", "boots"};

        // Temporary storage for matches
        Map<Integer, Map<String, ArmorPiece>> foundPieces = new HashMap<>();
        Map<Integer, Map<String, String>> foundPieceUuids = new HashMap<>();

        for (int i = 0; i < entries.size(); i++) {
            foundPieces.put(i, new HashMap<>());
            foundPieceUuids.put(i, new HashMap<>());
        }

        // Calculate optimal matches for each piece type
        for (String pieceType : pieceTypes) {
            List<CandidateMatch> candidates = new ArrayList<>();

            // Build candidate list
            for (int stageIdx = 0; stageIdx < entries.size(); stageIdx++) {
                ChecklistEntry entry = entries.get(stageIdx);

                for (Map.Entry<String, ArmorPiece> collectionEntry : collection.entrySet()) {
                    String uuid = collectionEntry.getKey();
                    ArmorPiece piece = collectionEntry.getValue();

                    if (!PieceTypeUtil.matchesPieceType(piece.getPieceName(), pieceType)) {
                        continue;
                    }

                    double deltaE = ColorMath.calculateDeltaE(entry.hex, piece.getHexcode());
                    if (deltaE <= 5.0) {
                        boolean isNeeded = entry.pieces.contains(pieceType);
                        candidates.add(new CandidateMatch(stageIdx, uuid, piece, deltaE, isNeeded));
                    }
                }
            }

            // Sort: needed pieces first, then by quality
            candidates.sort((a, b) -> {
                if (a.isNeeded != b.isNeeded) {
                    return a.isNeeded ? -1 : 1;
                }
                return Double.compare(a.deltaE, b.deltaE);
            });

            // Required slots use each piece once so checklist completion remains accurate.
            Set<String> usedPieces = new HashSet<>();
            Set<Integer> assignedStages = new HashSet<>();

            for (CandidateMatch candidate : candidates) {
                if (!candidate.isNeeded) continue;
                if (!usedPieces.contains(candidate.uuid) && !assignedStages.contains(candidate.stageIndex)) {
                    foundPieces.get(candidate.stageIndex).put(pieceType, candidate.piece);
                    foundPieceUuids.get(candidate.stageIndex).put(pieceType, candidate.uuid);
                    usedPieces.add(candidate.uuid);
                    assignedStages.add(candidate.stageIndex);
                }
            }

            // Fill optional slots in checklist order with each remaining piece at most once.
            for (int stageIndex = 0; stageIndex < entries.size(); stageIndex++) {
                if (entries.get(stageIndex).pieces.contains(pieceType)) continue;
                for (CandidateMatch candidate : candidates) {
                    if (!candidate.isNeeded && candidate.stageIndex == stageIndex && !usedPieces.contains(candidate.uuid)) {
                        foundPieces.get(stageIndex).put(pieceType, candidate.piece);
                        foundPieceUuids.get(stageIndex).put(pieceType, candidate.uuid);
                        usedPieces.add(candidate.uuid);
                        break;
                    }
                }
            }
        }

        // Build StageMatches for each entry
        for (int i = 0; i < entries.size(); i++) {
            ChecklistEntry entry = entries.get(i);
            ChecklistCache.StageMatches stageMatches = new ChecklistCache.StageMatches();
            stageMatches.stageHex = entry.hex;
            stageMatches.calculated = true;


            Map<String, ArmorPiece> pieces = foundPieces.get(i);
            Map<String, String> uuids = foundPieceUuids.get(i);

            // Save each piece match
            if (pieces.containsKey("helmet")) {
                ArmorPiece piece = pieces.get("helmet");
                String uuid = uuids.get("helmet");
                stageMatches.helmet = new ChecklistCache.MatchInfo(
                    piece.getPieceName(),
                    piece.getHexcode(),
                    ColorMath.calculateDeltaE(entry.hex, piece.getHexcode()),
                    uuid
                );
            }

            if (pieces.containsKey("chestplate")) {
                ArmorPiece piece = pieces.get("chestplate");
                String uuid = uuids.get("chestplate");
                stageMatches.chestplate = new ChecklistCache.MatchInfo(
                    piece.getPieceName(),
                    piece.getHexcode(),
                    ColorMath.calculateDeltaE(entry.hex, piece.getHexcode()),
                    uuid
                );
            }

            if (pieces.containsKey("leggings")) {
                ArmorPiece piece = pieces.get("leggings");
                String uuid = uuids.get("leggings");
                stageMatches.leggings = new ChecklistCache.MatchInfo(
                    piece.getPieceName(),
                    piece.getHexcode(),
                    ColorMath.calculateDeltaE(entry.hex, piece.getHexcode()),
                    uuid
                );
            }

            if (pieces.containsKey("boots")) {
                ArmorPiece piece = pieces.get("boots");
                String uuid = uuids.get("boots");
                stageMatches.boots = new ChecklistCache.MatchInfo(
                    piece.getPieceName(),
                    piece.getHexcode(),
                    ColorMath.calculateDeltaE(entry.hex, piece.getHexcode()),
                    uuid
                );
            }

            categoryCache.matchesByIndex.put(i, stageMatches);
        }

        return categoryCache;
    }

    /**
     * Load checklist data from JSON
     */
    private static Map<String, List<ChecklistEntry>> loadChecklistData() {
        Map<String, List<ChecklistEntry>> categories = new LinkedHashMap<>();

        try {
            InputStream inputStream = SeymourAnalyzer.class.getResourceAsStream("/data/seymouranalyzer/checklistdata.json");
            if (inputStream == null) {
                SeymourAnalyzer.LOGGER.error("Could not load checklistdata.json");
                return categories;
            }

            Gson gson = new Gson();
            JsonObject root = gson.fromJson(new InputStreamReader(inputStream, StandardCharsets.UTF_8), JsonObject.class);

            // Load categories
            JsonObject categoriesJson = root.getAsJsonObject("categories");
            for (String categoryName : categoriesJson.keySet()) {
                List<ChecklistEntry> entries = new ArrayList<>();
                JsonArray array = categoriesJson.getAsJsonArray(categoryName);

                for (JsonElement element : array) {
                    JsonObject obj = element.getAsJsonObject();
                    ChecklistEntry entry = new ChecklistEntry();
                    entry.hex = obj.get("hex").getAsString().toUpperCase();
                    entry.name = obj.get("name").getAsString();
                    entry.pieces = new ArrayList<>();

                    JsonArray piecesArray = obj.getAsJsonArray("pieces");
                    for (JsonElement pieceElement : piecesArray) {
                        entry.pieces.add(pieceElement.getAsString());
                    }

                    entries.add(entry);
                }

                categories.put(categoryName, entries);
            }
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Failed to load checklist data", e);
        }

        return categories;
    }

    /**
     * Load fade dye categories from colors.json
     */
    private static Map<String, List<ChecklistEntry>> loadFadeDyeData() {
        Map<String, List<ChecklistEntry>> fadeDyeCategories = new LinkedHashMap<>();

        try {
            InputStream inputStream = SeymourAnalyzer.class.getResourceAsStream("/data/seymouranalyzer/colors.json");
            if (inputStream == null) {
                SeymourAnalyzer.LOGGER.error("Could not load colors.json for fade dyes");
                return fadeDyeCategories;
            }

            Gson gson = new Gson();
            JsonObject root = gson.fromJson(new InputStreamReader(inputStream, StandardCharsets.UTF_8), JsonObject.class);
            JsonObject fadeDyes = root.getAsJsonObject("FADE_DYES");

            if (fadeDyes == null) {
                SeymourAnalyzer.LOGGER.warn("No FADE_DYES section found in colors.json");
                return fadeDyeCategories;
            }

            // Group stages by fade dye name
            for (String key : fadeDyes.keySet()) {
                // Parse "Aurora - Stage 1" format
                String[] parts = key.split(" - Stage ");
                if (parts.length == 2) {
                    String dyeName = parts[0];
                    String hexValue = fadeDyes.get(key).getAsString().toUpperCase();

                    fadeDyeCategories.putIfAbsent(dyeName, new ArrayList<>());

                    ChecklistEntry entry = new ChecklistEntry();
                    entry.hex = hexValue;
                    entry.name = key;
                    entry.pieces = new ArrayList<>();
                    entry.pieces.add("helmet");
                    entry.pieces.add("chestplate");
                    entry.pieces.add("leggings");
                    entry.pieces.add("boots");

                    fadeDyeCategories.get(dyeName).add(entry);
                }
            }

            SeymourAnalyzer.LOGGER.info("Loaded {} fade dye categories with {} total stages",
                fadeDyeCategories.size(),
                fadeDyeCategories.values().stream().mapToInt(List::size).sum());

        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Failed to load fade dye data", e);
        }

        return fadeDyeCategories;
    }
}
