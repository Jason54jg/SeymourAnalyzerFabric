package schnerry.seymouranalyzer.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.render.InfoBoxRenderer;

import java.util.Set;

/**
 * Debug utility to log ALL item data (NBT, components, everything) to console
 * Activated by /seymour debug command
 */
public class ItemDebugger {
    private static ItemDebugger instance;
    private boolean enabled = false;

    private ItemDebugger() {}

    public static ItemDebugger getInstance() {
        if (instance == null) {
            instance = new ItemDebugger();
        }
        return instance;
    }

    /**
     * Enable debug mode - waits for capture key press while hovering an item
     */
    public void enable() {
        enabled = true;

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(
                Component.literal("§a[Seymour Debug] §eEnabled! §7Open an inventory, hover over an item and press the §ecapture key§7.")
            );
        }
    }

    /**
     * Disable debug mode
     */
    @SuppressWarnings("unused")
    public void disable() {
        enabled = false;
    }

    /**
     * Called when the capture keybind is pressed.
     * Logs the currently hovered item if debug mode is active.
     */
    public void onCaptureKeyPressed() {
        if (!enabled) return;

        try {
            Minecraft client = Minecraft.getInstance();
            if (client.gui.screen() instanceof AbstractContainerScreen<?>) {
                ItemStack stack = InfoBoxRenderer.getInstance().getLastHoveredStack();

                if (stack != null && !stack.isEmpty()) {
                    logAllItemData(stack);
                    enabled = false;

                    if (client.player != null) {
                        client.player.sendSystemMessage(
                            Component.literal("§a[Seymour Debug] §7Item data logged to console! Debug mode disabled.")
                        );
                    }
                    SeymourAnalyzer.LOGGER.info("[DEBUG] Debug mode disabled after logging item");
                } else {
                    if (client.player != null) {
                        client.player.sendSystemMessage(
                            Component.literal("§a[Seymour Debug] §cNo item hovered! Open an inventory and hover over an item first.")
                        );
                    }
                }
            } else {
                if (client.player != null) {
                    client.player.sendSystemMessage(
                        Component.literal("§a[Seymour Debug] §cOpen an inventory screen first, then hover over an item and press the key.")
                    );
                }
            }
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("[DEBUG] Error logging hovered item", e);
        }
    }

    /**
     * Log ALL data about an ItemStack to console
     */
    private void logAllItemData(ItemStack stack) {
        SeymourAnalyzer.LOGGER.info("==================== ITEM DEBUG START ====================");

        try {
            // Basic info
            SeymourAnalyzer.LOGGER.info("=== BASIC INFO ===");
            SeymourAnalyzer.LOGGER.info("Item: {}", stack.getItem());
            SeymourAnalyzer.LOGGER.info("Registry ID: {}", stack.getItem().getDescriptionId());
            SeymourAnalyzer.LOGGER.info("Count: {}", stack.getCount());
            SeymourAnalyzer.LOGGER.info("Name: {}", stack.getHoverName().getString());
            SeymourAnalyzer.LOGGER.info("Max Stack Size: {}", stack.getMaxStackSize());
            SeymourAnalyzer.LOGGER.info("Damaged: {}", stack.isDamaged());
            SeymourAnalyzer.LOGGER.info("Damageable: {}", stack.isDamageableItem());

            // Display name and lore
            SeymourAnalyzer.LOGGER.info("\n=== DISPLAY INFO ===");
            // Tooltip API changed in 1.21.11 - just log hover name and lore component
            SeymourAnalyzer.LOGGER.info("Hover Name: {}", stack.getHoverName().getString());

            // All components
            SeymourAnalyzer.LOGGER.info("\n=== DATA COMPONENTS ===");
            Set<DataComponentType<?>> componentTypes = stack.getComponents().keySet();
            SeymourAnalyzer.LOGGER.info("Total Components: {}", componentTypes.size());

            for (DataComponentType<?> type : componentTypes) {
                try {
                    Object value = stack.get(type);
                    SeymourAnalyzer.LOGGER.info("Component: {} = {}", type, value);

                    // Special handling for specific component types
                    if (type == DataComponents.CUSTOM_DATA) {
                        CustomData customData = (CustomData) value;
                        if (customData != null) {
                            logNbtData(customData.copyTag());
                        }
                    } else if (type == DataComponents.DYED_COLOR) {
                        SeymourAnalyzer.LOGGER.info("  -> Dyed Color Details: {}", value);
                    } else if (type == DataComponents.CUSTOM_NAME) {
                        SeymourAnalyzer.LOGGER.info("  -> Custom Name: {}", value);
                    } else if (type == DataComponents.LORE) {
                        SeymourAnalyzer.LOGGER.info("  -> Lore: {}", value);
                    }
                } catch (Exception e) {
                    SeymourAnalyzer.LOGGER.error("  Error reading component {}: {}", type, e.getMessage());
                }
            }

            // NBT data (if any)
            SeymourAnalyzer.LOGGER.info("\n=== NBT DATA ===");
            CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            if (!customData.isEmpty()) {
                CompoundTag nbt = customData.copyTag();
                logNbtData(nbt);
            } else {
                SeymourAnalyzer.LOGGER.info("No custom NBT data");
            }

            // Enchantments (if any)
            SeymourAnalyzer.LOGGER.info("\n=== ENCHANTMENTS ===");
            ItemEnchantments enchantments = stack.getEnchantments();
            if (enchantments.isEmpty()) {
                SeymourAnalyzer.LOGGER.info("No enchantments");
            } else {
                SeymourAnalyzer.LOGGER.info("Enchantments: {}", enchantments);
            }

            // Item-specific data
            SeymourAnalyzer.LOGGER.info("\n=== ITEM TYPE SPECIFIC ===");
            SeymourAnalyzer.LOGGER.info("Item Class: {}", stack.getItem().getClass().getName());
            SeymourAnalyzer.LOGGER.info("Is Damageable: {}", stack.isDamageableItem());
            SeymourAnalyzer.LOGGER.info("Max Damage: {}", stack.getMaxDamage());

            // Raw toString
            SeymourAnalyzer.LOGGER.info("\n=== RAW DATA ===");
            SeymourAnalyzer.LOGGER.info("ItemStack: {}", stack);

        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("Error logging item data", e);
        }

        SeymourAnalyzer.LOGGER.info("==================== ITEM DEBUG END ====================\n");
    }

    /**
     * Recursively log NBT compound data
     */
    private void logNbtData(CompoundTag nbt) {
        logNbtData(nbt, 0);
    }

    /**
     * Recursively log NBT compound data with indentation
     */
    private void logNbtData(CompoundTag nbt, int indent) {
        String indentStr = "  ".repeat(indent);

        for (String key : nbt.keySet()) {
            try {
                Tag element = nbt.get(key);
                if (element == null) {
                    SeymourAnalyzer.LOGGER.info("{}[NBT] {} = null", indentStr, key);
                    continue;
                }

                // Handle different NBT types
                if (element instanceof CompoundTag compound) {
                    SeymourAnalyzer.LOGGER.info("{}[NBT] {} (Compound):", indentStr, key);
                    logNbtData(compound, indent + 1);
                } else {
                    // Use asString() for primitive types (returns Optional<String>)
                    String value = element.asString().orElse(element.toString());
                    SeymourAnalyzer.LOGGER.info("{}[NBT] {} = {}", indentStr, key, value);
                }
            } catch (Exception e) {
                SeymourAnalyzer.LOGGER.error("{}[NBT] Error reading key {}: {}", indentStr, key, e.getMessage());
            }
        }
    }

    public void copyCurrentPieceHexToClipboard() {
        if (!enabled) return;

        try {
            Minecraft client = Minecraft.getInstance();
            if (client.gui.screen() instanceof AbstractContainerScreen<?>) {
                ItemStack stack = InfoBoxRenderer.getInstance().getLastHoveredStack();

                if (stack != null && !stack.isEmpty()) {
                    CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
                    CompoundTag nbt = customData.copyTag();
                    String hex = String.valueOf(nbt.getString("SeymourHex"));
                    if (!hex.isEmpty()) {
                        // Copy to clipboard
                        client.keyboardHandler.setClipboard(hex);
                        client.player.sendSystemMessage(
                            Component.literal("§a[Seymour] §7Copied piece hex to clipboard: §e" + hex)
                        );
                    } else {
                        client.player.sendSystemMessage(
                            Component.literal("§a[Seymour] §cNo piece hex found in hovered item.")
                        );
                    }
                } else {
                    client.player.sendSystemMessage(
                        Component.literal("§a[Seymour] §cNo item hovered! Open an inventory and hover over an item first.")
                    );
                }
            } else {
                client.player.sendSystemMessage(
                    Component.literal("§a[Seymour] §cOpen an inventory screen first, then hover over an item and press the key.")
                );
            }
        } catch (Exception e) {
            SeymourAnalyzer.LOGGER.error("§a[Seymour] Error copying piece hex", e);
        }
    }
}
