package schnerry.seymouranalyzer;

import lombok.Getter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if <26.1 {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
*///?} else {
//? if <26.2 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?}
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import schnerry.seymouranalyzer.command.SeymourCommand;
import schnerry.seymouranalyzer.data.ChecklistCacheGenerator;
import schnerry.seymouranalyzer.data.CollectionManager;
import schnerry.seymouranalyzer.debug.ItemDebugger;
import schnerry.seymouranalyzer.gambling.VisitorChatListener;
import schnerry.seymouranalyzer.gui.GuiScaleManager;
import schnerry.seymouranalyzer.keybind.KeyBindings;
import schnerry.seymouranalyzer.render.BlockHighlighter;
import schnerry.seymouranalyzer.render.HexTooltipRenderer;
import schnerry.seymouranalyzer.render.InfoBoxRenderer;
import schnerry.seymouranalyzer.render.ItemSlotHighlighter;
import schnerry.seymouranalyzer.scanner.ChestScanner;
import schnerry.seymouranalyzer.scanner.TradeScanner;

/**
 * Client-side initialization
 */
public class SeymourAnalyzerClient implements ClientModInitializer {
    @Getter
    private static ChestScanner chestScanner;

    @Override
    public void onInitializeClient() {
        SeymourAnalyzer.LOGGER.info("Initializing Seymour Analyzer client...");

        // Initialize scanner
        chestScanner = new ChestScanner();

        // Initialize BlockHighlighter. DebugRenderer.render(...) doesn't exist past 1.21.10 (it
        // became emitGizmos(), which has no PoseStack/buffer access), so on every remaining
        // version we hook a Fabric API render event instead of mixing in directly:
        // - 1.21.11: WorldRenderEvents.BEFORE_DEBUG_RENDER (LevelRenderEvents doesn't exist yet).
        // - 26.1.x: LevelRenderEvents.BEFORE_GIZMOS (WorldRenderEvents was renamed to this).
        // - >=26.2: unimplemented - MultiBufferSource/immediate-mode rendering was removed
        //   entirely (see BlockHighlighter.java).
        BlockHighlighter.getInstance();
        //? if <26.1 {
/*WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> {
            BlockHighlighter highlighter = BlockHighlighter.getInstance();
            if (!highlighter.hasHighlights()) return;
            Vec3 cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().position();
            highlighter.renderHighlights(context.matrices(), context.consumers(), cameraPos);
        });
        *///?} else {
        //? if <26.2 {
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            BlockHighlighter highlighter = BlockHighlighter.getInstance();
            if (!highlighter.hasHighlights()) return;
            Vec3 cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().position();
            highlighter.renderHighlights(context.poseStack(), context.bufferSource(), cameraPos);
        });
        //?}
        //?}
        SeymourAnalyzer.LOGGER.info("Initialized BlockHighlighter");

        // Initialize ItemSlotHighlighter (registers screen render events)
        ItemSlotHighlighter.getInstance();
        SeymourAnalyzer.LOGGER.info("Initialized ItemSlotHighlighter");

        // Initialize InfoBoxRenderer (registers screen render events)
        InfoBoxRenderer.getInstance();
        SeymourAnalyzer.LOGGER.info("Initialized InfoBoxRenderer");

        // Initialize ItemDebugger (for /seymour debug command)
        ItemDebugger.getInstance();
        SeymourAnalyzer.LOGGER.info("Initialized ItemDebugger");

        // Initialize HexTooltipRenderer (shows hex on item tooltips like F3+H)
        HexTooltipRenderer.getInstance();
        SeymourAnalyzer.LOGGER.info("Initialized HexTooltipRenderer");

        // Initialize GuiScaleManager (handles automatic GUI scale forcing)
        GuiScaleManager.getInstance();
        SeymourAnalyzer.LOGGER.info("Initialized GuiScaleManager");

        // Register Seymour visitor chat listener (for auto-roll gambling)
        VisitorChatListener.register();

        // Register TradeScanner chat listener (for trade-completion detection)
        TradeScanner.register();

        // Generate checklist caches on startup (runs async to avoid blocking)
        new Thread(() -> {
            try {
                // Wait a bit to let collection load
                Thread.sleep(1000);
                ChecklistCacheGenerator.generateAllCaches();
            } catch (Exception e) {
                SeymourAnalyzer.LOGGER.error("Failed to generate initial checklist cache", e);
            }
        }, "ChecklistCacheInitializer").start();

        // Register keybindings (Press O to open GUI)
        KeyBindings.register();
        SeymourAnalyzer.LOGGER.info("Registered keybindings");

        // Register commands (MUST be in client initializer for client commands)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            SeymourCommand.register(dispatcher);
            SeymourAnalyzer.LOGGER.info("Registered /seymour commands");
        });

        // Register client tick for scanner
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && client.level != null) {
                chestScanner.tick(client);
                TradeScanner.getInstance().tick(client);
                // Tick collection manager for auto-save
                CollectionManager.getInstance().tick();
            }
        });

        // Register shutdown hook to save collection on exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SeymourAnalyzer.LOGGER.info("Saving collection on shutdown...");
            CollectionManager.getInstance().forceSync();
        }, "CollectionShutdownSaver"));

        SeymourAnalyzer.LOGGER.info("Seymour Analyzer client initialized!");
    }

    public static ChestScanner getScanner() {
        return chestScanner;
    }
}

