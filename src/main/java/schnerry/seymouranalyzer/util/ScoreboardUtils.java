package schnerry.seymouranalyzer.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Utility methods for reading the Minecraft sidebar scoreboard.
 * Hypixel SkyBlock encodes each visible line as a team with a unique
 * invisible fake-player name; the actual text lives in the team prefix/suffix.
 */
public class ScoreboardUtils {

    /**
     * Checks whether any line in the sidebar scoreboard contains {@code searchText}.
     * Minecraft formatting codes (§X) are stripped before the comparison.
     *
     * @param mc         Minecraft client instance
     * @param searchText text to look for (case-sensitive)
     * @return {@code true} if at least one scoreboard line contains the text
     */
    public static boolean scoreboardContains(Minecraft mc, String searchText) {
        if (mc == null || mc.level == null || searchText == null || searchText.isEmpty()) {
            return false;
        }

        try {
            Scoreboard scoreboard = mc.level.getScoreboard();
            Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (sidebar == null) return false;

            for (PlayerTeam team : scoreboard.getPlayerTeams()) {
                for (String player : team.getPlayers()) {
                    // On Hypixel each line = team-prefix  +  invisible-player-name  +  team-suffix
                    String prefix = team.getPlayerPrefix().getString();
                    String suffix = team.getPlayerSuffix().getString();
                    String line   = stripFormatting(prefix + player + suffix);
                    if (line.contains(searchText)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // Silently ignore any scoreboard read errors (wrong game state, etc.)
        }

        return false;
    }

    /**
     * Returns {@code true} when the player is on their own Private Island.
     * Detects the "Your Island" line that Hypixel SkyBlock places in the sidebar.
     *
     * @param mc Minecraft client instance
     */
    public static boolean isOnOwnIsland(Minecraft mc) {
        return scoreboardContains(mc, "Your Island");
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Removes all Minecraft formatting codes (§ + one char) from a string.
     */
    private static String stripFormatting(String text) {
        if (text == null) return "";
        return text
                // Hypixel may use non-standard § codes in its hidden scoreboard entry names.
                .replaceAll("§.", "")
                // Mojangson / UTF-8-mangled variant (Â§ artefact)
                .replaceAll("Â§.", "")
                .trim();
    }
}
