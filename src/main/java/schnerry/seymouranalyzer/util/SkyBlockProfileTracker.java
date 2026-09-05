package schnerry.seymouranalyzer.util;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import schnerry.seymouranalyzer.SeymourAnalyzer;
import schnerry.seymouranalyzer.config.ClothConfig;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the active Hypixel SkyBlock profile from the login and profile-switch
 * messages sent by the server.
 */
public final class SkyBlockProfileTracker {
    public static final String ALL_PROFILES = "__all__";
    private static final Pattern PROFILE_NAME_PATTERN =
        Pattern.compile("^(?:You are playing on profile:|Your profile was changed to:) (.+?)(?: \\([^)]*\\))?$");
    private static final Pattern PROFILE_ID_PATTERN =
        Pattern.compile("^Profile ID: ([0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12})$");

    private static String activeProfileName;
    private static String activeProfileId;
    private static String pendingProfileName;
    private static boolean registered;

    private SkyBlockProfileTracker() {
    }

    public static void register() {
        if (registered) return;
        registered = true;

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;

            String text = StringUtility.removeFormatting(message.getString()).trim();
            Matcher nameMatcher = PROFILE_NAME_PATTERN.matcher(text);
            if (nameMatcher.matches()) {
                pendingProfileName = nameMatcher.group(1).trim();
                return;
            }

            Matcher idMatcher = PROFILE_ID_PATTERN.matcher(text);
            if (idMatcher.matches()) {
                if (pendingProfileName != null && !pendingProfileName.isBlank()) {
                    activeProfileId = idMatcher.group(1).toLowerCase();
                    activeProfileName = pendingProfileName;
                    ClothConfig.getInstance().putProfile(activeProfileId, pendingProfileName);
                    pendingProfileName = null;
                } else {
                    SeymourAnalyzer.LOGGER.warn(
                        "Ignored SkyBlock profile ID {} because no matching profile-name message was received",
                        idMatcher.group(1)
                    );
                }
            }
        });
    }

    public static Optional<String> getActiveProfileName() {
        return Optional.ofNullable(activeProfileName);
    }

    public static Optional<String> getActiveProfileId() {
        return Optional.ofNullable(activeProfileId);
    }

    public static boolean hasActiveProfile() {
        return activeProfileName != null && activeProfileId != null;
    }

    public static Map<String, String> getKnownProfiles() {
        return Map.copyOf(ClothConfig.getInstance().getProfiles());
    }

    public static java.util.List<String> getProfileOptions() {
        java.util.List<String> profileIds = new java.util.ArrayList<>();
        profileIds.add(ALL_PROFILES);
        profileIds.addAll(ClothConfig.getInstance().getProfiles().keySet());
        return profileIds;
    }

    public static String getProfileLabel(String profileId) {
        if (ALL_PROFILES.equals(profileId)) return "All";
        return ClothConfig.getInstance().getCustomProfileNames().getOrDefault(
            profileId,
            ClothConfig.getInstance().getProfiles().getOrDefault(profileId, profileId)
        );
    }

    public static boolean setActiveProfileCustomName(String customName) {
        if (activeProfileId == null) return false;
        ClothConfig.getInstance().setCustomProfileName(activeProfileId, customName);
        return true;
    }

    public static boolean clearActiveProfileCustomName() {
        if (activeProfileId == null) return false;
        ClothConfig.getInstance().clearCustomProfileName(activeProfileId);
        return true;
    }
}
