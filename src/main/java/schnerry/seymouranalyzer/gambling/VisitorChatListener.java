package schnerry.seymouranalyzer.gambling;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.config.ClothConfig;
import schnerry.seymouranalyzer.util.StringUtility;

import java.util.Set;

public class VisitorChatListener {

    private static long lastTriggerMs = 0;
    private static final long COOLDOWN_MS = 15_000;
    private static final long RECENCY_WINDOW_MS = 60_000;
    private static final String SEYMOUR_COMPLETION_PHRASE = "pleasure doing business with you";

    private static final Set<Item> LEATHER_ARMOR = Set.of(
            Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE,
            Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS
    );

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            if (!ClothConfig.getInstance().isAutoRollOnVisitor()) return;

            String text = message.getString().toLowerCase();
            if (!isSeymourCompletionMessage(text)) return;

            long now = System.currentTimeMillis();
            if (now - lastTriggerMs < COOLDOWN_MS) return;
            lastTriggerMs = now;

            SeymourAnalyzer.LOGGER.info("[Gambling] Seymour trade detected, scanning inventory...");

            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> new Thread(() -> {
                try {
                    Thread.sleep(500);
                    mc.execute(() -> triggerRollFromInventory(mc, false));
                } catch (InterruptedException ignored) {}
            }, "SeymourAutoRoll").start());
        });
    }

    public static void triggerRollFromInventory(Minecraft mc, boolean skipRecency) {
        long searchTime = System.currentTimeMillis();
        SeymourPieceResult piece = findNewestSeymourPiece(mc, searchTime, skipRecency);

        if (piece != null) {
            SeymourAnalyzer.LOGGER.info("[Gambling] Rolling with piece: {} #{}",
                    piece.skyblockId, String.format("%06X", piece.rgb));
            mc.setScreen(new GamblingScreen(piece.rgb, piece.item));
        } else {
            SeymourAnalyzer.LOGGER.info("[Gambling] No Seymour piece found, random roll");
            mc.setScreen(new GamblingScreen());
        }
    }

    private static boolean isSeymourCompletionMessage(String rawLine) {
        String parsedMsg = StringUtility.removeFormatting(rawLine).trim().toLowerCase();

        return (parsedMsg.startsWith("offer accepted") && parsedMsg.contains("seymour"))
                || (parsedMsg.startsWith("[npc] seymour:") && parsedMsg.contains(SEYMOUR_COMPLETION_PHRASE));
    }

    private record SeymourPieceResult(int rgb, Item item, long timestamp, String skyblockId) {}

    private static SeymourPieceResult findNewestSeymourPiece(Minecraft mc, long now, boolean skipRecency) {
        LocalPlayer player = mc.player;
        if (player == null) return null;

        SeymourPieceResult best = null;

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (!LEATHER_ARMOR.contains(stack.getItem())) continue;

            String itemName = stack.getHoverName().getString();
            if (!StringUtility.isSeymourArmor(itemName)) continue;

            DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
            if (dyed == null) continue;

            CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            CompoundTag nbt = customData.copyTag();
            if (!nbt.contains("timestamp")) continue;

            long timestamp = nbt.getLong("timestamp").orElse(0L);
            if (timestamp <= 0) continue;

            if (!skipRecency) {
                long age = now - timestamp;
                if (age > RECENCY_WINDOW_MS || age < -5000) continue;
            }

            String skyblockId = nbt.getString("id").orElse("unknown");
            int rgb = dyed.rgb() & 0xFFFFFF;

            if (best == null || timestamp > best.timestamp) {
                best = new SeymourPieceResult(rgb, stack.getItem(), timestamp, skyblockId);
            }
        }

        return best;
    }
}
