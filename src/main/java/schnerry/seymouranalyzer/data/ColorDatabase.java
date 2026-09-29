package schnerry.seymouranalyzer.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.util.ColorMath;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the color database with target colors and fade dyes
 */
public class ColorDatabase {
    private static ColorDatabase INSTANCE;

    private final Map<String, String> targetColors = new LinkedHashMap<>();
    private final Map<String, String> fadeDyes = new LinkedHashMap<>();
    private final Map<String, ColorMath.LAB> labCache = new ConcurrentHashMap<>();
    private final Set<String> fadeDyeNames = new HashSet<>();

    private ColorDatabase() {
        loadColors();
    }

    public static ColorDatabase getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ColorDatabase();
        }
        return INSTANCE;
    }

    private void loadColors() {
        // Load target colors from JSON resource
        try {
            InputStream stream = getClass().getResourceAsStream("/data/seymouranalyzer/colors.json");
            if (stream != null) {
                JsonObject json;
                try (InputStreamReader reader = new InputStreamReader(stream)) {
                    json = new Gson().fromJson(reader, JsonObject.class);
                }

                if (json.has("TARGET_COLORS")) {
                    JsonObject colors = json.getAsJsonObject("TARGET_COLORS");
                    colors.entrySet().forEach(entry -> {
                        targetColors.put(entry.getKey(), entry.getValue().getAsString());
                    });
                }

                if (json.has("FADE_DYES")) {
                    JsonObject fades = json.getAsJsonObject("FADE_DYES");
                    fades.entrySet().forEach(entry -> {
                        fadeDyes.put(entry.getKey(), entry.getValue().getAsString());
                        fadeDyeNames.add(entry.getKey().split(" - ")[0]);
                    });
                }

                SeymourAnalyzer.LOGGER.info("Loaded {} target colors and {} fade dyes",
                    targetColors.size(), fadeDyes.size());
            }
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Failed to load color database", e);
        }
    }

    public Map<String, String> getTargetColors() {
        return Collections.unmodifiableMap(targetColors);
    }

    public Map<String, String> getFadeDyes() {
        return Collections.unmodifiableMap(fadeDyes);
    }

    public boolean isFadeDye(String colorName) {
        for (String fadeName : fadeDyeNames) {
            if (colorName.startsWith(fadeName + " - Stage")) {
                return true;
            }
        }
        return false;
    }

    public ColorMath.LAB getLabForHex(String hex) {
        return labCache.computeIfAbsent(hex.toUpperCase(), ColorMath::hexToLab);
    }

    public void rebuildLabCache() {
        labCache.clear();
        targetColors.values().forEach(this::getLabForHex);
        fadeDyes.values().forEach(this::getLabForHex);
    }

    public void clearLabCache() {
        labCache.clear();
    }
}

