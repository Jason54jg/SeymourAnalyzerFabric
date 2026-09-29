package schnerry.seymouranalyzer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.loader.api.FabricLoader;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.gui.ArmorChecklistScreen;
import schnerry.seymouranalyzer.gui.DatabaseScreen;
import schnerry.seymouranalyzer.render.ItemSlotHighlighter;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;

/**
 * Configuration class compatible with Cloth Config
 * Stores all mod settings with proper getters/setters
 */
public class ClothConfig {
    private static ClothConfig INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File configDir;
    private final File configFile;
    private final File dataFile;

    // Toggle settings - Analysis Features
    @Setter
    @Getter
    private boolean infoBoxEnabled = true;
    @Setter
    @Getter
    private boolean highlightsEnabled = true;
    @Getter
    @Setter
    private boolean wordsEnabled = true;
    @Getter
    @Setter
    private boolean patternsEnabled = true;
    @Getter
    @Setter
    private boolean dupesEnabled = true;

    // Toggle settings - Filter Options
    @Getter
    @Setter
    private boolean fadeDyesEnabled = true;
    @Getter
    @Setter
    private boolean customColorsEnabled = true;
    @Getter
    @Setter
    private boolean showHighFades = true;
    @Getter
    @Setter
    private boolean showHighCustoms = false;
    @Getter
    @Setter
    private boolean showHighFakes = true;
    @Getter
    @Setter
    private boolean threePieceSetsEnabled = true;
    @Getter
    @Setter
    private boolean pieceSpecificEnabled = false;

    // Toggle settings - Display
    @Getter
    @Setter
    private boolean seymourOnlyHex = false;
    @Getter
    @Setter
    private boolean coloredHexText = true;
    @Getter
    @Setter
    private boolean dbCompareEnabled = true;
    @Getter
    @Setter
    private boolean dbCompareCurrentProfileOnly = false;
    @Getter
    private DBCompareMode dbCompareMode = DBCompareMode.NORMAL;

    // Toggle settings - Gambling
    @Getter
    @Setter
    private boolean autoRollOnVisitor = false;

    // Toggle settings - GUI
    @Getter
    @Setter
    private boolean autoPinGui = false;

    @Getter
    @Setter
    private boolean bestSetsUnderDeltaTwoOnly = false;

    // Toggle settings - Scanning
    @Getter
    @Setter
    private boolean itemFramesEnabled = false;

    @Getter
    @Setter
    private boolean scanOnlyOwnIsland = false;

    // InfoBox position
    @Getter
    @Setter
    private int infoBoxX = 50;
    @Getter
    @Setter
    private int infoBoxY = 80;
    @Getter
    @Setter
    private float infoBoxScale = 1.0f;

    // Match priorities - Higher in list = higher priority for highlights
    @Getter
    private List<MatchPriority> matchPriorities = getDefaultMatchPriorities();

    // Per-priority highlight colors (ARGB). Falls back to MatchPriority.defaultColor if not set.
    private Map<String, Integer> highlightColors = new HashMap<>();

    // Custom data
    @Getter
    private Map<String, String> customColors = new HashMap<>();
    @Getter
    private Map<String, String> wordList = new HashMap<>();
    @Getter
    private Map<String, String> profiles = new HashMap<>();
    @Getter
    private Map<String, String> customProfileNames = new HashMap<>();

    private ClothConfig() {
        configDir = new File(FabricLoader.getInstance().getConfigDir().toFile(), "seymouranalyzer");
        configFile = new File(configDir, "config.json");
        dataFile = new File(configDir, "data.json");

        if (!configDir.exists()) {
            configDir.mkdirs();
        }

        load();
    }

    public static ClothConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ClothConfig();
        }
        return INSTANCE;
    }

    public void load() {
        try {
            if (configFile.exists()) {
                JsonObject json;
                try (FileReader reader = new FileReader(configFile)) {
                    json = GSON.fromJson(reader, JsonObject.class);
                }

                if (json.has("infoBoxEnabled")) infoBoxEnabled = json.get("infoBoxEnabled").getAsBoolean();
                if (json.has("highlightsEnabled")) highlightsEnabled = json.get("highlightsEnabled").getAsBoolean();
                if (json.has("fadeDyesEnabled")) fadeDyesEnabled = json.get("fadeDyesEnabled").getAsBoolean();
                if (json.has("threePieceSetsEnabled")) threePieceSetsEnabled = json.get("threePieceSetsEnabled").getAsBoolean();
                if (json.has("pieceSpecificEnabled")) pieceSpecificEnabled = json.get("pieceSpecificEnabled").getAsBoolean();
                if (json.has("wordsEnabled")) wordsEnabled = json.get("wordsEnabled").getAsBoolean();
                if (json.has("patternsEnabled")) patternsEnabled = json.get("patternsEnabled").getAsBoolean();
                if (json.has("customColorsEnabled")) customColorsEnabled = json.get("customColorsEnabled").getAsBoolean();
                if (json.has("dupesEnabled")) dupesEnabled = json.get("dupesEnabled").getAsBoolean();
                if (json.has("showHighFades")) showHighFades = json.get("showHighFades").getAsBoolean();
                if (json.has("showHighCustoms")) showHighCustoms = json.get("showHighCustoms").getAsBoolean();
                if (json.has("showHighFakes")) showHighFakes = json.get("showHighFakes").getAsBoolean();
                if (json.has("itemFramesEnabled")) itemFramesEnabled = json.get("itemFramesEnabled").getAsBoolean();
                if (json.has("scanOnlyOwnIsland")) scanOnlyOwnIsland = json.get("scanOnlyOwnIsland").getAsBoolean();
                if (json.has("seymourOnlyHex")) seymourOnlyHex = json.get("seymourOnlyHex").getAsBoolean();
                if (json.has("coloredHexText")) coloredHexText = json.get("coloredHexText").getAsBoolean();
                if (json.has("dbCompareEnabled")) dbCompareEnabled = json.get("dbCompareEnabled").getAsBoolean();
                if (json.has("dbCompareCurrentProfileOnly")) dbCompareCurrentProfileOnly = json.get("dbCompareCurrentProfileOnly").getAsBoolean();
                if (json.has("dbCompareMode")) dbCompareMode = parseDbCompareMode(json.get("dbCompareMode").getAsString());
                if (json.has("autoRollOnVisitor")) autoRollOnVisitor = json.get("autoRollOnVisitor").getAsBoolean();
                if (json.has("autoPinGui")) autoPinGui = json.get("autoPinGui").getAsBoolean();
                if (json.has("bestSetsUnderDeltaTwoOnly")) bestSetsUnderDeltaTwoOnly = json.get("bestSetsUnderDeltaTwoOnly").getAsBoolean();

                if (json.has("infoBoxX")) infoBoxX = json.get("infoBoxX").getAsInt();
                if (json.has("infoBoxY")) infoBoxY = json.get("infoBoxY").getAsInt();
                if (json.has("infoBoxScale")) infoBoxScale = json.get("infoBoxScale").getAsFloat();

                if (json.has("matchPriorities")) {
                    matchPriorities = new java.util.ArrayList<>();
                    json.getAsJsonArray("matchPriorities").forEach(element -> {
                        MatchPriority priority = MatchPriority.fromName(element.getAsString());
                        if (priority != null) {
                            matchPriorities.add(priority);
                        }
                    });
                    // Add any missing priorities at the end
                    for (MatchPriority priority : MatchPriority.values()) {
                        if (!matchPriorities.contains(priority)) {
                            matchPriorities.add(priority);
                        }
                    }
                }

                if (json.has("highlightColors")) {
                    json.getAsJsonObject("highlightColors").entrySet().forEach(entry -> {
                        try {
                            long val = Long.parseLong(entry.getValue().getAsString(), 16);
                            highlightColors.put(entry.getKey(), (int) val);
                        } catch (NumberFormatException ignored) {}
                    });
                }

                SeymourAnalyzer.LOGGER.info("Loaded config from file");
            }
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Failed to load config", e);
        }

        // Sync autoPinGui to GUI screens after loading
        DatabaseScreen.setRememberPosition(autoPinGui);
        ArmorChecklistScreen.setRememberPage(autoPinGui);

        // Load custom data
        try {
            if (dataFile.exists()) {
                JsonObject json;
                try (FileReader reader = new FileReader(dataFile)) {
                    json = GSON.fromJson(reader, JsonObject.class);
                }

                if (json.has("customColors")) {
                    JsonObject colors = json.getAsJsonObject("customColors");
                    colors.entrySet().forEach(entry -> {
                        customColors.put(entry.getKey(), entry.getValue().getAsString());
                    });
                }

                if (json.has("wordList")) {
                    JsonObject words = json.getAsJsonObject("wordList");
                    words.entrySet().forEach(entry -> {
                        wordList.put(entry.getKey(), entry.getValue().getAsString());
                    });
                }

                if (json.has("profiles")) {
                    JsonObject storedProfiles = json.getAsJsonObject("profiles");
                    storedProfiles.entrySet().forEach(entry -> {
                        profiles.put(entry.getKey(), entry.getValue().getAsString());
                    });
                }

                if (json.has("customProfileNames")) {
                    JsonObject storedNames = json.getAsJsonObject("customProfileNames");
                    storedNames.entrySet().forEach(entry -> {
                        customProfileNames.put(entry.getKey(), entry.getValue().getAsString());
                    });
                }
            }
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Failed to load data", e);
        }
    }

    public void save() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("infoBoxEnabled", infoBoxEnabled);
            json.addProperty("highlightsEnabled", highlightsEnabled);
            json.addProperty("fadeDyesEnabled", fadeDyesEnabled);
            json.addProperty("threePieceSetsEnabled", threePieceSetsEnabled);
            json.addProperty("pieceSpecificEnabled", pieceSpecificEnabled);
            json.addProperty("wordsEnabled", wordsEnabled);
            json.addProperty("patternsEnabled", patternsEnabled);
            json.addProperty("customColorsEnabled", customColorsEnabled);
            json.addProperty("dupesEnabled", dupesEnabled);
            json.addProperty("showHighFades", showHighFades);
            json.addProperty("showHighCustoms", showHighCustoms);
            json.addProperty("showHighFakes", showHighFakes);
            json.addProperty("itemFramesEnabled", itemFramesEnabled);
            json.addProperty("scanOnlyOwnIsland", scanOnlyOwnIsland);
            json.addProperty("seymourOnlyHex", seymourOnlyHex);
            json.addProperty("coloredHexText", coloredHexText);
            json.addProperty("dbCompareEnabled", dbCompareEnabled);
            json.addProperty("dbCompareCurrentProfileOnly", dbCompareCurrentProfileOnly);
            json.addProperty("dbCompareMode", dbCompareMode.name().toLowerCase(Locale.ROOT));
            json.addProperty("autoRollOnVisitor", autoRollOnVisitor);
            json.addProperty("autoPinGui", autoPinGui);
            json.addProperty("bestSetsUnderDeltaTwoOnly", bestSetsUnderDeltaTwoOnly);

            json.addProperty("infoBoxX", infoBoxX);
            json.addProperty("infoBoxY", infoBoxY);
            json.addProperty("infoBoxScale", infoBoxScale);

            com.google.gson.JsonArray prioritiesArray = new com.google.gson.JsonArray();
            matchPriorities.forEach(priority -> prioritiesArray.add(priority.name()));
            json.add("matchPriorities", prioritiesArray);

            com.google.gson.JsonObject hColors = new com.google.gson.JsonObject();
            highlightColors.forEach((k, v) -> hColors.addProperty(k, String.format("%08X", v)));
            json.add("highlightColors", hColors);

            try (FileWriter writer = new FileWriter(configFile)) {
                GSON.toJson(json, writer);
            }

            SeymourAnalyzer.LOGGER.info("Saved config to file");
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Failed to save config", e);
        }
    }

    public void saveData() {
        try {
            JsonObject json = new JsonObject();

            JsonObject colors = new JsonObject();
            customColors.forEach(colors::addProperty);
            json.add("customColors", colors);

            JsonObject words = new JsonObject();
            wordList.forEach(words::addProperty);
            json.add("wordList", words);

            JsonObject storedProfiles = new JsonObject();
            profiles.forEach(storedProfiles::addProperty);
            json.add("profiles", storedProfiles);

            JsonObject storedNames = new JsonObject();
            customProfileNames.forEach(storedNames::addProperty);
            json.add("customProfileNames", storedNames);

            try (FileWriter writer = new FileWriter(dataFile)) {
                GSON.toJson(json, writer);
            }
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Failed to save data", e);
        }
    }

    public void putProfile(String profileId, String profileName) {
        if (profileId == null || profileId.isBlank() || profileName == null || profileName.isBlank()) return;
        if (!profileName.equals(profiles.get(profileId))) {
            profiles.put(profileId, profileName);
            saveData();
        }
    }

    public void setCustomProfileName(String profileId, String customName) {
        if (profileId == null || profileId.isBlank() || customName == null || customName.isBlank()) return;
        customProfileNames.put(profileId, customName);
        saveData();
    }

    public void clearCustomProfileName(String profileId) {
        if (profileId != null && customProfileNames.remove(profileId) != null) {
            saveData();
        }
    }

    public void setMatchPriorities(List<MatchPriority> matchPriorities) {
        this.matchPriorities = matchPriorities;
        // Clear highlight cache so items re-calculate with new priorities
        ItemSlotHighlighter.getInstance().clearCache();
    }

    /**
     * Get the configured highlight color for a priority (ARGB).
     * Falls back to the priority's built-in default color.
     */
    public int getHighlightColor(MatchPriority priority) {
        return highlightColors.getOrDefault(priority.name(), priority.getDefaultColor());
    }

    /**
     * Set a custom highlight color for a priority (ARGB).
     * Clears item cache so highlights update immediately.
     */
    public void setHighlightColor(MatchPriority priority, int argb) {
        highlightColors.put(priority.name(), argb);
        ItemSlotHighlighter.getInstance().clearCache();
    }

    /**
     * Reset a priority's highlight color back to its built-in default.
     */
    public void resetHighlightColor(MatchPriority priority) {
        highlightColors.remove(priority.name());
        ItemSlotHighlighter.getInstance().clearCache();
    }

    /**
     * Get priority index (lower number = higher priority)
     * Returns -1 if not found
     */
    public int getPriorityIndex(MatchPriority priority) {
        return matchPriorities.indexOf(priority);
    }

    /**
     * Default priority order
     * Search > Dupe > Word > Pattern > Custom T1/T2 > Normal T0/T1/T2 > Fade T0/T1/T2
     * Note: Custom colors only have T1 and T2 (no T0)
     */
    public static List<MatchPriority> getDefaultMatchPriorities() {
        return Arrays.asList(
            MatchPriority.SEARCH,
            MatchPriority.DUPE,
            MatchPriority.WORD,
            MatchPriority.PATTERN,
            MatchPriority.CUSTOM_T1,
            MatchPriority.CUSTOM_T2,
            MatchPriority.NORMAL_T0,
            MatchPriority.NORMAL_T1,
            MatchPriority.NORMAL_T2,
            MatchPriority.FADE_T0,
            MatchPriority.FADE_T1,
            MatchPriority.FADE_T2
        );
    }

    private static DBCompareMode parseDbCompareMode(String mode) {
        if (mode == null) return DBCompareMode.NORMAL;
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "different" -> DBCompareMode.DIFFERENT;
            case "unique" -> DBCompareMode.UNIQUE;
            default -> DBCompareMode.NORMAL;
        };
    }

    public void setDbCompareMode(DBCompareMode mode) {
        this.dbCompareMode = mode != null ? mode : DBCompareMode.NORMAL;
    }
}
