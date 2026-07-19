package schnerry.seymouranalyzer.render;

import net.minecraft.client.Minecraft;
//? if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
//?}
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages and renders highlighted block positions in the world.
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

    // MultiBufferSource/immediate-mode line rendering was removed entirely in 26.2 in favor of
    // an extraction-based world render pipeline (FabricRenderState/RenderPipeline). Porting this
    // feature to that model is unimplemented for now - block highlighting is a no-op on >=26.2.
    //? if <26.2 {
    /**
     * Called by DebugRendererMixin to render block highlights.
     */
    public void renderHighlights(PoseStack poseStack, MultiBufferSource vertexConsumers, Vec3 cameraPos) {
        if (highlightedBlocks.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        for (BlockPos pos : highlightedBlocks) {
            // Calculate camera-relative coordinates for a 1x1x1 block
            float x1 = (float)(pos.getX() - cameraPos.x);
            float y1 = (float)(pos.getY() - cameraPos.y);
            float z1 = (float)(pos.getZ() - cameraPos.z);
            float x2 = x1 + 1.0f;
            float y2 = y1 + 1.0f;
            float z2 = z1 + 1.0f;

            drawBoxOutline(poseStack, vertexConsumers, x1, y1, z1, x2, y2, z2, r, g, b, a);
        }
    }

    /**
     * Draws a box outline using lines.
     */
    private void drawBoxOutline(PoseStack poseStack, MultiBufferSource vertexConsumers,
                                 float x1, float y1, float z1, float x2, float y2, float z2,
                                 float r, float g, float b, float a) {
        VertexConsumer lines = vertexConsumers.getBuffer(RenderTypes.lines());
        Matrix4f matrix = poseStack.last().pose();

        // Bottom face edges
        drawLine(lines, matrix, x1, y1, z1, x2, y1, z1, r, g, b, a);
        drawLine(lines, matrix, x2, y1, z1, x2, y1, z2, r, g, b, a);
        drawLine(lines, matrix, x2, y1, z2, x1, y1, z2, r, g, b, a);
        drawLine(lines, matrix, x1, y1, z2, x1, y1, z1, r, g, b, a);

        // Top face edges
        drawLine(lines, matrix, x1, y2, z1, x2, y2, z1, r, g, b, a);
        drawLine(lines, matrix, x2, y2, z1, x2, y2, z2, r, g, b, a);
        drawLine(lines, matrix, x2, y2, z2, x1, y2, z2, r, g, b, a);
        drawLine(lines, matrix, x1, y2, z2, x1, y2, z1, r, g, b, a);

        // Vertical edges
        drawLine(lines, matrix, x1, y1, z1, x1, y2, z1, r, g, b, a);
        drawLine(lines, matrix, x2, y1, z1, x2, y2, z1, r, g, b, a);
        drawLine(lines, matrix, x2, y1, z2, x2, y2, z2, r, g, b, a);
        drawLine(lines, matrix, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    /**
     * Draws a single line segment.
     */
    private void drawLine(VertexConsumer consumer, Matrix4f matrix,
                          float x1, float y1, float z1, float x2, float y2, float z2,
                          float r, float g, float b, float a) {
        // Calculate normal for the line (direction)
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len < 0.0001f) len = 1.0f;
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;

        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a).setNormal(nx, ny, nz).setLineWidth(2.0f);
        consumer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a).setNormal(nx, ny, nz).setLineWidth(2.0f);
    }
    //?}
}
