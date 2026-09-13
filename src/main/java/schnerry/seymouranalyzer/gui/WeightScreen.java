package schnerry.seymouranalyzer.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.data.ArmorPiece;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.data.WeightCalculator;
import schnerry.seymouranalyzer.util.SkyBlockProfileTracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.lwjgl.glfw.GLFW;

/** Workbook-based collection value calculator, opened with {@code /seymour weight}. */
public final class WeightScreen extends ModScreen {
    private static final Map<String, CachedResult> RESULTS_BY_PROFILE = new ConcurrentHashMap<>();
    private String selectedProfileId = SkyBlockProfileTracker.ALL_PROFILES;
    private boolean profileDropdownOpen;
    private volatile boolean calculating;
    private volatile String phase = "Choose a profile and calculate";
    private volatile int calculationProgress;
    private volatile WeightCalculator.Result result;
    private int scrollOffset;
    private ViewMode viewMode = ViewMode.PIECES;
    private boolean draggingScrollbar;
    private String sortColumn = "weight";
    private boolean sortAscending;
    private EditBox pieceSearchField;
    private String activePieceSearch = "";
    private PieceContextMenu contextMenu;
    private static final int HEADER_Y = 75;
    private static final int START_Y = 95;
    private static final int ROW_HEIGHT = 20;
    private static final int FOOTER_HEIGHT = 40;
    private enum ViewMode { PIECES, SUMMARY }
    private record CachedResult(String collectionFingerprint, WeightCalculator.Result result) {}
    private record PieceContextMenu(WeightCalculator.PieceWeight piece, int x, int y) {}

    public WeightScreen(Screen parent) { super(Component.literal("Collection Weight"), parent); }

    @Override protected void init() {
        super.init();
        if (result == null) result = cachedResultForCurrentCollection();
        Button profile = Button.builder(Component.literal("Profile: " + SkyBlockProfileTracker.getProfileLabel(selectedProfileId)), button -> {
            profileDropdownOpen = !profileDropdownOpen; rebuildWidgets();
        }).bounds(20, 10, 130, 20).build();
        addRenderableWidget(profile);
        Button calculate = Button.builder(Component.literal(calculating ? "§7Calculating..."
            : result == null ? "§aCalculate Weight" : "§eRecalculate Weight"), button -> calculate())
            .bounds(width - 165, 10, 145, 20).build();
        calculate.active = !calculating;
        addRenderableWidget(calculate);
        addRenderableWidget(Button.builder(Component.literal("§eGo to Database"),
            button -> minecraft.gui.setScreen(new DatabaseScreen(this)))
            .bounds(width - 300, 10, 130, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§eView: " + (viewMode == ViewMode.PIECES ? "Pieces" : "Summary")),
            button -> {
                viewMode = viewMode == ViewMode.PIECES ? ViewMode.SUMMARY : ViewMode.PIECES;
                scrollOffset = 0;
                rebuildWidgets();
            }).bounds(155, 10, 125, 20).build());
        if (viewMode == ViewMode.PIECES) {
            if (pieceSearchField != null) activePieceSearch = pieceSearchField.getValue();
            pieceSearchField = new EditBox(font, width - 280, 35, 260, 20, Component.literal("Search"));
            pieceSearchField.setMaxLength(50);
            pieceSearchField.setHint(Component.literal("Search pieces, hex, match, rule..."));
            pieceSearchField.setValue(activePieceSearch);
            pieceSearchField.setResponder(search -> {
                activePieceSearch = search;
                scrollOffset = 0;
            });
            addRenderableWidget(pieceSearchField);
        }
        if (profileDropdownOpen) for (Button option : ProfileDropdown.createOptions(20, 30, 130, selectedProfileId, profileId -> {
            selectedProfileId = profileId;
            profileDropdownOpen = false;
            result = cachedResultForCurrentCollection();
            scrollOffset = 0;
            rebuildWidgets();
        })) addRenderableWidget(option);
    }

    private void calculate() {
        if (calculating) return;
        calculating = true; result = null; phase = "Preparing collection"; calculationProgress = 0; rebuildWidgets();
        String calculationProfileId = selectedProfileId;
        List<ArmorPiece> selected = CollectionManager.getInstance().getCollection().values().stream()
            .filter(piece -> SkyBlockProfileTracker.ALL_PROFILES.equals(calculationProfileId)
                || calculationProfileId.equals(piece.getProfileId())).toList();
        String collectionFingerprint = collectionFingerprint(selected);
        CompletableFuture.runAsync(() -> {
            try {
                WeightCalculator.Result calculated = WeightCalculator.calculate(selected, update -> {
                    phase = update.phase();
                    calculationProgress = update.percent();
                });
                RESULTS_BY_PROFILE.put(calculationProfileId, new CachedResult(collectionFingerprint, calculated));
                logUnlistedMatches(calculated);
                minecraft.execute(() -> showUnlistedMatchAlert(calculated));
                if (calculationProfileId.equals(selectedProfileId)) result = calculated;
                phase = "Calculation complete";
            }
            catch (Exception exception) { phase = "Calculation failed; see log"; SeymourAnalyzer.LOGGER.error("[Weight] Calculation failed", exception); }
            finally { calculating = false; if (minecraft != null) minecraft.execute(this::rebuildWidgets); }
        });
    }

    @Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        String title = "§l§nCollection Weight";
        graphics.text(font, title, width / 2 - font.width(title) / 2, 10, 0xFFFFFFFF);
        if (result == null) {
            graphics.centeredText(font, "§7" + phase, width / 2, 35, 0xFFFFFFFF);
        }
        if (calculating) drawProgress(graphics);
        if (result == null && !calculating) {
            graphics.centeredText(font, "§7Base rules, bonuses, checklists, and Best Sets are included.",
                width / 2, START_Y, 0xFFFFFFFF);
        }
        if (result != null) drawResults(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, delta);
        if (contextMenu != null) drawContextMenu(graphics, mouseX, mouseY);
        if (profileDropdownOpen) ProfileDropdown.renderModal(graphics, font, width, height, 20, 10, 130, 20, selectedProfileId);
    }

    private void drawProgress(GuiGraphicsExtractor graphics) {
        int x = width / 2 - 90, y = 50, w = 180;
        graphics.fill(x, y, x + w, y + 8, 0xFF303030);
        int fill = calculationProgress;
        graphics.fill(x, y, x + w * fill / 100, y + 8, 0xFF00A000);
        graphics.text(font, "§e" + fill + "%", x + w + 6, y - 2, 0xFFFFFFFF);
    }

    private void drawResults(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.centeredText(font, "§aTotal Weight: §f" + String.format("%.1f", result.total())
            + "   §fBase: " + String.format("%.1f", result.baseTotal())
            + "   §aBonuses: +" + String.format("%.1f", result.bonusTotal())
            + "   §7(" + result.pieces().size() + " pieces)", width / 2, 55, 0xFFFFFFFF);
        if (viewMode == ViewMode.SUMMARY) {
            drawSummary(graphics);
            return;
        }
        List<WeightCalculator.PieceWeight> pieces = filteredPieces();
        pieces.sort(getPieceComparator());
        graphics.text(font, header("Name", "name"), 20, HEADER_Y, 0xFFAAAAAA);
        graphics.text(font, header("Hex", "hex"), 200, HEADER_Y, 0xFFAAAAAA);
        graphics.text(font, header("Match", "match"), 300, HEADER_Y, 0xFFAAAAAA);
        graphics.text(font, header("Rule", "rule"), 540, HEADER_Y, 0xFFAAAAAA);
        graphics.text(font, header("Weight", "weight"), 710, HEADER_Y, 0xFFAAAAAA);
        graphics.fill(20, HEADER_Y + 12, width - 20, HEADER_Y + 13, 0xFF646464);

        int maxVisibleRows = Math.max(1, (height - START_Y - FOOTER_HEIGHT) / ROW_HEIGHT);
        int endIndex = Math.min(scrollOffset + maxVisibleRows, pieces.size());
        int y = START_Y;
        for (int index = scrollOffset; index < endIndex && y < height - FOOTER_HEIGHT; index++) {
            WeightCalculator.PieceWeight row = pieces.get(index);
            boolean expanded = isShiftHeld() && isMouseOver(mouseY, y, ROW_HEIGHT);
            drawPieceRow(graphics, row, y, expanded);
            y += expanded ? ROW_HEIGHT * 3 : ROW_HEIGHT;
        }
        if (pieces.size() > maxVisibleRows) {
            ScrollbarRenderer.renderVerticalScrollbar(graphics, width - 15, START_Y, maxVisibleRows * ROW_HEIGHT,
                scrollOffset, pieces.size(), maxVisibleRows);
        }
        String footer = pieces.isEmpty() ? "No matching pieces" : "Showing " + (scrollOffset + 1) + "-" + endIndex
            + " of " + pieces.size();
        graphics.centeredText(font, footer, width / 2, height - 25, 0xFF888888);
        graphics.centeredText(font, "Hold SHIFT over a piece to show its score details", width / 2, height - 10, 0xFF888888);
    }

    private void drawPieceRow(GuiGraphicsExtractor graphics, WeightCalculator.PieceWeight row, int y, boolean expanded) {
        int highlight = row.fake() ? 0x48AA0000 : tierHighlight(row.tier());
        if (highlight != 0) graphics.fill(530, y - 2, width - 20, y + ROW_HEIGHT - 2, highlight);

        String hex = row.piece().getHexcode().replace("#", "").toUpperCase();
        var rgb = schnerry.seymouranalyzer.util.ColorMath.hexToRgb(hex);
        graphics.fill(200, y, 285, y + 16, 0xFF000000 | (rgb.r() << 16) | (rgb.g() << 8) | rgb.b());
        graphics.text(font, trim(row.piece().getPieceName(), 25), 20, y + 4, 0xFFFFFFFF);
        graphics.text(font, hex, 202, y + 4,
            schnerry.seymouranalyzer.util.ColorMath.isColorDark(hex) ? 0xFFFFFFFF : 0xFF000000);
        graphics.text(font, trim(row.matchName(), 32), 300, y + 4, 0xFF55FFFF);
        graphics.text(font, (row.fake() ? "§cFake: " : "") + trim(row.rule(), 22), 540, y + 4, 0xFFFFFFFF);
        String baseWeight = String.format("%.1f", row.baseWeight());
        graphics.text(font, baseWeight, 710, y + 4, 0xFFFFFFFF);
        double bonusWeight = individualBonusWeight(row);
        if (bonusWeight > 0) {
            graphics.text(font, " +" + String.format("%.1f", bonusWeight), 710 + font.width(baseWeight), y + 4, 0xFF55FF55);
        }

        if (expanded) {
            graphics.text(font, "§7Rule: §f" + row.rule() + (row.fake() ? " §c(If Fake)" : "")
                + "  §7Match: §f" + row.matchName() + " §7#" + row.targetHex() + " T" + row.tier(), 30, y + 23,
                0xFFFFFFFF);
            String bonuses = row.bonuses().isEmpty() ? "§fNone" : row.bonuses().stream()
                .map(bonus -> "§a" + bonus.name() + " +" + String.format("%.1f", bonus.weight()))
                .reduce((left, right) -> left + "§7, " + right).orElse("§fNone");
            graphics.text(font, "§7Bonuses: " + trim(bonuses, 105),
                30, y + 43, 0xFFFFFFFF);
        }
    }
    private void drawSummary(GuiGraphicsExtractor graphics) {
        int visible = Math.max(1, (height - START_Y - FOOTER_HEIGHT - 20) / ROW_HEIGHT);
        int columnWidth = (width - 80) / 3;
        List<WeightCalculator.Breakdown> baseRules = summaryRows(WeightCalculator.ContributionType.BASE_RULE);
        List<WeightCalculator.Breakdown> pieceBonuses = summaryRows(WeightCalculator.ContributionType.PIECE_BONUS);
        List<WeightCalculator.Breakdown> collectionBonuses = summaryRows(WeightCalculator.ContributionType.COLLECTION_BONUS);
        drawSummarySection(graphics, 20, columnWidth, "Base Rules", baseRules, scrollOffset, visible, 0xFFFFFFFF);
        drawSummarySection(graphics, 40 + columnWidth, columnWidth, "Piece Bonuses", pieceBonuses, 0, visible, 0xFF55FF55);
        drawSummarySection(graphics, 60 + columnWidth * 2, columnWidth, "Collection Bonuses", collectionBonuses, 0, visible, 0xFF55FF55);
        if (baseRules.size() > visible) {
            ScrollbarRenderer.renderVerticalScrollbar(graphics, currentScrollbarX(), START_Y, visible * ROW_HEIGHT,
                scrollOffset, baseRules.size(), visible);
        }
        graphics.centeredText(font, "Base rules: " + (scrollOffset + 1) + "-" + Math.min(scrollOffset + visible, baseRules.size())
            + " of " + baseRules.size(), width / 2, height - 25, 0xFF888888);
        graphics.centeredText(font, "§fBase: " + String.format("%.1f", result.baseTotal())
            + "   §aBonuses: +" + String.format("%.1f", result.bonusTotal()), width / 2, height - 10, 0xFFFFFFFF);
    }

    private void drawSummarySection(GuiGraphicsExtractor graphics, int x, int sectionWidth, String title,
                                    List<WeightCalculator.Breakdown> rows, int offset, int visible, int weightColor) {
        int bottom = START_Y + visible * ROW_HEIGHT;
        graphics.fill(x, HEADER_Y, x + sectionWidth, bottom, 0x40202020);
        graphics.centeredText(font, "§l" + title, x + sectionWidth / 2, HEADER_Y + 3, 0xFFFFFFFF);
        for (int i = 0; i < visible && offset + i < rows.size(); i++) {
            WeightCalculator.Breakdown row = rows.get(offset + i);
            int y = START_Y + i * ROW_HEIGHT;
            graphics.text(font, trim(row.name(), 23), x + 8, y + 3, 0xFFFFFFFF);
            String score = String.format("%.1f", row.weight());
            graphics.text(font, score, x + sectionWidth - font.width(score) - 8, y + 3, weightColor);
            double each = row.weight() / contributionCount(row.detail());
            graphics.text(font, row.detail() + " x " + String.format("%.1f", each), x + 8, y + 13, 0xFF888888);
        }
    }

    private static int contributionCount(String detail) {
        int separator = detail.indexOf(' ');
        return separator < 0 ? 1 : Integer.parseInt(detail.substring(0, separator));
    }

    private List<WeightCalculator.Breakdown> summaryRows(WeightCalculator.ContributionType type) {
        return result.breakdowns().stream().filter(row -> row.type() == type)
            .sorted(Comparator.comparingDouble(WeightCalculator.Breakdown::weight).reversed()).toList();
    }
    private List<WeightCalculator.PieceWeight> filteredPieces() {
        List<WeightCalculator.PieceWeight> pieces = new ArrayList<>(result.pieces());
        String search = activePieceSearch.trim();
        if (!search.isEmpty()) {
            String searchLower = search.toLowerCase();
            Pattern wildcard = wildcardPattern(search);
            pieces.removeIf(row -> !matchesSearch(row, searchLower, wildcard));
        }

        pieces.sort(getPieceComparator());
        return pieces;
    }

    private static double individualBonusWeight(WeightCalculator.PieceWeight row) {
        return row.bonuses().stream().mapToDouble(WeightCalculator.ScoreBonus::weight).sum();
    }

    private static boolean matchesSearch(WeightCalculator.PieceWeight row, String searchLower, Pattern wildcard) {
        String hex = row.piece().getHexcode().replace("#", "").toUpperCase();
        if (wildcard != null && wildcard.matcher(hex).matches()) return true;
        return row.piece().getPieceName().toLowerCase().contains(searchLower)
            || hex.toLowerCase().contains(searchLower)
            || row.matchName().toLowerCase().contains(searchLower)
            || row.targetHex().toLowerCase().contains(searchLower)
            || row.rule().toLowerCase().contains(searchLower)
            || row.bonuses().stream().anyMatch(bonus -> bonus.name().toLowerCase().contains(searchLower));
    }

    private static Pattern wildcardPattern(String search) {
        String normalized = search.toUpperCase().replace("#", "");
        if (normalized.length() != 6 || !normalized.matches("[0-9A-FWXYZ]+")) return null;
        StringBuilder regex = new StringBuilder("^");
        Map<Character, Integer> groups = new java.util.HashMap<>();
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character == 'X') regex.append("[0-9A-F]");
            else if (character == 'W' || character == 'Y' || character == 'Z') {
                Integer group = groups.get(character);
                if (group == null) {
                    groups.put(character, groups.size() + 1);
                    regex.append("([0-9A-F])");
                } else regex.append("\\").append(group);
            } else regex.append(character);
        }
        return Pattern.compile(regex.append("$").toString());
    }
    private static String trim(String text, int limit) { return text.length() <= limit ? text : text.substring(0, limit - 1) + "…"; }
    private boolean isShiftHeld() {
        Window window = minecraft.getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (result != null) { int visible = Math.max(1, (height - START_Y - FOOTER_HEIGHT - (viewMode == ViewMode.SUMMARY ? 20 : 0)) / ROW_HEIGHT); int count = rowCount(); int max = Math.max(0, count - visible); scrollOffset = Math.clamp(scrollOffset + (vertical < 0 ? 1 : -1), 0, max); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean mouseClicked(MouseButtonEvent click, boolean outOfBounds) {
        if (profileDropdownOpen) { if (click.button() == 0) ProfileDropdown.getSelectedOption(click.x(), click.y(), 20, 10, 130, 20).ifPresent(id -> selectedProfileId = id); profileDropdownOpen = false; rebuildWidgets(); return true; }
        if (contextMenu != null && click.button() == 0) return handleContextMenuClick(click.x(), click.y());
        if (result != null && viewMode == ViewMode.PIECES && click.button() == 1 && openContextMenu(click.x(), click.y())) return true;
        if (result != null && viewMode == ViewMode.PIECES && click.button() == 0
            && click.y() >= HEADER_Y - 5 && click.y() <= START_Y - 5) {
            String clickedColumn = getHeaderColumn(click.x());
            if (clickedColumn != null) {
                if (clickedColumn.equals(sortColumn)) {
                    sortAscending = !sortAscending;
                } else {
                    sortColumn = clickedColumn;
                    sortAscending = true;
                }
                scrollOffset = 0;
                return true;
            }
        }
        if (result != null && click.button() == 0) {
            int visible = Math.max(1, (height - START_Y - FOOTER_HEIGHT - (viewMode == ViewMode.SUMMARY ? 20 : 0)) / ROW_HEIGHT);
            if (ScrollbarRenderer.isMouseOverScrollbar(click.x(), click.y(), currentScrollbarX(), START_Y, visible * ROW_HEIGHT)) {
                draggingScrollbar = true;
                updateScrollbarPosition(click.y(), visible);
                return true;
            }
        }
        return super.mouseClicked(click, outOfBounds);
    }

    private boolean openContextMenu(double mouseX, double mouseY) {
        int maxVisibleRows = Math.max(1, (height - START_Y - FOOTER_HEIGHT) / ROW_HEIGHT);
        List<WeightCalculator.PieceWeight> pieces = filteredPieces();
        int endIndex = Math.min(scrollOffset + maxVisibleRows, pieces.size());
        int y = START_Y;
        for (int index = scrollOffset; index < endIndex && y < height - FOOTER_HEIGHT; index++) {
            WeightCalculator.PieceWeight piece = pieces.get(index);
            int rowHeight = isShiftHeld() && isMouseOver((int) mouseY, y, ROW_HEIGHT) ? ROW_HEIGHT * 3 : ROW_HEIGHT;
            if (isMouseOver((int) mouseY, y, rowHeight)) {
                contextMenu = new PieceContextMenu(piece, Math.min((int) mouseX, width - 145), Math.min((int) mouseY, height - 25));
                return true;
            }
            y += rowHeight;
        }
        return false;
    }

    private void drawContextMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = contextMenu.x, y = contextMenu.y, menuWidth = 140, menuHeight = 20;
        graphics.fill(x, y, x + menuWidth, y + menuHeight, 0xF0282828);
        graphics.fill(x, y, x + menuWidth, y + 1, 0xFF646464);
        graphics.fill(x, y + menuHeight - 1, x + menuWidth, y + menuHeight, 0xFF646464);
        graphics.fill(x, y, x + 1, y + menuHeight, 0xFF646464);
        graphics.fill(x + menuWidth - 1, y, x + menuWidth, y + menuHeight, 0xFF646464);
        if (mouseX >= x && mouseX <= x + menuWidth && mouseY >= y && mouseY <= y + menuHeight) {
            graphics.fill(x + 1, y + 1, x + menuWidth - 1, y + menuHeight - 1, 0x80505050);
        }
        graphics.text(font, "Find in Database", x + 5, y + 6, 0xFFFFFFFF);
    }

    private boolean handleContextMenuClick(double mouseX, double mouseY) {
        PieceContextMenu menu = contextMenu;
        contextMenu = null;
        if (mouseX < menu.x || mouseX > menu.x + 140 || mouseY < menu.y || mouseY > menu.y + 20) return true;
        DatabaseScreen database = new DatabaseScreen(this);
        database.setInitialSearch(menu.piece.piece().getPieceName());
        minecraft.gui.setScreen(database);
        return true;
    }

    @Override public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (draggingScrollbar && click.button() == 0 && result != null) {
            int visible = Math.max(1, (height - START_Y - FOOTER_HEIGHT - (viewMode == ViewMode.SUMMARY ? 20 : 0)) / ROW_HEIGHT);
            updateScrollbarPosition(click.y(), visible);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override public boolean mouseReleased(MouseButtonEvent click) {
        if (draggingScrollbar && click.button() == 0) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    private void updateScrollbarPosition(double mouseY, int visible) {
        int count = rowCount();
        int maxScroll = Math.max(0, count - visible);
        scrollOffset = Math.clamp(ScrollbarRenderer.calculateScrollFromDrag(mouseY, START_Y, visible * ROW_HEIGHT,
            count, visible), 0, maxScroll);
    }

    private int rowCount() {
        return switch (viewMode) {
            case PIECES -> filteredPieces().size();
            case SUMMARY -> summaryRows(WeightCalculator.ContributionType.BASE_RULE).size();
        };
    }

    private int currentScrollbarX() {
        if (viewMode == ViewMode.SUMMARY) return 20 + (width - 80) / 3 - 8;
        return width - 15;
    }

    private WeightCalculator.Result cachedResultForCurrentCollection() {
        CachedResult cached = RESULTS_BY_PROFILE.get(selectedProfileId);
        if (cached == null || !cached.collectionFingerprint.equals(collectionFingerprint(selectedPieces()))) return null;
        return cached.result;
    }

    private List<ArmorPiece> selectedPieces() {
        return CollectionManager.getInstance().getCollection().values().stream()
            .filter(piece -> SkyBlockProfileTracker.ALL_PROFILES.equals(selectedProfileId)
                || selectedProfileId.equals(piece.getProfileId())).toList();
    }

    private static String collectionFingerprint(List<ArmorPiece> pieces) {
        return pieces.stream()
            .map(piece -> String.valueOf(piece.getUuid()) + "|" + piece.getPieceName() + "|" + piece.getHexcode() + "|" + piece.getProfileId())
            .sorted()
            .reduce("", (hash, piece) -> Integer.toHexString((hash + "\n" + piece).hashCode()));
    }

    private static void logUnlistedMatches(WeightCalculator.Result calculated) {
        if (calculated.unlistedMatches().isEmpty()) return;
        SeymourAnalyzer.LOGGER.info("[Weight] Unlisted matches ({}):", calculated.unlistedMatches().size());
        for (WeightCalculator.UnlistedMatch match : calculated.unlistedMatches()) {
            SeymourAnalyzer.LOGGER.info("[Weight] {} | #{} | T{} | {} piece(s)",
                match.name(), match.hex(), match.tier(), match.pieces());
        }
    }

    private void showUnlistedMatchAlert(WeightCalculator.Result calculated) {
        if (calculated.unlistedMatches().isEmpty() || minecraft.player == null) return;
        String report = calculated.unlistedMatches().stream()
            .map(match -> match.name() + " | #" + match.hex() + " | T" + match.tier() + " | " + match.pieces() + " piece(s)")
            .reduce("Unlisted matches (" + calculated.unlistedMatches().size() + "):", (left, right) -> left + "\n" + right);
        minecraft.player.sendSystemMessage(Component.literal("§cFound unlisted matches. Click this message to copy and report it to Schnerry on Discord")
            .withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(report))));
    }

    private static boolean isMouseOver(int mouseY, int y, int height) {
        return mouseY >= y && mouseY < y + height;
    }

    private String header(String label, String column) {
        if (!column.equals(sortColumn)) return label;
        return label + (sortAscending ? " ↓" : " ↑");
    }

    private Comparator<WeightCalculator.PieceWeight> getPieceComparator() {
        Comparator<WeightCalculator.PieceWeight> comparator = switch (sortColumn) {
            case "name" -> Comparator.comparing(row -> row.piece().getPieceName(), String.CASE_INSENSITIVE_ORDER);
            case "hex" -> Comparator.comparing(row -> row.piece().getHexcode(), String.CASE_INSENSITIVE_ORDER);
            case "match" -> Comparator.comparing(WeightCalculator.PieceWeight::matchName, String.CASE_INSENSITIVE_ORDER);
            case "rule" -> Comparator.comparing(WeightCalculator.PieceWeight::rule, String.CASE_INSENSITIVE_ORDER);
            case "weight" -> Comparator.comparingDouble(row -> row.baseWeight() + individualBonusWeight(row));
            default -> Comparator.comparing(WeightCalculator.PieceWeight::matchName, String.CASE_INSENSITIVE_ORDER);
        };
        return sortAscending ? comparator : comparator.reversed();
    }

    private String getHeaderColumn(double mouseX) {
        if (mouseX >= 20 && mouseX < 200) return "name";
        if (mouseX >= 200 && mouseX < 300) return "hex";
        if (mouseX >= 300 && mouseX < 540) return "match";
        if (mouseX >= 540 && mouseX < 710) return "rule";
        if (mouseX >= 710 && mouseX < width - 20) return "weight";
        return null;
    }

    private static int tierHighlight(int tier) {
        return switch (tier) {
            case 0 -> 0x48FF0000;
            case 1 -> 0x48FF69B4;
            case 2 -> 0x48FFA500;
            default -> 0;
        };
    }
}
