package schnerry.seymouranalyzer.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import schnerry.seymouranalyzer.SeymourAnalyzer;
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

        List<ChecklistMatcher.Stage> stages = entries.stream()
            .map(entry -> new ChecklistMatcher.Stage(entry.hex, entry.pieces))
            .toList();
        List<Map<String, ChecklistMatcher.Match>> assignments = ChecklistMatcher.assign(
            stages, collection, PieceTypeUtil::matchesPieceType);
        for (int index = 0; index < stages.size(); index++) {
            categoryCache.matchesByIndex.put(index, ChecklistMatcher.toCacheStage(stages.get(index), assignments.get(index)));
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
            JsonObject root;
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                root = gson.fromJson(reader, JsonObject.class);
            }

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
            JsonObject root;
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                root = gson.fromJson(reader, JsonObject.class);
            }
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
