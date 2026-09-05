package schnerry.seymouranalyzer.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.config.ClothConfig;
import schnerry.seymouranalyzer.data.ArmorPiece;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.util.ColorMath;
import schnerry.seymouranalyzer.util.PieceTypeUtil;
import schnerry.seymouranalyzer.util.SkyBlockProfileTracker;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Best Sets GUI - finds the truly optimal best matching 4-piece armor sets
 * <p>
 * Algorithm:
 * 1. Generates ALL valid 4-piece combinations (where all pairwise ΔE ≤ 5.0)
 * 2. Sorts all combinations by average ΔE (best to worst)
 * 3. Greedily selects non-overlapping sets from the sorted list
 * <p>
 * This guarantees the best possible sets based on average ΔE,
 * with each piece used only once across all selected sets.
 */
public class BestSetsScreen extends ModScreen {
    private List<ArmorSet> bestSets = new ArrayList<>();
    private boolean isCalculating = false;
    private int calculationProgress = 0;
    private int scrollOffset = 0;
    private ContextMenu contextMenu = null;
    private String selectedProfileId = SkyBlockProfileTracker.ALL_PROFILES;
    private boolean profileDropdownOpen;
    private boolean profileSelected;
    private boolean hasCalculated;

    // Static cache to persist results across GUI opens/closes
    private static List<ArmorSet> cachedBestSets = null;
    private static int cachedCollectionSize = -1;
    private static long cacheTimestamp = 0;
    private static boolean cachedUnderDeltaTwoOnly;
    private static String cachedProfileId;

    private static final int MAX_SETS = 100;
    private static final double MAX_DELTA_E = 5.0;
    private static final int ROW_HEIGHT = 80;
    private static final int START_Y = 90;
    private static final long CACHE_VALIDITY_MS = 300000; // 5 minutes

    public BestSetsScreen(Screen parent) {
        super(Component.literal("Best Matching Sets"), parent);

        // Load from cache if valid
        int currentSize = CollectionManager.getInstance().getCollection().size();
        long currentTime = System.currentTimeMillis();

        if (cachedBestSets != null &&
            cachedCollectionSize == currentSize &&
            cachedUnderDeltaTwoOnly == ClothConfig.getInstance().isBestSetsUnderDeltaTwoOnly() &&
            Objects.equals(cachedProfileId, selectedProfileId) &&
            (currentTime - cacheTimestamp) < CACHE_VALIDITY_MS) {
            bestSets = new ArrayList<>(cachedBestSets);
            SeymourAnalyzer.LOGGER.info("[Best Sets] Loaded {} sets from cache", bestSets.size());
        }
    }

    @Override
    protected void init() {
        super.init();

        // Calculate button - updates text based on isCalculating state
        Button calculateButton = Button.builder(
            Component.literal(!profileSelected ? "§7Select Profile First"
                : isCalculating ? "§7Calculating..." : "§aCalculate Best Sets"),
            button -> {
                if (profileSelected && !isCalculating) {
                    calculateBestSets();
                }
            })
            .bounds(this.width / 2 - 75, 40, 150, 25)
            .build();
        calculateButton.active = profileSelected && !isCalculating;
        this.addRenderableWidget(calculateButton);

        Button profileSelector = Button.builder(
            Component.literal("Profile: " + SkyBlockProfileTracker.getProfileLabel(selectedProfileId)),
            button -> {
                profileDropdownOpen = !profileDropdownOpen;
                this.rebuildWidgets();
            })
            .bounds(20, 40, 145, 25)
            .build();
        this.addRenderableWidget(profileSelector);
        if (profileDropdownOpen) {
            for (Button option : ProfileDropdown.createOptions(20, 65, 145, selectedProfileId, profileId -> {
                selectedProfileId = profileId;
                profileDropdownOpen = false;
                profileSelected = true;
                bestSets.clear();
                scrollOffset = 0;
                hasCalculated = false;
                this.rebuildWidgets();
            })) {
                this.addRenderableWidget(option);
            }
        }
    }

    @Override
    public void render(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        // Title
        String title = "§l§nBest Matching Sets";
        int titleWidth = this.font.width(title);
        guiGraphics.text(this.font, title, this.width / 2 - titleWidth / 2, 10, 0xFFFFFFFF);

        boolean underDeltaTwoOnly = ClothConfig.getInstance().isBestSetsUnderDeltaTwoOnly();
        String modeText = underDeltaTwoOnly
            ? "§aMode: Average ΔE < 2.0"
            : "§7Mode: Top 100 sets";
        int modeWidth = this.font.width(modeText);
        guiGraphics.text(this.font, modeText, this.width / 2 - modeWidth / 2, 24, 0xFFFFFFFF);

        // Progress bar
        if (isCalculating && calculationProgress > 0) {
            int progressBarX = this.width / 2 - 100;
            int progressBarY = 70;
            int progressBarWidth = 200;
            int progressBarHeight = 8;

            // Background
            guiGraphics.fill(progressBarX, progressBarY, progressBarX + progressBarWidth, progressBarY + progressBarHeight, 0xFF282828);

            // Progress fill
            int fillWidth = (progressBarWidth * calculationProgress) / 100;
            guiGraphics.fill(progressBarX, progressBarY, progressBarX + fillWidth, progressBarY + progressBarHeight, 0xFF00C800);

            // Border
            guiGraphics.fill(progressBarX - 1, progressBarY - 1, progressBarX + progressBarWidth + 1, progressBarY, 0xFFFFFFFF);
            guiGraphics.fill(progressBarX - 1, progressBarY + progressBarHeight, progressBarX + progressBarWidth + 1, progressBarY + progressBarHeight + 1, 0xFFFFFFFF);
            guiGraphics.fill(progressBarX - 1, progressBarY, progressBarX, progressBarY + progressBarHeight, 0xFFFFFFFF);
            guiGraphics.fill(progressBarX + progressBarWidth, progressBarY, progressBarX + progressBarWidth + 1, progressBarY + progressBarHeight, 0xFFFFFFFF);

            // Percentage
            String percentText = "§e" + calculationProgress + "%";
            int percentWidth = this.font.width(percentText);
            guiGraphics.text(this.font, percentText, progressBarX + (progressBarWidth - percentWidth) / 2, progressBarY + 10, 0xFFFFFFFF);
        }

        // Draw sets or instructions
        if (bestSets.isEmpty() && !isCalculating) {
            if (hasCalculated) {
                String noSetsMessage = "§7No optimal sets found for the selected profile";
                guiGraphics.text(this.font, noSetsMessage, this.width / 2 - this.font.width(noSetsMessage) / 2,
                    110, 0xFF888888);
            } else {
                String line1 = "§7Click button to calculate best matching sets";
                String line2 = "§7This will find 4-piece sets with lowest color difference";
                String line3 = "§7Each piece is used only ONCE across all sets";

                guiGraphics.text(this.font, line1, this.width / 2 - this.font.width(line1) / 2, 100, 0xFF888888);
                guiGraphics.text(this.font, line2, this.width / 2 - this.font.width(line2) / 2, 115, 0xFF888888);
                guiGraphics.text(this.font, line3, this.width / 2 - this.font.width(line3) / 2, 130, 0xFF888888);
            }
        } else if (!bestSets.isEmpty()) {
            String setsInfo = underDeltaTwoOnly
                ? "§7" + bestSets.size() + " sets with average ΔE < 2.0 - Each piece used once"
                : "§7Top " + bestSets.size() + " sets (ΔE ≤ " + MAX_DELTA_E + ") - Each piece used once";
            guiGraphics.text(this.font, setsInfo, 20, START_Y - 10, 0xFF888888);

            // Draw visible sets
            int maxVisible = 5;
            int visibleCount = Math.min(maxVisible, bestSets.size() - scrollOffset);

            for (int i = 0; i < visibleCount; i++) {
                ArmorSet set = bestSets.get(scrollOffset + i);
                int rowY = START_Y + (i * ROW_HEIGHT);
                drawSetRow(guiGraphics, set, rowY, scrollOffset + i + 1);
            }

            // Scroll info
            if (bestSets.size() > maxVisible) {
                String scrollText = "§7(" + (scrollOffset + 1) + "-" + Math.min(scrollOffset + maxVisible, bestSets.size()) +
                                   " of " + bestSets.size() + ") §eScroll for more";
                guiGraphics.text(this.font, scrollText, 20, START_Y + (maxVisible * ROW_HEIGHT) + 10, 0xFF888888);
            }
        }

        // Draw context menu
        if (contextMenu != null) {
            drawContextMenu(guiGraphics, mouseX, mouseY);
        }

        super.render(guiGraphics, mouseX, mouseY, delta);
        if (profileDropdownOpen) {
            ProfileDropdown.renderModal(guiGraphics, this.font, this.width, this.height, 20, 40, 145, 25, selectedProfileId);
        }
    }

    private void drawSetRow(GuiGraphicsExtractor guiGraphics, ArmorSet set, int rowY, int rank) {
        // Rank
        guiGraphics.text(this.font, "§e#" + rank, 20, rowY, 0xFFFFFFFF);

        // Color squares (2x2 grid)
        int boxX = 20;
        int boxY = rowY + 15;
        int boxSize = 24;

        ColorMath.RGB helmetRgb = ColorMath.hexToRgb(set.helmet.getHexcode());
        ColorMath.RGB chestRgb = ColorMath.hexToRgb(set.chestplate.getHexcode());
        ColorMath.RGB legsRgb = ColorMath.hexToRgb(set.leggings.getHexcode());
        ColorMath.RGB bootsRgb = ColorMath.hexToRgb(set.boots.getHexcode());

        // Draw color boxes
        guiGraphics.fill(boxX, boxY, boxX + boxSize, boxY + boxSize,
            0xFF000000 | (helmetRgb.r() << 16) | (helmetRgb.g() << 8) | helmetRgb.b());
        guiGraphics.fill(boxX + boxSize, boxY, boxX + boxSize * 2, boxY + boxSize,
            0xFF000000 | (chestRgb.r() << 16) | (chestRgb.g() << 8) | chestRgb.b());
        guiGraphics.fill(boxX, boxY + boxSize, boxX + boxSize, boxY + boxSize * 2,
            0xFF000000 | (legsRgb.r() << 16) | (legsRgb.g() << 8) | legsRgb.b());
        guiGraphics.fill(boxX + boxSize, boxY + boxSize, boxX + boxSize * 2, boxY + boxSize * 2,
            0xFF000000 | (bootsRgb.r() << 16) | (bootsRgb.g() << 8) | bootsRgb.b());

        // Border around 2x2 grid
        int totalBoxSize = boxSize * 2;
        guiGraphics.fill(boxX - 1, boxY - 1, boxX + totalBoxSize + 1, boxY, 0xFFFFFFFF);
        guiGraphics.fill(boxX - 1, boxY + totalBoxSize, boxX + totalBoxSize + 1, boxY + totalBoxSize + 1, 0xFFFFFFFF);
        guiGraphics.fill(boxX - 1, boxY, boxX, boxY + totalBoxSize, 0xFFFFFFFF);
        guiGraphics.fill(boxX + totalBoxSize, boxY, boxX + totalBoxSize + 1, boxY + totalBoxSize, 0xFFFFFFFF);

        // Labels on boxes
        guiGraphics.text(this.font, "§8H", boxX + 3, boxY + 5, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§8C", boxX + boxSize + 3, boxY + 5, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§8L", boxX + 3, boxY + boxSize + 5, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§8B", boxX + boxSize + 3, boxY + boxSize + 5, 0xFFFFFFFF);

        // Piece info (left side)
        guiGraphics.text(this.font, "§7Helmet: §f" + set.helmet.getPieceName(), 80, rowY, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§8  #" + set.helmet.getHexcode().toUpperCase(), 80, rowY + 12, 0xFFFFFFFF);

        guiGraphics.text(this.font, "§7Chest: §f" + set.chestplate.getPieceName(), 80, rowY + 24, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§8  #" + set.chestplate.getHexcode().toUpperCase(), 80, rowY + 36, 0xFFFFFFFF);

        // Piece info (middle)
        guiGraphics.text(this.font, "§7Legs: §f" + set.leggings.getPieceName(), 420, rowY, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§8  #" + set.leggings.getHexcode().toUpperCase(), 420, rowY + 12, 0xFFFFFFFF);

        guiGraphics.text(this.font, "§7Boots: §f" + set.boots.getPieceName(), 420, rowY + 24, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§8  #" + set.boots.getHexcode().toUpperCase(), 420, rowY + 36, 0xFFFFFFFF);

        // Statistics (right side)
        String avgColor = set.avgDeltaE <= 1.0 ? "§a" : (set.avgDeltaE <= 2.0 ? "§e" : "§6");
        guiGraphics.text(this.font, "§7Avg ΔE: " + avgColor + String.format("%.2f", set.avgDeltaE), 770, rowY, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§7W/o worst: §b" + String.format("%.2f", set.avgWithout1), 770, rowY + 12, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§7W/o worst 2: §d" + String.format("%.2f", set.avgWithout2), 770, rowY + 24, 0xFFFFFFFF);
        guiGraphics.text(this.font, "§7Worst: §c" + set.worstPieceType, 770, rowY + 36, 0xFFFFFFFF);

        // Average hex swatches
        ColorMath.RGB avgRgb = ColorMath.hexToRgb(set.avgHex);
        ColorMath.RGB top3Rgb = ColorMath.hexToRgb(set.top3AvgHex);
        int swatchSize = 8;
        int swatchX = 770;
        int avgSwatchY = rowY + 50;
        int top3SwatchY = rowY + 62;

        // All 4 avg hex
        guiGraphics.fill(swatchX, avgSwatchY, swatchX + swatchSize, avgSwatchY + swatchSize,
            0xFF000000 | (avgRgb.r() << 16) | (avgRgb.g() << 8) | avgRgb.b());
        guiGraphics.text(this.font, "§7Avg: §f#" + set.avgHex, swatchX + swatchSize + 3, avgSwatchY, 0xFFFFFFFF);

        // Top 3 avg hex
        guiGraphics.fill(swatchX, top3SwatchY, swatchX + swatchSize, top3SwatchY + swatchSize,
            0xFF000000 | (top3Rgb.r() << 16) | (top3Rgb.g() << 8) | top3Rgb.b());
        guiGraphics.text(this.font, "§7Top3: §f#" + set.top3AvgHex, swatchX + swatchSize + 3, top3SwatchY, 0xFFFFFFFF);

        // Separator line
        guiGraphics.fill(20, rowY + 75, this.width - 40, rowY + 76, 0xFF3C3C3C);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean isOutOfBounds) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        if (profileDropdownOpen) {
            if (button == 0) {
                ProfileDropdown.getSelectedOption(mouseX, mouseY, 20, 40, 145, 25).ifPresent(profileId -> {
                    selectedProfileId = profileId;
                    profileSelected = true;
                    bestSets.clear();
                    scrollOffset = 0;
                    hasCalculated = false;
                });
            }
            profileDropdownOpen = false;
            this.rebuildWidgets();
            return true;
        }

        // Handle guiGraphics menu
        if (contextMenu != null) {
            if (button == 0) {
                if (handleContextMenuClick(mouseX, mouseY)) {
                    return true;
                }
                contextMenu = null;
                return true;
            }
        }

        // Right click to show guiGraphics menu for hex codes
        if (button == 1 && !bestSets.isEmpty()) {
            handleHexRightClick(mouseX, mouseY);
            return true;
        }

        return super.mouseClicked(click, isOutOfBounds);
    }

    private void handleHexRightClick(double mouseX, double mouseY) {
        int maxVisible = 5;
        int visibleCount = Math.min(maxVisible, bestSets.size() - scrollOffset);

        for (int i = 0; i < visibleCount; i++) {
            ArmorSet set = bestSets.get(scrollOffset + i);
            int rowY = START_Y + (i * ROW_HEIGHT);

            // Check helmet hex
            if (mouseX >= 80 && mouseX <= 230 && mouseY >= rowY + 10 && mouseY <= rowY + 24) {
                showContextMenu(set.helmet.getHexcode(), mouseX, mouseY);
                return;
            }
            // Check chest hex
            if (mouseX >= 80 && mouseX <= 230 && mouseY >= rowY + 34 && mouseY <= rowY + 48) {
                showContextMenu(set.chestplate.getHexcode(), mouseX, mouseY);
                return;
            }
            // Check legs hex
            if (mouseX >= 420 && mouseX <= 570 && mouseY >= rowY + 10 && mouseY <= rowY + 24) {
                showContextMenu(set.leggings.getHexcode(), mouseX, mouseY);
                return;
            }
            // Check boots hex
            if (mouseX >= 420 && mouseX <= 570 && mouseY >= rowY + 34 && mouseY <= rowY + 48) {
                showContextMenu(set.boots.getHexcode(), mouseX, mouseY);
                return;
            }
        }
    }

    private void showContextMenu(String hex, double x, double y) {
        contextMenu = new ContextMenu(hex, (int) x, (int) y);
    }

    private void drawContextMenu(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        int menuWidth = 120;
        int optionHeight = 20;

        // Background
        guiGraphics.fill(contextMenu.x(), contextMenu.y(), contextMenu.x() + menuWidth, contextMenu.y() + optionHeight, 0xF0282828);

        // Border
        guiGraphics.fill(contextMenu.x(), contextMenu.y(), contextMenu.x() + menuWidth, contextMenu.y() + 2, 0xFF646464);
        guiGraphics.fill(contextMenu.x(), contextMenu.y() + optionHeight - 2, contextMenu.x() + menuWidth, contextMenu.y() + optionHeight, 0xFF646464);
        guiGraphics.fill(contextMenu.x(), contextMenu.y(), contextMenu.x() + 2, contextMenu.y() + optionHeight, 0xFF646464);
        guiGraphics.fill(contextMenu.x() + menuWidth - 2, contextMenu.y(), contextMenu.x() + menuWidth, contextMenu.y() + optionHeight, 0xFF646464);

        // Hover highlight
        if (mouseX >= contextMenu.x() && mouseX <= contextMenu.x() + menuWidth &&
            mouseY >= contextMenu.y() && mouseY < contextMenu.y() + optionHeight) {
            guiGraphics.fill(contextMenu.x(), contextMenu.y(), contextMenu.x() + menuWidth, contextMenu.y() + optionHeight, 0xC8505050);
        }

        // Option text
        guiGraphics.text(this.font, "§fFind in Database", contextMenu.x() + 5, contextMenu.y() + 6, 0xFFFFFFFF);
    }

    private boolean handleContextMenuClick(double mouseX, double mouseY) {
        int menuWidth = 120;
        int optionHeight = 20;

        if (mouseX >= contextMenu.x() && mouseX <= contextMenu.x() + menuWidth &&
            mouseY >= contextMenu.y() && mouseY < contextMenu.y() + optionHeight) {
            // Open database with hex search
            DatabaseScreen dbScreen = new DatabaseScreen(this);
            dbScreen.setHexSearch(contextMenu.hex);
            this.minecraft.gui.setScreen(dbScreen);
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxVisible = 5;
        int maxScroll = Math.max(0, bestSets.size() - maxVisible);

        if (verticalAmount > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        } else if (verticalAmount < 0) {
            scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void calculateBestSets() {
        isCalculating = true;
        calculationProgress = 0;
        bestSets.clear();
        hasCalculated = false;

        // Re-init to update button state
        this.minecraft.execute(this::init);

        // Run calculation asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                performCalculation();
            } catch (Exception e) {
                SeymourAnalyzer.LOGGER.error("[Best Sets] Calculation failed", e);
            } finally {
                isCalculating = false;
                calculationProgress = 100;

                // Re-init to update button state back
                this.minecraft.execute(this::init);
            }
        });
    }

    private void performCalculation() {
        long startTime = System.currentTimeMillis();

        // Categorize pieces by type
        Map<String, ArmorPiece> collection = CollectionManager.getInstance().getCollection();
        if (!SkyBlockProfileTracker.ALL_PROFILES.equals(selectedProfileId)) {
            collection = collection.entrySet().stream()
                .filter(entry -> selectedProfileId.equals(entry.getValue().getProfileId()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        List<PieceWithLab> helmets = new ArrayList<>();
        List<PieceWithLab> chestplates = new ArrayList<>();
        List<PieceWithLab> leggings = new ArrayList<>();
        List<PieceWithLab> boots = new ArrayList<>();

        calculationProgress = 5;

        // Pre-calculate LAB values for ALL pieces (huge optimization - done once instead of 6x per set)
        for (ArmorPiece piece : collection.values()) {
            if (piece.getHexcode() == null || piece.getPieceName() == null) continue;

            String type = PieceTypeUtil.detectPieceType(piece.getPieceName());
            if (type == null) {
                continue;
            }
            PieceWithLab pwl = new PieceWithLab(piece);

            switch (type) {
                case "helmet" -> helmets.add(pwl);
                case "chestplate" -> chestplates.add(pwl);
                case "leggings" -> leggings.add(pwl);
                case "boots" -> boots.add(pwl);
            }
        }

        SeymourAnalyzer.LOGGER.info("[Best Sets] Pieces: {} helmets, {} chests, {} legs, {} boots",
            helmets.size(), chestplates.size(), leggings.size(), boots.size());

        calculationProgress = 10;

        // Pre-filter: Remove pieces that can't possibly match with ANY other piece
        helmets = filterViablePieces(helmets, Arrays.asList(chestplates, leggings, boots));
        chestplates = filterViablePieces(chestplates, Arrays.asList(helmets, leggings, boots));
        leggings = filterViablePieces(leggings, Arrays.asList(helmets, chestplates, boots));
        boots = filterViablePieces(boots, Arrays.asList(helmets, chestplates, leggings));

        SeymourAnalyzer.LOGGER.info("[Best Sets] After filtering: {} helmets, {} chests, {} legs, {} boots",
            helmets.size(), chestplates.size(), leggings.size(), boots.size());

        calculationProgress = 15;

        // Generate ALL valid 4-piece combinations and calculate their scores
        List<ArmorSet> allValidSets = Collections.synchronizedList(new ArrayList<>());

        long totalCombinations = (long) helmets.size() * chestplates.size() * leggings.size() * boots.size();
        final long[] processedCombinations = {0};
        final int[] lastProgress = {15};

        SeymourAnalyzer.LOGGER.info("[Best Sets] Total theoretical combinations: {}", totalCombinations);

        // Make lists final for lambda
        final List<PieceWithLab> finalChestplates = chestplates;
        final List<PieceWithLab> finalLeggings = leggings;
        final List<PieceWithLab> finalBoots = boots;

        // Process in parallel for better performance
        helmets.parallelStream().forEach(helmet -> {
            for (PieceWithLab chest : finalChestplates) {
                // Quick validation - check if helmet-chest pair is within threshold
                double hcDelta = ColorMath.calculateDeltaEWithLab(helmet.lab, chest.lab);
                if (hcDelta > MAX_DELTA_E) {
                    synchronized (processedCombinations) {
                        processedCombinations[0] += (long) finalLeggings.size() * finalBoots.size();
                    }
                    continue;
                }

                for (PieceWithLab leg : finalLeggings) {
                    // Check if adding legs keeps us within threshold
                    double hlDelta = ColorMath.calculateDeltaEWithLab(helmet.lab, leg.lab);
                    double clDelta = ColorMath.calculateDeltaEWithLab(chest.lab, leg.lab);

                    if (hlDelta > MAX_DELTA_E || clDelta > MAX_DELTA_E) {
                        synchronized (processedCombinations) {
                            processedCombinations[0] += finalBoots.size();
                        }
                        continue;
                    }

                    for (PieceWithLab boot : finalBoots) {
                        synchronized (processedCombinations) {
                            processedCombinations[0]++;

                            // Update progress every 5000 combinations (cap at 80 for this phase)
                            if (processedCombinations[0] % 5000 == 0) {
                                int newProgress = 15 + (int)((processedCombinations[0] * 65.0) / totalCombinations);
                                newProgress = Math.min(80, newProgress);
                                if (newProgress > lastProgress[0]) {
                                    calculationProgress = newProgress;
                                    lastProgress[0] = newProgress;
                                }
                            }
                        }

                        // Check if all pieces are different (no reuse within a set)
                        if (helmet.piece.getUuid().equals(chest.piece.getUuid()) ||
                            helmet.piece.getUuid().equals(leg.piece.getUuid()) ||
                            helmet.piece.getUuid().equals(boot.piece.getUuid()) ||
                            chest.piece.getUuid().equals(leg.piece.getUuid()) ||
                            chest.piece.getUuid().equals(boot.piece.getUuid()) ||
                            leg.piece.getUuid().equals(boot.piece.getUuid())) {
                            continue;
                        }

                        // Check boots deltas
                        double hbDelta = ColorMath.calculateDeltaEWithLab(helmet.lab, boot.lab);
                        double cbDelta = ColorMath.calculateDeltaEWithLab(chest.lab, boot.lab);
                        double lbDelta = ColorMath.calculateDeltaEWithLab(leg.lab, boot.lab);

                        // All pairwise deltas must be within threshold
                        if (hbDelta > MAX_DELTA_E || cbDelta > MAX_DELTA_E || lbDelta > MAX_DELTA_E) {
                            continue;
                        }

                        // Create the set with pre-computed deltas
                        ArmorSet set = new ArmorSet(
                            helmet.piece, chest.piece, leg.piece, boot.piece,
                            hcDelta, hlDelta, hbDelta, clDelta, cbDelta, lbDelta
                        );

                        if (set.avgDeltaE <= MAX_DELTA_E) {
                            allValidSets.add(set);
                        }
                    }
                }
            }
        });

        calculationProgress = 85;
        SeymourAnalyzer.LOGGER.info("[Best Sets] Found {} valid combinations", allValidSets.size());

        // Sort all valid sets by average delta E (best first)
        allValidSets.sort(Comparator.comparingDouble(set -> set.avgDeltaE));

        calculationProgress = 90;

        // Select the best non-overlapping sets (greedy selection from sorted list)
        List<ArmorSet> selectedSets = new ArrayList<>();
        Set<String> usedUuids = new HashSet<>();
        boolean underDeltaTwoOnly = ClothConfig.getInstance().isBestSetsUnderDeltaTwoOnly();

        for (ArmorSet set : allValidSets) {
            if (underDeltaTwoOnly && set.avgDeltaE >= 2.0) {
                continue;
            }
            if (!underDeltaTwoOnly && selectedSets.size() >= MAX_SETS) {
                break;
            }

            // Check if any piece in this set is already used
            if (usedUuids.contains(set.helmet.getUuid()) ||
                usedUuids.contains(set.chestplate.getUuid()) ||
                usedUuids.contains(set.leggings.getUuid()) ||
                usedUuids.contains(set.boots.getUuid())) {
                continue;
            }

            // This set doesn't overlap with any selected set - add it
            selectedSets.add(set);
            usedUuids.add(set.helmet.getUuid());
            usedUuids.add(set.chestplate.getUuid());
            usedUuids.add(set.leggings.getUuid());
            usedUuids.add(set.boots.getUuid());
        }

        calculationProgress = 95;

        bestSets = selectedSets;
        hasCalculated = true;

        // Save to cache
        cachedBestSets = new ArrayList<>(bestSets);
        cachedCollectionSize = collection.size();
        cacheTimestamp = System.currentTimeMillis();
        cachedUnderDeltaTwoOnly = ClothConfig.getInstance().isBestSetsUnderDeltaTwoOnly();
        cachedProfileId = selectedProfileId;

        long endTime = System.currentTimeMillis();
        long totalTimeMs = endTime - startTime;
        double totalTimeSec = totalTimeMs / 1000.0;
        SeymourAnalyzer.LOGGER.info("[Best Sets] Selected {} optimal sets in {}ms ({} seconds)",
            bestSets.size(), totalTimeMs, String.format("%.2f", totalTimeSec));
    }

    /**
     * Pre-filter pieces that can't possibly match with ANY piece from other types
     */
    private List<PieceWithLab> filterViablePieces(List<PieceWithLab> pieces, List<List<PieceWithLab>> otherTypesLists) {
        return pieces.stream()
            .filter(piece -> {
                // Check if this piece can match with at least one piece from EACH other type
                for (List<PieceWithLab> otherType : otherTypesLists) {
                    boolean hasMatch = otherType.stream()
                        .anyMatch(other -> ColorMath.calculateDeltaEWithLab(piece.lab, other.lab) <= MAX_DELTA_E);
                    if (!hasMatch) {
                        return false; // Can't form a valid set
                    }
                }
                return true;
            })
            .collect(Collectors.toList());
    }

    /**
     * Wrapper class to hold piece and pre-computed LAB values
     */
    private static class PieceWithLab {
        final ArmorPiece piece;
        final ColorMath.LAB lab;

        PieceWithLab(ArmorPiece piece) {
            this.piece = piece;
            this.lab = ColorMath.hexToLab(piece.getHexcode());
        }
    }

    /**
     * Represents a complete 4-piece armor set with statistics
     */
    private static class ArmorSet {
        final ArmorPiece helmet;
        final ArmorPiece chestplate;
        final ArmorPiece leggings;
        final ArmorPiece boots;

        final double avgDeltaE;
        final double avgWithout1; // Average ΔE without worst piece
        final double avgWithout2; // Average ΔE of best 2 pieces
        final String worstPieceType;

        final String avgHex;      // Average hex of all 4 pieces
        final String top3AvgHex;  // Average hex of best 3 pieces (excluding worst)

        // Constructor with pre-computed deltas
        ArmorSet(ArmorPiece helmet, ArmorPiece chestplate, ArmorPiece leggings, ArmorPiece boots,
                 double d_hc, double d_hl, double d_hb, double d_cl, double d_cb, double d_lb) {
            this.helmet = helmet;
            this.chestplate = chestplate;
            this.leggings = leggings;
            this.boots = boots;

            // Average of all pairs (already computed)
            this.avgDeltaE = (d_hc + d_hl + d_hb + d_cl + d_cb + d_lb) / 6.0;

            // Calculate average delta for each piece
            double helmetAvg = (d_hc + d_hl + d_hb) / 3.0;
            double chestAvg = (d_hc + d_cl + d_cb) / 3.0;
            double legsAvg = (d_hl + d_cl + d_lb) / 3.0;
            double bootsAvg = (d_hb + d_cb + d_lb) / 3.0;

            // Find worst piece
            Map<String, Double> pieceAvgs = new HashMap<>();
            pieceAvgs.put("helmet", helmetAvg);
            pieceAvgs.put("chestplate", chestAvg);
            pieceAvgs.put("leggings", legsAvg);
            pieceAvgs.put("boots", bootsAvg);

            List<Map.Entry<String, Double>> sorted = pieceAvgs.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

            this.worstPieceType = sorted.getFirst().getKey();

            // Calculate average without worst piece (best 3 pieces)
            List<Double> best3Deltas = new ArrayList<>();
            String worst = sorted.getFirst().getKey();

            if (!worst.equals("helmet")) best3Deltas.addAll(Arrays.asList(d_hc, d_hl, d_hb));
            if (!worst.equals("chestplate")) best3Deltas.addAll(Arrays.asList(d_hc, d_cl, d_cb));
            if (!worst.equals("leggings")) best3Deltas.addAll(Arrays.asList(d_hl, d_cl, d_lb));
            if (!worst.equals("boots")) best3Deltas.addAll(Arrays.asList(d_hb, d_cb, d_lb));

            this.avgWithout1 = best3Deltas.stream().mapToDouble(Double::doubleValue).average().orElse(0);

            // Calculate average of best 2 pieces (smallest delta)
            double[] allDeltas = {d_hc, d_hl, d_hb, d_cl, d_cb, d_lb};
            Arrays.sort(allDeltas);
            this.avgWithout2 = allDeltas[0]; // Best pair

            // Compute average hex of all 4 pieces
            this.avgHex = computeAvgHex(new ArmorPiece[]{helmet, chestplate, leggings, boots});

            // Compute average hex of best 3 pieces (excluding worst)
            List<ArmorPiece> best3Pieces = new ArrayList<>(Arrays.asList(helmet, chestplate, leggings, boots));
            best3Pieces.removeIf(p -> {
                String type = PieceTypeUtil.detectPieceType(p.getPieceName());
                return worst.equals(type);
            });
            // Fallback: if nothing removed (type mismatch), use all 4
            if (best3Pieces.size() == 4) best3Pieces.remove(3);
            this.top3AvgHex = computeAvgHex(best3Pieces.toArray(new ArmorPiece[0]));
        }

        private static String computeAvgHex(ArmorPiece[] pieces) {
            int rSum = 0, gSum = 0, bSum = 0;
            int count = 0;
            for (ArmorPiece p : pieces) {
                if (p == null || p.getHexcode() == null) continue;
                ColorMath.RGB rgb = ColorMath.hexToRgb(p.getHexcode());
                rSum += rgb.r();
                gSum += rgb.g();
                bSum += rgb.b();
                count++;
            }
            if (count == 0) return "000000";
            int r = rSum / count;
            int g = gSum / count;
            int b = bSum / count;
            return String.format("%02X%02X%02X", r, g, b);
        }
    }

    private record ContextMenu(String hex, int x, int y) {
    }
}
