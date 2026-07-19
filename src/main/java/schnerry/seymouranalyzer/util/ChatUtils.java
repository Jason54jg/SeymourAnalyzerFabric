package schnerry.seymouranalyzer.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Player.sendSystemMessage(Component) was renamed from displayClientMessage(Component, boolean)
 * starting with 26.1 - centralised here so call sites don't need per-version branching.
 */
public final class ChatUtils {
    private ChatUtils() {}

    public static void sendSystemMessage(LocalPlayer player, Component message) {
        //? if <26.1 {
        /*player.displayClientMessage(message, false);
        *///?} else {
        player.sendSystemMessage(message);
        //?}
    }
}
