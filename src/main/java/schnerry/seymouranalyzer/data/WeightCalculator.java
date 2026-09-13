package schnerry.seymouranalyzer.data;

import com.google.gson.Gson;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.analyzer.ColorSlotValidity;
import schnerry.seymouranalyzer.util.ColorMath;
import schnerry.seymouranalyzer.util.PieceTypeUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/** Calculates the workbook-derived collection weight without depending on UI configuration. */
public final class WeightCalculator {
    private static final Pattern HEX = Pattern.compile("[0-9A-F]{6}");
    private static volatile Rules rules;
    private WeightCalculator() {}

    public record ScoreBonus(String name, double weight) {}
    public record PieceWeight(ArmorPiece piece, String matchName, String targetHex, int tier, boolean fake,
                              String rule, double baseWeight, List<ScoreBonus> bonuses) {}
    public enum ContributionType { BASE_RULE, PIECE_BONUS, COLLECTION_BONUS }
    public record Breakdown(String name, double weight, String detail, ContributionType type) {}
    public record UnlistedMatch(String name, String hex, int tier, int pieces) {}
    public record Result(List<PieceWeight> pieces, List<Breakdown> breakdowns, double baseTotal, double bonusTotal,
                         double total, List<UnlistedMatch> unlistedMatches, String note) {}
    public record Progress(String phase, int percent) {}
    private static final class Rules { List<BaseRule> baseRules; List<WordBonus> wordBonuses; }
    private static final class BaseRule { int row; String label; double weight; Double ifFake; String comment; }
    private static final class WordBonus { String word; double weight; }
    private record Match(String name, String hex, String sourceHex, double delta, int tier, boolean fade) {}

    public static Result calculate(Collection<ArmorPiece> collection, Consumer<Progress> progress) {
        progress.accept(new Progress("Loading workbook rules", 5));
        Rules loaded = rules();
        List<ArmorPiece> pieces = collection.stream().filter(WeightCalculator::validPiece).toList();
        List<PieceWeight> weighted = new ArrayList<>();
        List<Breakdown> contributions = new ArrayList<>();
        double baseTotal = 0;
        progress.accept(new Progress("Applying one base rule per piece", 10));
        for (int index = 0; index < pieces.size(); index++) {
            ArmorPiece piece = pieces.get(index);
            Match match = closestMatch(piece);
            BaseRule rule = selectRule(loaded.baseRules, match);
            boolean fake = !ColorSlotValidity.isValidForPiece(match.name, piece.getPieceName());
            double base = fake && rule.ifFake != null ? rule.ifFake : rule.weight;
            List<ScoreBonus> applied = new ArrayList<>();
            List<Bonus> individual = individualBonuses(piece.getHexcode());
            for (Bonus bonus : individual) applied.add(new ScoreBonus(bonus.name, bonus.weight));
            weighted.add(new PieceWeight(piece, match.name, match.hex, match.tier, fake, rule.label, base,
                List.copyOf(applied)));
            baseTotal += base;
            contributions.add(new Breakdown(rule.label + (fake && rule.ifFake != null ? " (If Fake)" : ""),
                base, match.name, ContributionType.BASE_RULE));
            for (ScoreBonus bonus : applied) {
                contributions.add(new Breakdown(bonus.name, bonus.weight, "Piece bonus", ContributionType.PIECE_BONUS));
            }
            if ((index + 1) % Math.max(1, pieces.size() / 100) == 0 || index + 1 == pieces.size()) {
                progress.accept(new Progress("Applying one base rule per piece", 10 + (index + 1) * 35 / Math.max(1, pieces.size())));
            }
        }
        double bonusTotal = contributions.stream().filter(contribution -> contribution.detail.equals("Piece bonus"))
            .mapToDouble(Breakdown::weight).sum();
        progress.accept(new Progress("Applying exact-hex duplicate bonuses", 45));
        bonusTotal += addDuplicateBonuses(weighted, contributions);
        progress.accept(new Progress("Calculating Best Sets", 47));
        bonusTotal += addBestSetBonuses(pieces, contributions,
            value -> progress.accept(new Progress("Calculating Best Sets", 47 + value * 23 / 100)));
        progress.accept(new Progress("Calculating checklist categories", 70));
        bonusTotal += addChecklistBonuses(pieces, contributions, progress);
        List<Breakdown> breakdowns = summarizeContributions(contributions);
        List<UnlistedMatch> unlistedMatches = summarizeUnlistedMatches(weighted);
        progress.accept(new Progress("Calculation complete", 100));
        return new Result(List.copyOf(weighted), breakdowns, baseTotal, bonusTotal, baseTotal + bonusTotal, unlistedMatches,
            "Rules were generated from Weight System.xlsx comments. Unlisted workbook cases use a zero-weight deterministic fallback.");
    }

    private static boolean validPiece(ArmorPiece piece) {
        return piece != null && piece.getHexcode() != null && HEX.matcher(piece.getHexcode().replace("#", "").toUpperCase()).matches()
            && piece.getPieceName() != null;
    }

    private static Match closestMatch(ArmorPiece piece) {
        ColorDatabase db = ColorDatabase.getInstance();
        Match best = null;
        for (Map.Entry<String, String> entry : db.getTargetColors().entrySet()) best = closer(best, entry, false, piece.getHexcode());
        for (Map.Entry<String, String> entry : db.getFadeDyes().entrySet()) best = closer(best, entry, true, piece.getHexcode());
        if (best == null) return new Match("Unknown", "000000", normalizeHex(piece.getHexcode()), Double.MAX_VALUE, 3, false);
        return best;
    }

    private static Match closer(Match best, Map.Entry<String, String> entry, boolean fade, String hex) {
        double delta = ColorMath.calculateDeltaE(hex, entry.getValue());
        int tier = delta <= 1 ? 0 : delta <= 2 ? 1 : delta <= 5 ? 2 : 3;
        Match candidate = new Match(entry.getKey(), normalizeHex(entry.getValue()), normalizeHex(hex), delta, tier, fade);
        return best == null || delta < best.delta ? candidate : best;
    }

    private static BaseRule selectRule(List<BaseRule> all, Match match) {
        // The worksheet separates exact (1:1) normal, fade, and Great Spook matches.
        // Resolve these before tier rules so every exact piece has its single intended base.
        if (isExactHexMatch(match)) {
            String expected = isGreatSpookHex(match.hex) ? "1:1 Great Spook"
                : match.fade ? "1:1 Fade" : "1:1";
            for (BaseRule rule : all) if (rule.label.equalsIgnoreCase(expected)) return rule;
        }

        List<BaseRule> candidates = new ArrayList<>();
        for (BaseRule rule : all) if (isSpecificMatch(rule, match)) candidates.add(rule);
        if (!candidates.isEmpty()) return candidates.stream().max(Comparator.comparingDouble(rule -> rule.weight)).orElseThrow();
        BaseRule category = all.stream().filter(rule -> isCategoryMatch(rule, match))
            .max(Comparator.comparingDouble(rule -> rule.weight)).orElse(null);
        if (category != null) return category;
        return fallback(match);
    }

    private static boolean isExactHexMatch(Match match) {
        return normalizeHex(match.hex).equals(match.sourceHex);
    }

    private static boolean isGreatSpookHex(String hex) {
        return ColorDatabase.getInstance().getTargetColors().entrySet().stream()
            .anyMatch(entry -> entry.getKey().startsWith("Great Spook") && normalizeHex(entry.getValue()).equals(normalizeHex(hex)));
    }

    private static boolean isSpecificMatch(BaseRule rule, Match match) {
        String label = rule.label.toLowerCase();
        if (label.contains("bulk") || label.contains("normal fade") || label.contains("dark/light fade")) return false;
        if (requiredTier(rule) != match.tier) return false;
        String comment = normalize(rule.comment == null ? "" : rule.comment);
        String ruleLabel = normalize(rule.label);
        String matchName = normalize(match.name);
        return comment.contains(matchName) || comment.contains(match.hex)
            || ruleLabel.contains(matchName) || ruleLabel.contains(match.hex);
    }

    private static boolean isCategoryMatch(BaseRule rule, Match match) {
        String label = rule.label.toLowerCase();
        if (label.contains("adaptive") && match.name.startsWith("Adaptive Armour") && !match.name.contains("Outside"))
            return requiredTier(rule) == match.tier;
        if (label.contains("ghostly") && match.name.startsWith("Ghostly Boots")) return requiredTier(rule) == match.tier;
        if (label.startsWith("great spook t") && match.name.startsWith("Great Spook")) return requiredTier(rule) == match.tier
            && spookRange(label, match.hex);
        if (label.contains("xxxxxx") && match.hex.matches("(.)\\1{5}")) return requiredTier(rule) == match.tier;
        if (label.equals("dark/light bulk")) return match.tier == 3 && isDarkOrLightBulk(match.hex);
        if (label.contains("normal fade") || label.contains("dark/light fade")) {
            boolean exceptional = exceptionalFade(match.name);
            return match.fade && requiredTier(rule) == match.tier && (label.contains("dark/light") == exceptional);
        }
        if (label.equals("t3 (bulk)")) return match.tier == 3;
        return false;
    }

    private static boolean spookRange(String label, String hex) {
        int value = Integer.parseInt(hex, 16);
        if (label.contains("light purple")) return value >= Integer.parseInt("89009B", 16) && value <= Integer.parseInt("9E00B2", 16);
        if (label.contains("dark purple")) return value >= Integer.parseInt("300036", 16) && value <= Integer.parseInt("830093", 16);
        return value < Integer.parseInt("300036", 16);
    }

    private static boolean exceptionalFade(String name) {
        if (name.startsWith("Charcoal - Stage")) return true;
        int stage = stage(name);
        return (name.startsWith("Black Ice") && ((stage >= 2 && stage <= 20) || (stage >= 55 && stage <= 65)))
            || (name.startsWith("Snowflake") && ((stage >= 6 && stage <= 28) || (stage >= 39 && stage <= 57)))
            || (name.startsWith("Beach") && stage >= 52 && stage <= 60);
    }
    private static boolean isDarkOrLightBulk(String hex) {
        ColorMath.RGB rgb = ColorMath.hexToRgb(hex);
        return (rgb.r() <= 0x22 && rgb.g() <= 0x22 && rgb.b() <= 0x22)
            || (rgb.r() >= 0xDD && rgb.g() >= 0xDD && rgb.b() >= 0xDD);
    }
    private static int stage(String name) { try { return Integer.parseInt(name.replaceAll(".*Stage ", "")); } catch (NumberFormatException ignored) { return -1; } }
    private static int requiredTier(BaseRule rule) {
        String label = rule.label.toLowerCase();
        if (label.contains("t3")) return 3;
        if (label.contains("t2")) return 2;
        if (label.contains("t1")) return 1;
        if (label.contains("t0")) return 0;
        return rule.row <= 28 ? 2 : rule.row <= 42 ? 1 : 0;
    }
    private static BaseRule fallback(Match match) {
        BaseRule fallback = new BaseRule(); fallback.label = "Unlisted " + (match.fade ? "fade " : "color ") + "T" + match.tier;
        fallback.weight = 0; return fallback;
    }

    private record Bonus(String name, double weight) {}
    private static List<Bonus> individualBonuses(String rawHex) {
        String hex = rawHex.replace("#", "").toUpperCase();
        List<Bonus> bonuses = new ArrayList<>();
        char[] c = hex.toCharArray();
        if (c[0] == c[2] && c[2] == c[4]) bonuses.add(new Bonus("AxBxCx", 15));
        if (c[0] == c[1] && c[2] == c[3] && c[4] == c[5]) bonuses.add(new Bonus("AABBCC", 15));
        if (c[0] == c[5] && c[1] == c[4] && c[2] == c[3]) bonuses.add(new Bonus("ABCCBA", 15));
        if (c[0] == c[3] && c[1] == c[4] && c[2] == c[5]) bonuses.add(new Bonus("ABCABC", 15));
        int max = 0; for (char value : c) { int count = 0; for (char other : c) if (value == other) count++; max = Math.max(max, count); }
        int run = 1, largestRun = 1; for (int i = 1; i < c.length; i++) { run = c[i] == c[i - 1] ? run + 1 : 1; largestRun = Math.max(largestRun, run); }
        if (max >= 4) bonuses.add(new Bonus(largestRun >= 4 ? "Quads (In a row)" : "Quads (Not in a row)", largestRun >= 4 ? 10 : 3));
        if (max >= 5) bonuses.add(new Bonus(largestRun >= 5 ? "Quintuples (In a row)" : "Quintuples (Not in a row)", largestRun >= 5 ? 30 : 10));
        if (hex.matches("(.)\\1\\1(.)\\2\\2")) bonuses.add(new Bonus("AAABBB", 50));
        if (hex.matches("(.)(.)\\1\\2\\1\\2")) bonuses.add(new Bonus("ABABAB", 50));
        for (WordBonus word : rules().wordBonuses) if (wordMatches(hex, word.word)) {
            bonuses.add(new Bonus(word.word.length() + " letter word: " + word.word, word.weight));
        }
        return bonuses;
    }
    private static boolean wordMatches(String hex, String rawPattern) {
        String pattern = rawPattern.toUpperCase();
        for (int start = 0; start + pattern.length() <= hex.length(); start++) {
            Map<Character, Character> bound = new HashMap<>(); boolean good = true;
            for (int i = 0; i < pattern.length(); i++) { char p = pattern.charAt(i), h = hex.charAt(start + i);
                if (p == 'X') continue; if (p == 'W' || p == 'Y' || p == 'Z') { if (bound.putIfAbsent(p, h) != null && bound.get(p) != h) good = false; } else if (p != h) good = false;
            } if (good) return true;
        } return false;
    }

    private static double addDuplicateBonuses(List<PieceWeight> pieces, List<Breakdown> out) {
        double total = 0; Map<String, List<PieceWeight>> groups = new HashMap<>();
        for (PieceWeight piece : pieces) groups.computeIfAbsent(piece.piece().getHexcode().replace("#", "").toUpperCase(), ignored -> new ArrayList<>()).add(piece);
        for (Map.Entry<String, List<PieceWeight>> group : groups.entrySet()) if (group.getValue().size() >= 2) {
            int bestTier = group.getValue().stream().mapToInt(PieceWeight::tier).min().orElse(3);
            double pair = switch (bestTier) { case 0 -> 25; case 1 -> 15; case 2 -> 8; default -> 5; };
            total += pair; out.add(new Breakdown("Dupe Pair (T" + bestTier + ")", pair, "#" + group.getKey() + " ×" + group.getValue().size(), ContributionType.COLLECTION_BONUS));
            if (group.getValue().size() >= 3) { total += 75; out.add(new Breakdown("3x Dupe", 75, "#" + group.getKey() + " ×" + group.getValue().size(), ContributionType.COLLECTION_BONUS)); }
        } return total;
    }

    private static double addBestSetBonuses(List<ArmorPiece> pieces, List<Breakdown> out, IntConsumer progress) {
        double total = 0;
        for (BestSetCalculator.SetScore set : BestSetCalculator.findUnderTwoSets(pieces, progress)) {
            total += 1; out.add(new Breakdown("Best Set", 1, String.format("avg ΔE %.2f", set.averageDeltaE()), ContributionType.COLLECTION_BONUS));
            double abs = set.averageAbsoluteDistance();
            if (abs <= 3) { total += 5; out.add(new Breakdown("Best Set & 3 Abs", 5, "avg Abs " + String.format("%.2f", abs), ContributionType.COLLECTION_BONUS)); }
            if (abs <= 2) { total += 10; out.add(new Breakdown("Best Set & 2 Abs", 10, "avg Abs " + String.format("%.2f", abs), ContributionType.COLLECTION_BONUS)); }
            if (abs <= 1) { total += 20; out.add(new Breakdown("Best Set & 1 Abs", 20, "avg Abs " + String.format("%.2f", abs), ContributionType.COLLECTION_BONUS)); }
            if (abs <= 0) { total += 50; out.add(new Breakdown("Best Set & 0 Abs", 50, "avg Abs " + String.format("%.2f", abs), ContributionType.COLLECTION_BONUS)); }
        } return total;
    }

    private static double addChecklistBonuses(List<ArmorPiece> pieces, List<Breakdown> out, Consumer<Progress> progress) {
        Map<String, List<ChecklistTarget>> categories = ChecklistTargets.load();
        ChecklistPieceIndex pieceIndex = ChecklistPieceIndex.create(pieces);
        double total = 0;
        int categoryIndex = 0;
        for (Map.Entry<String, List<ChecklistTarget>> category : categories.entrySet()) {
            List<ChecklistTarget> targets = category.getValue(); if (targets.isEmpty()) continue;
            int t2 = maximumMatches(targets, pieceIndex, 5);
            int t1 = maximumMatches(targets, pieceIndex, 2);
            if (t2 == targets.size()) { total += 20; out.add(new Breakdown("Checklist T2", 20, category.getKey(), ContributionType.COLLECTION_BONUS)); }
            double ratio = t1 / (double) targets.size();
            if (ratio >= .5) { total += 25; out.add(new Breakdown("Checklist T1 50%", 25, category.getKey(), ContributionType.COLLECTION_BONUS)); }
            if (ratio >= .75) { total += 50; out.add(new Breakdown("Checklist T1 75%", 50, category.getKey(), ContributionType.COLLECTION_BONUS)); }
            if (ratio >= 1) { total += 100; out.add(new Breakdown("Checklist T1 100%", 100, category.getKey(), ContributionType.COLLECTION_BONUS)); }
            categoryIndex++;
            progress.accept(new Progress("Calculating checklist categories", 70 + categoryIndex * 30 / Math.max(1, categories.size())));
        } return total;
    }
    private record ChecklistTarget(String id, String hex, String type) {}
    private record ChecklistCandidate(ChecklistTarget target, ArmorPiece piece, double deltaE) {}
    private record LabCell(int lightness, int a, int b) {}

    private static final class ChecklistPieceIndex {
        private static final double CELL_SIZE = 5.0;
        private final Map<String, Map<LabCell, List<ArmorPiece>>> piecesByTypeAndCell;

        private ChecklistPieceIndex(Map<String, Map<LabCell, List<ArmorPiece>>> piecesByTypeAndCell) {
            this.piecesByTypeAndCell = piecesByTypeAndCell;
        }

        private static ChecklistPieceIndex create(List<ArmorPiece> pieces) {
            Map<String, Map<LabCell, List<ArmorPiece>>> indexed = new HashMap<>();
            for (ArmorPiece piece : pieces) {
                String type = PieceTypeUtil.detectPieceType(piece.getPieceName());
                if (type == null) continue;
                indexed.computeIfAbsent(type, ignored -> new HashMap<>())
                    .computeIfAbsent(cell(ColorMath.hexToLab(piece.getHexcode())), ignored -> new ArrayList<>())
                    .add(piece);
            }
            return new ChecklistPieceIndex(indexed);
        }

        private List<ArmorPiece> nearby(String type, ColorMath.LAB target) {
            Map<LabCell, List<ArmorPiece>> cells = piecesByTypeAndCell.get(type);
            if (cells == null) return List.of();
            LabCell origin = cell(target);
            List<ArmorPiece> candidates = new ArrayList<>();
            for (int l = origin.lightness - 1; l <= origin.lightness + 1; l++) {
                for (int a = origin.a - 1; a <= origin.a + 1; a++) {
                    for (int b = origin.b - 1; b <= origin.b + 1; b++) {
                        List<ArmorPiece> cellPieces = cells.get(new LabCell(l, a, b));
                        if (cellPieces != null) candidates.addAll(cellPieces);
                    }
                }
            }
            return candidates;
        }

        private static LabCell cell(ColorMath.LAB lab) {
            return new LabCell((int) Math.floor(lab.L() / CELL_SIZE), (int) Math.floor(lab.a() / CELL_SIZE),
                (int) Math.floor(lab.b() / CELL_SIZE));
        }
    }

    private static int maximumMatches(List<ChecklistTarget> targets, ChecklistPieceIndex pieceIndex, double limit) {
        List<ChecklistCandidate> candidates = new ArrayList<>();
        for (ChecklistTarget target : targets) {
            ColorMath.LAB targetLab = ColorMath.hexToLab(target.hex);
            for (ArmorPiece piece : pieceIndex.nearby(target.type, targetLab)) {
                double deltaE = ColorMath.calculateDeltaEWithLab(targetLab, ColorMath.hexToLab(piece.getHexcode()));
                if (deltaE <= limit) candidates.add(new ChecklistCandidate(target, piece, deltaE));
            }
        }
        candidates.sort(Comparator.comparingDouble(ChecklistCandidate::deltaE));
        Set<String> usedPieces = new HashSet<>();
        Set<String> assignedTargets = new HashSet<>();
        for (ChecklistCandidate candidate : candidates) {
            String candidateId = pieceId(candidate.piece);
            if (!usedPieces.contains(candidateId) && !assignedTargets.contains(candidate.target.id)) {
                usedPieces.add(candidateId);
                assignedTargets.add(candidate.target.id);
            }
        }
        return assignedTargets.size();
    }

    private static final class ChecklistTargets {
        static Map<String, List<ChecklistTarget>> load() {
            Map<String, List<ChecklistTarget>> result = new LinkedHashMap<>();
            try (InputStream stream = WeightCalculator.class.getResourceAsStream("/data/seymouranalyzer/checklistdata.json")) {
                var root = new Gson().fromJson(new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
                for (var entry : root.getAsJsonObject("categories").entrySet()) {
                    List<ChecklistTarget> targets = new ArrayList<>();
                    int index = 0;
                    for (var value : entry.getValue().getAsJsonArray()) {
                        var object = value.getAsJsonObject();
                        for (var type : object.getAsJsonArray("pieces")) {
                            targets.add(new ChecklistTarget(entry.getKey() + ":" + index + ":" + type.getAsString(),
                                object.get("hex").getAsString(), type.getAsString()));
                        }
                        index++;
                    }
                    result.put(entry.getKey(), targets);
                }
                Map<String, List<ChecklistTarget>> fades = new LinkedHashMap<>();
                for (Map.Entry<String, String> fade : ColorDatabase.getInstance().getFadeDyes().entrySet()) {
                    String category = fade.getKey().replaceFirst(" - Stage.*", "");
                    List<ChecklistTarget> targets = fades.computeIfAbsent(category, ignored -> new ArrayList<>());
                    for (String type : PieceTypeUtil.pieceTypes) {
                        targets.add(new ChecklistTarget(category + ":" + targets.size() + ":" + type,
                            fade.getValue(), type));
                    }
                }
                result.putAll(fades);
            } catch (Exception exception) { SeymourAnalyzer.LOGGER.error("Unable to load checklist weight targets", exception); }
            return result;
        }
    }
    private static Rules rules() {
        Rules cached = rules; if (cached != null) return cached;
        try (InputStream stream = WeightCalculator.class.getResourceAsStream("/data/seymouranalyzer/weight-system.json")) {
            rules = new Gson().fromJson(new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8), Rules.class); return rules;
        } catch (Exception exception) { throw new IllegalStateException("Cannot load generated weight-system.json", exception); }
    }
    private static String pieceId(ArmorPiece piece) {
        if (piece.getUuid() != null && !piece.getUuid().isBlank()) return piece.getUuid();
        return piece.getPieceName() + ":" + piece.getHexcode();
    }
    private static List<Breakdown> summarizeContributions(List<Breakdown> contributions) {
        Map<String, List<Breakdown>> byRule = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Breakdown contribution : contributions) {
            byRule.computeIfAbsent(contribution.type + "\u0000" + contribution.name, ignored -> new ArrayList<>()).add(contribution);
        }
        return byRule.entrySet().stream().map(entry -> {
            List<Breakdown> entries = entry.getValue();
            double weight = entries.stream().mapToDouble(Breakdown::weight).sum();
            String detail = entries.size() + (entries.size() == 1 ? " contribution" : " contributions");
            return new Breakdown(entries.getFirst().name, weight, detail, entries.getFirst().type);
        }).toList();
    }
    private static List<UnlistedMatch> summarizeUnlistedMatches(List<PieceWeight> pieces) {
        Map<String, List<PieceWeight>> byMatch = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (PieceWeight piece : pieces) {
            if (piece.rule.startsWith("Unlisted ")) {
                byMatch.computeIfAbsent(piece.matchName + "\u0000" + piece.targetHex + "\u0000" + piece.tier,
                    ignored -> new ArrayList<>()).add(piece);
            }
        }
        return byMatch.values().stream().map(matches -> {
            PieceWeight match = matches.getFirst();
            return new UnlistedMatch(match.matchName, match.targetHex, match.tier, matches.size());
        }).toList();
    }
    private static String normalizeHex(String value) { return value.replace("#", "").toUpperCase(Locale.ROOT); }
    private static String normalize(String input) {
        String normalized = input.toUpperCase(Locale.ROOT)
            .replace("ARMOUR", "ARMOR")
            .replace("'S", "")
            .replaceFirst("\\s+/.*$", "")
            .replace("DYE", "")
            .replaceAll("[^A-Z0-9]", "");
        return normalized
            .replace("BERSERKER", "BESERKER")
            .replace("COMMANDER", "COMANDER")
            .replace("MELODYSSHOES", "MELODYSHOES")
            .replace("GREY", "GRAY")
            .replace("ARMOR3P", "3P")
            .replace("ARMOR", "")
            .replace("RIFTBOOTS", "")
            .replace("112", "")
            .replace("PURE", "");
    }
}
