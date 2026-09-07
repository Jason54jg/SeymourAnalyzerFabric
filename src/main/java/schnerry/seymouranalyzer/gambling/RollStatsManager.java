package schnerry.seymouranalyzer.gambling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.util.ColorMath;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RollStatsManager {
    public enum Source { COMMAND, VISITOR }

    public static final class RollRecord {
        public long timestamp;
        public Source source;
        public String pieceType;
        public String hex;
        public String closestName;
        public String closestHex;
        public double deltaE;
        public int absoluteDistance;
        public String tier;
        public List<MatchRecord> topMatches = new ArrayList<>();
    }

    public static final class MatchRecord {
        public String name;
        public String hex;
        public double deltaE;
        public int absoluteDistance;
        public String tier;
    }

    private static final int MAX_SAVED_ROLLS = 1_000;
    private static final RollStatsManager INSTANCE = new RollStatsManager();
    private final List<RollRecord> rolls = new ArrayList<>();

    private RollStatsManager() {
        load();
    }

    public static RollStatsManager getInstance() {
        return INSTANCE;
    }

    public synchronized void record(ItemStack item, Source source) {
        GamblingRoller.RollColor color = GamblingRoller.getRollColor(item);
        DyedItemColor dyed = item.get(DataComponents.DYED_COLOR);
        if (color == null || dyed == null) return;

        RollRecord record = new RollRecord();
        record.timestamp = System.currentTimeMillis();
        record.source = source;
        record.pieceType = GamblingRoller.getPieceTypeName(item);
        record.hex = color.hexString();
        record.closestName = color.closestName();
        record.closestHex = color.topMatches().isEmpty() ? "" : color.topMatches().getFirst().matchHex();
        record.deltaE = color.topMatches().isEmpty() ? Double.NaN : color.topMatches().getFirst().deltaE();
        record.absoluteDistance = absoluteDistance(dyed.rgb() & 0xFFFFFF, record.closestHex);
        record.tier = color.tier().label;

        for (GamblingRoller.GamblingMatch match : color.topMatches()) {
            MatchRecord topMatch = new MatchRecord();
            topMatch.name = match.name();
            topMatch.hex = match.matchHex();
            topMatch.deltaE = match.deltaE();
            topMatch.absoluteDistance = absoluteDistance(dyed.rgb() & 0xFFFFFF, match.matchHex());
            topMatch.tier = match.tier().label;
            record.topMatches.add(topMatch);
        }

        rolls.add(record);
        while (rolls.size() > MAX_SAVED_ROLLS) rolls.removeFirst();
        save();
    }

    public synchronized List<RollRecord> getLastRolls(int limit) {
        int fromIndex = Math.max(0, rolls.size() - limit);
        List<RollRecord> recent = new ArrayList<>(rolls.subList(fromIndex, rolls.size()));
        java.util.Collections.reverse(recent);
        return recent;
    }

    private static int absoluteDistance(int rgb, String hex) {
        if (hex == null || hex.isBlank()) return 0;
        ColorMath.RGB target = ColorMath.hexToRgb(hex.replace("#", ""));
        return Math.abs(((rgb >> 16) & 0xFF) - target.r())
            + Math.abs(((rgb >> 8) & 0xFF) - target.g())
            + Math.abs((rgb & 0xFF) - target.b());
    }

    private void load() {
        Path path = getPath();
        if (!Files.exists(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RollRecord[] saved = new Gson().fromJson(reader, RollRecord[].class);
            if (saved != null) java.util.Collections.addAll(rolls, saved);
        } catch (Exception exception) {
            SeymourAnalyzer.LOGGER.error("Failed to load roll statistics", exception);
        }
    }

    private void save() {
        try {
            Files.createDirectories(getPath().getParent());
            try (Writer writer = Files.newBufferedWriter(getPath(), StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(rolls, writer);
            }
        } catch (Exception exception) {
            SeymourAnalyzer.LOGGER.error("Failed to save roll statistics", exception);
        }
    }

    private static Path getPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("seymouranalyzer").resolve("roll_stats.json");
    }
}
