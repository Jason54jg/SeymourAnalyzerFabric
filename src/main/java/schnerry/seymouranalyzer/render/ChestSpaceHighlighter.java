package schnerry.seymouranalyzer.render;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import schnerry.seymouranalyzer.scanner.ChestSpaceTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders purple frames around chests with free space.
 * Integrated with ChestSpaceTracker cache for live updates.
 */
public class ChestSpaceHighlighter {
    private static ChestSpaceHighlighter instance;
    private final List<BlockPos> highlightedChests = new ArrayList<>();

    // Purple color (RGBA): 0xAA00FF with alpha
    private static final float PURPLE_R = 0.67f;  // 170/255
    private static final float PURPLE_G = 0.0f;
    private static final float PURPLE_B = 1.0f;   // 255/255
    private static final float ALPHA = 0.7f;

    private ChestSpaceHighlighter() {
    }

    public static ChestSpaceHighlighter getInstance() {
        if (instance == null) {
            instance = new ChestSpaceHighlighter();
        }
        return instance;
    }

    /**
     * Update highlights based on current ChestSpaceTracker cache.
     * Clears previous highlights and rebuilds from cache.
     */
    public void updateFromCache() {
        highlightedChests.clear();

        if (!ChestSpaceTracker.getInstance().isHighlightEnabled()) {
            return;
        }

        for (Map.Entry<String, ChestSpaceTracker.ChestInfo> entry
                : ChestSpaceTracker.getInstance().getCacheSnapshot().entrySet()) {
            ChestSpaceTracker.ChestInfo info = entry.getValue();
            if (info != null && info.totalSlots - info.usedSlots > 0) {
                String[] coordinates = entry.getKey().split(",");
                if (coordinates.length != 3) continue;

                try {
                    highlightedChests.add(new BlockPos(
                        Integer.parseInt(coordinates[0]),
                        Integer.parseInt(coordinates[1]),
                        Integer.parseInt(coordinates[2])
                    ));
                } catch (NumberFormatException ignored) {
                    // Ignore malformed legacy cache entries.
                }
            }
        }
    }

    /**
     * Render all highlighted chests as purple frames.
     */
    public void emitHighlights() {
        if (highlightedChests.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        GizmoStyle purpleStyle = GizmoStyle.strokeAndFill(toArgb(ALPHA), 2.5f, toArgb(ALPHA * 0.3f));
        Set<BlockPos> remaining = new HashSet<>(highlightedChests);
        for (BlockPos pos : highlightedChests) {
            if (!remaining.remove(pos)) continue;

            BlockPos partner = getDoubleChestPartner(pos, client);
            if (partner != null && remaining.remove(partner)) {
                Gizmos.cuboid(new AABB(pos).minmax(new AABB(partner)), purpleStyle);
            } else {
                Gizmos.cuboid(pos, purpleStyle);
            }
        }
    }

    private BlockPos getDoubleChestPartner(BlockPos pos, Minecraft client) {
        net.minecraft.world.level.block.state.BlockState state = client.level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }

        return pos.relative(ChestBlock.getConnectedDirection(state));
    }

    public void clearAll() {
        highlightedChests.clear();
    }

    public int getHighlightCount() {
        return highlightedChests.size();
    }

    /**
     * Convert RGB + alpha float values to ARGB int.
     */
    private static int toArgb(float alpha) {
        int alphaChannel = Math.clamp(Math.round(alpha * 255.0f), 0, 255);
        int redChannel = Math.clamp(Math.round(PURPLE_R * 255.0f), 0, 255);
        int greenChannel = Math.clamp(Math.round(PURPLE_G * 255.0f), 0, 255);
        int blueChannel = Math.clamp(Math.round(PURPLE_B * 255.0f), 0, 255);
        return (alphaChannel << 24) | (redChannel << 16) | (greenChannel << 8) | blueChannel;
    }
}
