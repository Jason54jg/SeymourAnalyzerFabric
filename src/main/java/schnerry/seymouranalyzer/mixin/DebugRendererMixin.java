package schnerry.seymouranalyzer.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import schnerry.seymouranalyzer.render.BlockHighlighter;

/**
 * DebugRendererMixin for 1.21.10
 *
 * WorldRenderEvents was removed in 1.21.9, so we use a direct mixin.
 * In 26.2 DebugRenderer now emits gizmos instead of receiving buffer consumers directly.
 */
@Mixin(net.minecraft.client.renderer.debug.DebugRenderer.class)
public class DebugRendererMixin {

    /**
     * Inject at the TAIL of DebugRenderer.emitGizmos to add our highlights
     * into the game's backend-agnostic gizmo pipeline.
     */
    @Inject(
        method = "emitGizmos",
        at = @At("TAIL")
    )
    private void onDebugRender(
            Frustum frustum,
            double cameraX,
            double cameraY,
            double cameraZ,
            float tickProgress,
            CallbackInfo ci
    ) {
        BlockHighlighter highlighter = BlockHighlighter.getInstance();
        if (!highlighter.hasHighlights()) return;

        highlighter.emitHighlights();
    }
}



