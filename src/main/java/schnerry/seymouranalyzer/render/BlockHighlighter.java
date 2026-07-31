package schnerry.seymouranalyzer.render;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages and renders highlighted block positions in the world.
 * Updated for 1.21.10 rendering API.
 */
public class BlockHighlighter {
    private static BlockHighlighter instance;
    private final List<BlockPos> highlightedBlocks = new ArrayList<>();

    // Highlight color (RGBA) - bright green
    private float r = 0.0f;
    private float g = 1.0f;
    private float b = 0.0f;
    private float a = 0.8f;

    private BlockHighlighter() {
    }

    public static BlockHighlighter getInstance() {
        if (instance == null) {
            instance = new BlockHighlighter();
        }
        return instance;
    }

    public void addBlock(BlockPos pos) {
        if (!highlightedBlocks.contains(pos)) {
            highlightedBlocks.add(pos);
        }
    }

    public void addBlocks(List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            addBlock(pos);
        }
    }

    public void clearAll() {
        highlightedBlocks.clear();
    }

    public void setColor(float r, float g, float b, float a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    public List<BlockPos> getHighlightedBlocks() {
        return new ArrayList<>(highlightedBlocks);
    }

    public boolean hasHighlights() {
        return !highlightedBlocks.isEmpty();
    }

    /**
     * Emits block highlights through Minecraft 26.2's Gizmo pipeline.
     */
    public void emitHighlights() {
        if (highlightedBlocks.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        GizmoStyle style = GizmoStyle.strokeAndFill(toArgb(a), 2.0f, toArgb(a * 0.25f));
        for (BlockPos pos : highlightedBlocks) {
            // Do not force always-on-top so highlights obey depth and occlusion.
            Gizmos.cuboid(pos, style);
        }
    }

    private int toArgb(float alpha) {
        int alphaChannel = Math.clamp(Math.round(alpha * 255.0f), 0, 255);
        int redChannel = Math.clamp(Math.round(r * 255.0f), 0, 255);
        int greenChannel = Math.clamp(Math.round(g * 255.0f), 0, 255);
        int blueChannel = Math.clamp(Math.round(b * 255.0f), 0, 255);
        return (alphaChannel << 24) | (redChannel << 16) | (greenChannel << 8) | blueChannel;
    }
}
