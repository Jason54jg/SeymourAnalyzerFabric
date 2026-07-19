package schnerry.seymouranalyzer.gambling;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

import java.util.List;

/**
 * Casino-style rolling animation screen. Thanks to DotGling for the original code.
 */
public class GamblingScreen extends Screen {

    private static final int    CELL_SIZE    = 56;
    private static final int    CELL_PADDING = 6;
    private static final int    CELL_STRIDE  = CELL_SIZE + CELL_PADDING;
    private static final int    TICKER_H     = CELL_SIZE + 22;
    private static final int    STRIP_COUNT  = 80;
    private static final int    WINNER_INDEX = 68;
    private static final double TOTAL_SCROLL = (double) WINNER_INDEX * CELL_STRIDE;
    private static final int    TIER_BAR_H   = 4;
    private static final int    TIER_LABEL_H = 9;
    private static final long   ROLL_MS         = 6000L;
    private static final long   INSPECT_FADE_MS = 800L;
    private static final long   HINT_DELAY_MS   = 2500L;
    private static final double SOUND_INTERVAL_PX = CELL_STRIDE;

    private final List<ItemStack> strip;
    private final ItemStack       winner;

    private long    startMs         = -1L;
    private boolean rolling         = true;
    private long    rollingEndMs    = -1L;
    private double  scrollOffset    = 0.0;
    private double  lastSoundOffset = 0.0;
    private boolean dingPlayed      = false;

    public GamblingScreen() {
        this(-1, null);
    }

    public GamblingScreen(int winnerRgb, net.minecraft.world.item.Item winnerItem) {
        super(Component.literal("Seymour Roll"));
        strip  = GamblingRoller.buildRollStrip(STRIP_COUNT, WINNER_INDEX, winnerRgb, winnerItem);
        winner = strip.get(WINNER_INDEX);
    }

    // Content hook, called by extractRenderState()/render() below depending on Minecraft version.
    private void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();
        if (startMs < 0) startMs = now;
        long elapsed = now - startMs;

        if (rolling) {
            double t  = Math.min(1.0, elapsed / (double) ROLL_MS);
            double tE = 1.0 - Math.pow(1.0 - t, 3);
            double newOffset = tE * TOTAL_SCROLL;

            double speed = newOffset - scrollOffset;
            if (newOffset - lastSoundOffset >= SOUND_INTERVAL_PX) {
                lastSoundOffset += SOUND_INTERVAL_PX;
                float speedFraction = (float)(speed / (CELL_STRIDE * 0.5));
                float pitch = 0.6f + Math.min(0.9f, speedFraction * 1.5f);
                playTick(pitch);
            }

            scrollOffset = newOffset;

            if (elapsed >= ROLL_MS) {
                rolling      = false;
                rollingEndMs = now;
                scrollOffset = TOTAL_SCROLL;
            }
        } else if (!dingPlayed) {
            dingPlayed = true;
            playDing();
        }

        g.fill(0, 0, this.width, this.height, 0xCC080808);
        renderTicker(g, now);
        if (!rolling) renderInspectionPanel(g, now);
    }

    //? if <26.1 {
    /*@Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        renderContent(g, mouseX, mouseY, delta);
    }
    *///?} else {
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        renderContent(g, mouseX, mouseY, delta);
    }
    //?}

    private void playTick(float pitch) {
        if (this.minecraft == null) return;
        this.minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch)
        );
    }

    private void playDing() {
        if (this.minecraft == null) return;
        this.minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f)
        );
    }

    private void renderTicker(GuiGraphicsExtractor g, long now) {
        int cx = this.width  / 2;
        int cy = this.height / 2 - 40;
        int stripTop    = cy - TICKER_H / 2;
        int stripBottom = cy + TICKER_H / 2;

        g.fill(0, stripTop, this.width, stripBottom, 0xFF111111);
        g.fill(0, stripTop,        this.width, stripTop + 2,  0xFFAA8833);
        g.fill(0, stripBottom - 2, this.width, stripBottom,   0xFFAA8833);

        g.fill(cx - CELL_SIZE / 2 - 4, stripTop,
                cx + CELL_SIZE / 2 + 4, stripBottom, 0x33FFDD55);

        int itemAreaTop    = stripTop + 4;
        int itemAreaBottom = stripBottom - TIER_BAR_H - TIER_LABEL_H - 3;

        for (int i = 0; i < strip.size(); i++) {
            int cellLeft = (int)(cx - scrollOffset + (long) i * CELL_STRIDE - (double) CELL_SIZE / 2);
            if (cellLeft > this.width + CELL_STRIDE) break;
            if (cellLeft + CELL_SIZE < -CELL_STRIDE)  continue;

            ItemStack stack    = strip.get(i);
            boolean   isWinner = (i == WINNER_INDEX && !rolling);

            GamblingRoller.RollColor rc  = GamblingRoller.getRollColor(stack);
            GamblingRoller.Tier      tier = (rc != null) ? rc.tier() : GamblingRoller.Tier.UNKNOWN;
            int tierRgb = (rc != null) ? rc.resolveTierColor(now) : tier.resolveColor(now);

            boolean isOneOfOne = (tier == GamblingRoller.Tier.ONE_OF_ONE);
            int cellBg = isWinner ? 0xFF1E1A00
                       : isOneOfOne ? (0xFF000000 | (~tierRgb & 0xFFFFFF))
                       : 0xFF1C1C1C;

            g.fill(cellLeft, itemAreaTop,
                    cellLeft + CELL_SIZE, stripBottom - 2,
                    cellBg);

            int itemCy = (itemAreaTop + itemAreaBottom) / 2;
            renderItemScaled2x(g, stack, cellLeft + CELL_SIZE / 2 - 8, itemCy - 8);

            String label  = tier.label;
            int    labelX = cellLeft + (CELL_SIZE - this.font.width(label)) / 2;
            int    labelY = itemAreaBottom + 1;
            g.text(this.font, label, labelX, labelY, 0xFF000000 | tierRgb);

            int barTop = stripBottom - TIER_BAR_H - 2;
            g.fill(cellLeft, barTop, cellLeft + CELL_SIZE, barTop + TIER_BAR_H,
                    0xFF000000 | tierRgb);
        }

        drawDownArrow(g, cx, stripTop - 2);
    }

    private void renderItemScaled2x(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        g.pose().pushMatrix();
        g.pose().translate(x + 8f, y + 8f);
        g.pose().scale(2.0f, 2.0f);
        g.item(stack, -8, -8);
        g.pose().popMatrix();
    }

    private void drawDownArrow(GuiGraphicsExtractor g, int cx, int tipY) {
        // left-pointing arrow, centered at cx, sitting above the strip
        int midY = tipY - 9;
        for (int i = 0; i < 8; i++) {
            int half = i; // narrow on left (tip), wide on right
            g.fill(cx - 3 + i, midY - half, cx - 3 + i + 1, midY + half + 1, 0xFFFFCC00);
        }
    }

    private void renderInspectionPanel(GuiGraphicsExtractor g, long now) {
        long sinceEnd = now - rollingEndMs;
        float alpha   = (float) Math.min(1.0, sinceEnd / (double) INSPECT_FADE_MS);
        int   a       = (int)(alpha * 255) & 0xFF;

        int cx = this.width / 2;
        int panelW = 340;

        GamblingRoller.RollColor rc   = GamblingRoller.getRollColor(winner);
        GamblingRoller.Tier      tier = (rc != null) ? rc.tier() : GamblingRoller.Tier.UNKNOWN;
        int tierRgb   = (rc != null) ? rc.resolveTierColor(now) : tier.resolveColor(now);

        List<GamblingRoller.GamblingMatch> topMatches = (rc != null) ? rc.topMatches() : List.of();
        int matchCount    = Math.min(3, topMatches.size());
        int matchSectionH = matchCount > 0 ? (14 * (matchCount + 1) + 10) : 0;
        int panelH        = 140 + matchSectionH;

        int px = cx - panelW / 2;
        int tickerBottom = this.height / 2 - 40 + TICKER_H / 2;
        int py = tickerBottom + 18;
        int borderCol = (a << 24) | tierRgb;

        g.fill(px,              py,              px + panelW, py + panelH, (a << 24) | 0x0D0D0D);
        g.fill(px,              py,              px + panelW, py + 2,      borderCol);
        g.fill(px,              py + panelH - 2, px + panelW, py + panelH, borderCol);
        g.fill(px,              py,              px + 2,      py + panelH, borderCol);
        g.fill(px + panelW - 2, py,              px + panelW, py + panelH, borderCol);

        final float ITEM_SCALE = 5.0f;
        final int   ITEM_SIDE  = (int)(ITEM_SCALE * 16);
        final int   ITEM_COL_W = ITEM_SIDE + 24;
        int itemDrawX = px + ITEM_COL_W / 2 - ITEM_SIDE / 2;
        int itemDrawY = py + (panelH - ITEM_SIDE) / 2;

        g.pose().pushMatrix();
        g.pose().translate(itemDrawX + ITEM_SIDE / 2f, itemDrawY + ITEM_SIDE / 2f);
        g.pose().scale(ITEM_SCALE, ITEM_SCALE);
        g.item(winner, -8, -8);
        g.pose().popMatrix();

        int tx    = px + ITEM_COL_W + 10;
        int lineH = 14;
        int ty    = py + 16;

        String itemName = getPieceTypeName(winner);
        String rareName = GamblingRoller.getRarityName(winner);
        String hexStr   = GamblingRoller.getHexString(winner);

        g.text(this.font, itemName,   tx, ty,              (a << 24) | 0xFFFFFF);
        g.text(this.font, tier.label, tx, ty + lineH,      (a << 24) | tierRgb);
        int badgeW = this.font.width(tier.label) + 6;
        g.text(this.font, rareName,   tx + badgeW, ty + lineH, (a << 24) | 0xAAAAAA);
        g.text(this.font, "COLOR",    tx, ty + lineH * 3,  (a << 24) | 0x888888);
        g.text(this.font, hexStr,     tx, ty + lineH * 4,  (a << 24) | 0xFFCC00);

        int swatchX = tx + this.font.width(hexStr) + 6;
        int swatchY = ty + lineH * 4 - 1;
        drawSwatch(g, winner, swatchX, swatchY, 11, 11, a);

        if (matchCount > 0) {
            int matchY = ty + lineH * 6;
            g.text(this.font, "CLOSEST MATCHES", tx, matchY, (a << 24) | 0x888888);
            matchY += lineH;

            int maxNameW = panelW - ITEM_COL_W - 10 - 80;

            for (int i = 0; i < matchCount; i++) {
                GamblingRoller.GamblingMatch match = topMatches.get(i);
                int matchTierRgb = match.tier().resolveColor(now);

                String prefix = "#" + (i + 1) + " ";
                String deltaStr = String.format("ΔE %.2f", match.deltaE());
                DyedItemColor winnerDyed = winner.get(net.minecraft.core.component.DataComponents.DYED_COLOR);
                int winnerRgb = winnerDyed != null ? winnerDyed.rgb() & 0xFFFFFF : 0;
                int matchRgb  = 0;
                try { matchRgb = Integer.parseInt(match.matchHex().replace("#", ""), 16); } catch (Exception ignored) {}
                int absDist = Math.abs(((winnerRgb >> 16) & 0xFF) - ((matchRgb >> 16) & 0xFF))
                            + Math.abs(((winnerRgb >>  8) & 0xFF) - ((matchRgb >>  8) & 0xFF))
                            + Math.abs(( winnerRgb        & 0xFF) - ( matchRgb        & 0xFF));
                String absStr = String.format("Abs %d", absDist);

                String matchName = match.name();
                while (this.font.width(matchName) > maxNameW && matchName.length() > 3) {
                    matchName = matchName.substring(0, matchName.length() - 1);
                }
                if (!matchName.equals(match.name())) matchName += "..";

                g.text(this.font, prefix, tx, matchY, (a << 24) | 0xAAAAAA);
                int afterPrefix = tx + this.font.width(prefix);
                g.text(this.font, matchName, afterPrefix, matchY, (a << 24) | 0xFFFFFF);

                int deltaX = px + panelW - 12 - this.font.width(deltaStr) - 6 - this.font.width(absStr);
                g.text(this.font, deltaStr, deltaX, matchY, (a << 24) | matchTierRgb);

                int absX = deltaX + this.font.width(deltaStr) + 6;
                g.text(this.font, absStr, absX, matchY, (a << 24) | 0xAAAAAA);

                matchY += lineH;
            }
        }

        if (sinceEnd > HINT_DELAY_MS) {
            float hintAlpha = (float) Math.min(1.0, (sinceEnd - HINT_DELAY_MS) / 800.0);
            int   ha        = (int)(hintAlpha * 180) & 0xFF;
            String hint = "Press ESC to close";
            g.text(this.font, hint,
                    cx - this.font.width(hint) / 2,
                    this.height - 30,
                    (ha << 24) | 0x666666);
        }
    }

    private void drawSwatch(GuiGraphicsExtractor g, ItemStack stack,
                            int x, int y, int w, int h, int a) {
        DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
        if (dyed == null) return;
        int rgb    = dyed.rgb() & 0xFFFFFF;
        int border = (a << 24);
        g.fill(x, y, x + w, y + h, (a << 24) | rgb);
        g.fill(x,         y,         x + w, y + 1,     border);
        g.fill(x,         y + h - 1, x + w, y + h,     border);
        g.fill(x,         y,         x + 1, y + h,     border);
        g.fill(x + w - 1, y,         x + w, y + h,     border);
    }

    private String getPieceTypeName(ItemStack stack) {
        net.minecraft.world.item.Item item = stack.getItem();
        if (item == net.minecraft.world.item.Items.LEATHER_HELMET)     return "Velvet Top Hat";
        if (item == net.minecraft.world.item.Items.LEATHER_CHESTPLATE) return "Cashmere Jacket";
        if (item == net.minecraft.world.item.Items.LEATHER_LEGGINGS)   return "Satin Trousers";
        if (item == net.minecraft.world.item.Items.LEATHER_BOOTS)      return "Oxford Shoes";
        return stack.getHoverName().getString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (!rolling && keyEvent.key() == 256) { // ESC
            this.onClose();
            return true;
        }
        return false;
    }
}

