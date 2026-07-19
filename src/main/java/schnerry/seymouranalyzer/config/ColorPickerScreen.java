package schnerry.seymouranalyzer.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import schnerry.seymouranalyzer.gui.ModScreen;

/**
 * HSV color picker screen with alpha support and manual hex input.
 * Opens as a sub-screen from PriorityEditorScreen when clicking a color swatch.
 */
public class ColorPickerScreen extends ModScreen {

    // ── Layout constants ────────────────────────────────────────────────────
    private static final int PANEL_W  = 210;
    private static final int PANEL_H  = 275;
    private static final int PADDING  = 12;
    private static final int SQ_W     = PANEL_W - PADDING * 2;  // 186
    private static final int SQ_H     = 130;
    private static final int STRIP_H  = 11;
    private static final int GAP      = 5;
    private static final int PREVIEW_SIZE = 28;

    // ── State ────────────────────────────────────────────────────────────────
    private final MatchPriority priority;
    /** 0..1 */
    private float hue, saturation, value;
    /** 0..255 */
    private int alpha;

    // Drag tracking
    private boolean draggingSv   = false;
    private boolean draggingHue  = false;
    private boolean draggingAlpha = false;

    // Widgets
    private EditBox hexInput;
    private boolean updatingHexFromState = false;

    public ColorPickerScreen(Screen parent, MatchPriority priority) {
        super(Component.literal("Pick Color"), parent);
        this.priority = priority;

        // Initialise state from current saved color
        int argb = ClothConfig.getInstance().getHighlightColor(priority);
        this.alpha = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        float[] hsv = rgbToHsv(r, g, b);
        this.hue        = hsv[0];
        this.saturation = hsv[1];
        this.value      = hsv[2];
    }

    // ── Panel origin helpers ──────────────────────────────────────────────────
    private int panelX()    { return (width  - PANEL_W) / 2; }
    private int panelY()    { return (height - PANEL_H) / 2; }
    private int sqX()       { return panelX() + PADDING; }
    private int sqY()       { return panelY() + 26; }         // below title
    private int hueY()      { return sqY() + SQ_H + GAP; }
    private int alphaY()    { return hueY() + STRIP_H + GAP; }
    private int previewY()  { return alphaY() + STRIP_H + GAP; }  // preview swatch row
    private int hexRowY()   { return previewY() + GAP; } // hex input row

    // ── Init ────────────────────────────────────────────────────────────────
    @Override
    protected void init() {
        super.init();

        // Slim hex input – right-aligned to the same right edge as all sliders
        int hexBoxW = 65;
        int hexBoxX = sqX() + SQ_W - hexBoxW;
        int hexBoxY = hexRowY();

        // Hex input (RRGGBB, 6 chars)
        hexInput = new EditBox(this.font, hexBoxX, hexBoxY, hexBoxW, 14,
                Component.literal("Hex"));
        hexInput.setMaxLength(6);
        hexInput.setResponder(this::onHexTyped);
        hexInput.setBordered(true);
        this.addRenderableWidget(hexInput);
        syncHexFromState();

        // Buttons sit below the hex row
        int buttonY = hexRowY() + 14 + PREVIEW_SIZE + GAP + 2;
        int btnW    = (PANEL_W - PADDING * 2 - GAP) / 2;

        // Done
        this.addRenderableWidget(Button.builder(Component.literal("Done"), btn -> {
            ClothConfig.getInstance().setHighlightColor(priority, currentArgb());
            ClothConfig.getInstance().save();
            this.onClose();
        }).bounds(sqX(), buttonY, btnW, 18).build());

        // Reset to default
        this.addRenderableWidget(Button.builder(Component.literal("Default"), btn -> {
            int def = priority.getDefaultColor();
            applyArgb(def);
        }).bounds(sqX() + btnW + GAP, buttonY, btnW, 18).build());
    }

    // ── Render order fix ─────────────────────────────────────────────────────
    // extractRenderState calls super (which renders widgets) and then render().
    // If we draw the panel fill inside render(), it covers already-rendered widgets.
    // Fix: draw the opaque panel background BEFORE super so widgets appear on top.
    //? if <26.1 {
    /*@Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        drawPanelBackground(g);
        super.render(g, mouseX, mouseY, delta); // renders widgets, then calls renderContent()
    }
    *///?} else {
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        drawPanelBackground(g);
        super.extractRenderState(g, mouseX, mouseY, delta); // renders widgets, then calls renderContent()
    }
    //?}

    private void drawPanelBackground(GuiGraphicsExtractor g) {
        int px = panelX(), py = panelY();
        // Dim the whole screen
        g.fill(0, 0, width, height, 0xA0000000);
        // Panel body
        g.fill(px, py, px + PANEL_W, py + PANEL_H, 0xFF1E1E1E);
        // Panel border
        g.fill(px,              py,               px + PANEL_W,     py + 1,             0xFF555555);
        g.fill(px,              py + PANEL_H - 1, px + PANEL_W,     py + PANEL_H,       0xFF555555);
        g.fill(px,              py,               px + 1,           py + PANEL_H,       0xFF555555);
        g.fill(px + PANEL_W - 1, py,              px + PANEL_W,     py + PANEL_H,       0xFF555555);
        // Title
        String title = "Pick Color: " + priority.getDisplayName();
        g.centeredText(this.font, Component.literal(title), px + PANEL_W / 2, py + 9, 0xFFFFFFFF);
    }

    // ── Render ───────────────────────────────────────────────────────────────
    // Draws picker content on top (SV square, sliders, preview, labels).
    // Buttons/EditBox are already rendered by super. Invoked virtually by ModScreen's
    // render()/extractRenderState() chain via the renderContent() hook.
    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int sx = sqX(), sy = sqY();

        // ── SV square (Saturation × Value) ──────────────────────────────────
        // Render column by column using fillGradient (top=bright, bottom=black)
        for (int col = 0; col < SQ_W; col++) {
            float s = (float) col / (SQ_W - 1);
            int topColor    = hsvToArgb(hue, s, 1f, 255);
            int bottomColor = 0xFF000000;
            g.fillGradient(sx + col, sy, sx + col + 1, sy + SQ_H, topColor, bottomColor);
        }
        // SV square border
        g.fill(sx - 1,      sy - 1,      sx + SQ_W + 1, sy,           0xFF888888);
        g.fill(sx - 1,      sy + SQ_H,   sx + SQ_W + 1, sy + SQ_H + 1, 0xFF888888);
        g.fill(sx - 1,      sy - 1,      sx,            sy + SQ_H + 1, 0xFF888888);
        g.fill(sx + SQ_W,   sy - 1,      sx + SQ_W + 1, sy + SQ_H + 1, 0xFF888888);

        // SV crosshair marker
        int svMarkerX = sx + Math.round(saturation * (SQ_W - 1));
        int svMarkerY = sy + Math.round((1f - value) * (SQ_H - 1));
        // Outer white ring
        g.fill(svMarkerX - 4, svMarkerY - 1, svMarkerX + 5, svMarkerY + 2, 0xFFFFFFFF);
        g.fill(svMarkerX - 1, svMarkerY - 4, svMarkerX + 2, svMarkerY + 5, 0xFFFFFFFF);
        // Inner black ring (hollow circle effect)
        g.fill(svMarkerX - 3, svMarkerY,     svMarkerX + 4, svMarkerY + 1, 0xFF000000);
        g.fill(svMarkerX,     svMarkerY - 3, svMarkerX + 1, svMarkerY + 4, 0xFF000000);

        // ── Hue slider ──────────────────────────────────────────────────────
        int hy = hueY();
        for (int col = 0; col < SQ_W; col++) {
            float h = (float) col / (SQ_W - 1);
            g.fill(sx + col, hy, sx + col + 1, hy + STRIP_H, hsvToArgb(h, 1f, 1f, 255));
        }
        // Hue border
        g.fill(sx - 1, hy - 1, sx + SQ_W + 1, hy,              0xFF888888);
        g.fill(sx - 1, hy + STRIP_H, sx + SQ_W + 1, hy + STRIP_H + 1, 0xFF888888);
        g.fill(sx - 1, hy - 1, sx,              hy + STRIP_H + 1, 0xFF888888);
        g.fill(sx + SQ_W, hy - 1, sx + SQ_W + 1, hy + STRIP_H + 1, 0xFF888888);
        // Hue indicator
        int hueMarkerX = sx + Math.round(hue * (SQ_W - 1));
        g.fill(hueMarkerX - 1, hy - 2, hueMarkerX + 2, hy + STRIP_H + 2, 0xFFFFFFFF);
        g.fill(hueMarkerX,     hy - 1, hueMarkerX + 1, hy + STRIP_H + 1, 0xFF000000);

        // ── Alpha slider ─────────────────────────────────────────────────────
        int ay = alphaY();
        // Checkerboard background
        for (int col = 0; col < SQ_W; col += 6) {
            int endCol = Math.min(col + 6, SQ_W);
            for (int row = 0; row < STRIP_H; row += 6) {
                int endRow = Math.min(row + 6, STRIP_H);
                boolean checker = ((col / 6) + (row / 6)) % 2 == 0;
                int c = checker ? 0xFFBBBBBB : 0xFF777777;
                g.fill(sx + col, ay + row, sx + endCol, ay + endRow, c);
            }
        }
        // Alpha gradient overlay
        int rgbNow = hsvToRgb(hue, saturation, value);
        for (int col = 0; col < SQ_W; col++) {
            int a = Math.round((float) col / (SQ_W - 1) * 255);
            g.fill(sx + col, ay, sx + col + 1, ay + STRIP_H, (a << 24) | (rgbNow & 0x00FFFFFF));
        }
        // Alpha border
        g.fill(sx - 1, ay - 1, sx + SQ_W + 1, ay,               0xFF888888);
        g.fill(sx - 1, ay + STRIP_H, sx + SQ_W + 1, ay + STRIP_H + 1, 0xFF888888);
        g.fill(sx - 1, ay - 1, sx,               ay + STRIP_H + 1, 0xFF888888);
        g.fill(sx + SQ_W, ay - 1, sx + SQ_W + 1, ay + STRIP_H + 1, 0xFF888888);
        // Alpha indicator
        int alphaMarkerX = sx + Math.round((float) alpha / 255f * (SQ_W - 1));
        g.fill(alphaMarkerX - 1, ay - 2, alphaMarkerX + 2, ay + STRIP_H + 2, 0xFFFFFFFF);
        g.fill(alphaMarkerX,     ay - 1, alphaMarkerX + 1, ay + STRIP_H + 1, 0xFF000000);

        // ── Preview swatch ───────────────────────────────────────────────────
        int previewY = previewY();
        // Checkerboard behind preview (to show alpha)
        for (int col = 0; col < PREVIEW_SIZE; col += 6) {
            for (int row = 0; row < PREVIEW_SIZE; row += 6) {
                boolean checker = ((col / 6) + (row / 6)) % 2 == 0;
                g.fill(sx + col, previewY + row,
                       sx + Math.min(col + 6, PREVIEW_SIZE), previewY + Math.min(row + 6, PREVIEW_SIZE),
                       checker ? 0xFFBBBBBB : 0xFF777777);
            }
        }
        g.fill(sx, previewY, sx + PREVIEW_SIZE, previewY + PREVIEW_SIZE, currentArgb());
        // Preview border
        g.fill(sx - 1,              previewY - 1, sx + PREVIEW_SIZE + 1, previewY,                           0xFF888888);
        g.fill(sx - 1,              previewY + PREVIEW_SIZE, sx + PREVIEW_SIZE + 1, previewY + PREVIEW_SIZE + 1, 0xFF888888);
        g.fill(sx - 1,              previewY - 1, sx,                     previewY + PREVIEW_SIZE + 1,        0xFF888888);
        g.fill(sx + PREVIEW_SIZE,   previewY - 1, sx + PREVIEW_SIZE + 1, previewY + PREVIEW_SIZE + 1,        0xFF888888);

        // Alpha % label – right of the preview swatch, vertically centred
        int pctX = sx + PREVIEW_SIZE + 8;
        int pctY = previewY + (PREVIEW_SIZE - 18) / 2;
        g.text(this.font, "Alpha:",                                pctX, pctY,      0xFFAAAAAA);
        g.text(this.font, Math.round(alpha / 255f * 100) + "%",   pctX, pctY + 10, 0xFFFFFFFF);

        // ── Hex row (below preview) ──────────────────────────────────────────
        // "Hex: #" label sits immediately left of the right-aligned input field
        int hexLabelY = hexRowY() + 3;
        int hexInputX = sqX() + SQ_W - 65;
        g.text(this.font, "Hex: #", hexInputX - 38, hexLabelY, 0xFFAAAAAA);

        super.renderContent(g, mouseX, mouseY, delta); // tick()
    }

    // ── Mouse interaction ─────────────────────────────────────────────────────
    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean isOutOfBounds) {
        if (click.button() == 0) {
            int mx = (int) click.x(), my = (int) click.y();
            if (isInSvSquare(mx, my)) {
                draggingSv = true;
                updateSvFromMouse(mx, my);
                return true;
            }
            if (isInHueStrip(mx, my)) {
                draggingHue = true;
                updateHueFromMouse(mx);
                return true;
            }
            if (isInAlphaStrip(mx, my)) {
                draggingAlpha = true;
                updateAlphaFromMouse(mx);
                return true;
            }
        }
        return super.mouseClicked(click, isOutOfBounds);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (click.button() == 0) {
            int mx = (int) click.x(), my = (int) click.y();
            if (draggingSv) {
                updateSvFromMouse(mx, my);
                return true;
            }
            if (draggingHue) {
                updateHueFromMouse(mx);
                return true;
            }
            if (draggingAlpha) {
                updateAlphaFromMouse(mx);
                return true;
            }
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (click.button() == 0) {
            draggingSv    = false;
            draggingHue   = false;
            draggingAlpha = false;
        }
        return super.mouseReleased(click);
    }

    // ── Hit tests ─────────────────────────────────────────────────────────────
    private boolean isInSvSquare(int mx, int my) {
        int sx = sqX(), sy = sqY();
        return mx >= sx && mx < sx + SQ_W && my >= sy && my < sy + SQ_H;
    }
    private boolean isInHueStrip(int mx, int my) {
        int sx = sqX(), hy = hueY();
        return mx >= sx && mx < sx + SQ_W && my >= hy && my < hy + STRIP_H;
    }
    private boolean isInAlphaStrip(int mx, int my) {
        int sx = sqX(), ay = alphaY();
        return mx >= sx && mx < sx + SQ_W && my >= ay && my < ay + STRIP_H;
    }

    // ── Update helpers ────────────────────────────────────────────────────────
    private void updateSvFromMouse(int mx, int my) {
        int sx = sqX(), sy = sqY();
        saturation = clamp01((float)(mx - sx) / (SQ_W - 1));
        value      = 1f - clamp01((float)(my - sy) / (SQ_H - 1));
        syncHexFromState();
    }

    private void updateHueFromMouse(int mx) {
        hue = clamp01((float)(mx - sqX()) / (SQ_W - 1));
        syncHexFromState();
    }

    private void updateAlphaFromMouse(int mx) {
        alpha = Math.round(clamp01((float)(mx - sqX()) / (SQ_W - 1)) * 255);
    }

    // ── Hex field sync ────────────────────────────────────────────────────────
    /** Pushes current HSV state into the hex field (RGB only). */
    private void syncHexFromState() {
        if (hexInput == null) return;
        updatingHexFromState = true;
        int rgb = hsvToRgb(hue, saturation, value);
        hexInput.setValue(String.format("%06X", rgb & 0x00FFFFFF));
        updatingHexFromState = false;
    }

    /** Called when user types in the hex box. */
    private void onHexTyped(String text) {
        if (updatingHexFromState) return;
        if (text.length() == 6) {
            try {
                int rgb = Integer.parseInt(text, 16);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >>  8) & 0xFF;
                int b =  rgb        & 0xFF;
                float[] hsv = rgbToHsv(r, g, b);
                hue        = hsv[0];
                saturation = hsv[1];
                value      = hsv[2];
            } catch (NumberFormatException ignored) {}
        }
    }

    // ── Color helpers ─────────────────────────────────────────────────────────
    private int currentArgb() {
        return (alpha << 24) | (hsvToRgb(hue, saturation, value) & 0x00FFFFFF);
    }

    private void applyArgb(int argb) {
        alpha = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        float[] hsv = rgbToHsv(r, g, b);
        hue        = hsv[0];
        saturation = hsv[1];
        value      = hsv[2];
        syncHexFromState();
    }

    /** ARGB integer from HSV + alpha. */
    private static int hsvToArgb(float h, float s, float v, int a) {
        return (a << 24) | (hsvToRgb(h, s, v) & 0x00FFFFFF);
    }

    /** RGB integer (no alpha) from HSV. */
    private static int hsvToRgb(float h, float s, float v) {
        if (s == 0f) {
            int g = Math.round(v * 255);
            return (g << 16) | (g << 8) | g;
        }
        float hh = h * 6f;
        int   i  = (int) hh;
        float f  = hh - i;
        float p  = v * (1f - s);
        float q  = v * (1f - s * f);
        float t  = v * (1f - s * (1f - f));
        float r, g, b;
        switch (i % 6) {
            case 0  -> { r = v; g = t; b = p; }
            case 1  -> { r = q; g = v; b = p; }
            case 2  -> { r = p; g = v; b = t; }
            case 3  -> { r = p; g = q; b = v; }
            case 4  -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    /** Returns [hue 0..1, saturation 0..1, value 0..1]. */
    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float h = 0f, s = 0f, v = max;
        if (delta > 0f) {
            s = delta / max;
            if      (max == rf) h = ((gf - bf) / delta) % 6f;
            else if (max == gf) h = (bf - rf) / delta + 2f;
            else                h = (rf - gf) / delta + 4f;
            h /= 6f;
            if (h < 0f) h += 1f;
        }
        return new float[]{h, s, v};
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}


