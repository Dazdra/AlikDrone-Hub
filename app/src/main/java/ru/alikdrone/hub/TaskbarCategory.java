package ru.alikdrone.hub;

import java.util.Locale;

/**
 * Stable application categories used by both the Hub screen and Taskbar.
 *
 * <p>Persist {@link #getId()} rather than an enum ordinal. This keeps saved
 * user overrides valid if enum constants are reordered in a future release.</p>
 */
public enum TaskbarCategory {
    FLIGHT(
            "flight",
            R.string.taskbar_category_flight,
            R.color.category_flight
    ),
    COMMUNICATION(
            "communication",
            R.string.taskbar_category_communication,
            R.color.category_communication
    ),
    MAPS_WEATHER(
            "maps_weather",
            R.string.taskbar_category_maps_weather,
            R.color.category_maps_weather
    ),
    MEDIA_STREAMING(
            "media_streaming",
            R.string.taskbar_category_media_streaming,
            R.color.category_media_streaming
    ),
    FILES_TOOLS(
            "files_tools",
            R.string.taskbar_category_files_tools,
            R.color.category_files_tools
    ),
    OTHER(
            "other",
            R.string.taskbar_category_other,
            R.color.category_other
    );

    private final String id;
    private final int labelResId;
    private final int colorResId;

    TaskbarCategory(String id, int labelResId, int colorResId) {
        this.id = id;
        this.labelResId = labelResId;
        this.colorResId = colorResId;
    }

    public String getId() {
        return id;
    }

    public int getLabelResId() {
        return labelResId;
    }

    public int getColorResId() {
        return colorResId;
    }

    /**
     * Restores a persisted category id, or returns {@code null} for an
     * unknown/corrupt value. Callers that need automatic classification
     * should use {@link #resolve(String, String)}.
     */
    public static TaskbarCategory fromId(String id) {
        return findById(id);
    }

    /**
     * Applies a valid user override, otherwise classifies the package
     * automatically. A stored {@code "other"} is a valid explicit override.
     */
    public static TaskbarCategory resolve(
            String packageName,
            String overrideId
    ) {
        TaskbarCategory override = findById(overrideId);
        return override == null ? classify(packageName) : override;
    }

    /**
     * Best-effort classification based only on the stable Android package
     * name. It intentionally avoids labels, locale, install order and list
     * positions, so mapping remains deterministic on every controller.
     */
    public static TaskbarCategory classify(String packageName) {
        String value = normalize(packageName);
        if (value.isEmpty()) {
            return OTHER;
        }

        if (isHubOrFlightPackage(value)) {
            return FLIGHT;
        }
        if (containsAny(
                value,
                "discord",
                "telegram",
                "challegram",
                "whatsapp",
                "signal",
                "securesms",
                "viber",
                "skype",
                "zoom",
                "microsoft.teams",
                "slack"
        )) {
            return COMMUNICATION;
        }
        if (containsAny(
                value,
                "windy",
                "windyty",
                "weather",
                "meteoblue",
                "windfinder",
                "rainviewer",
                "radar",
                ".maps",
                ".map.",
                "osmand",
                "organicmaps",
                "mapy",
                "gpstest",
                "gps.test"
        )) {
            return MAPS_WEATHER;
        }
        if (containsAny(
                value,
                "youtube",
                "rutube",
                "twitch",
                "spotify",
                "vlc",
                "screenstream",
                "screen.stream",
                "livekit",
                "streaming",
                "media.player"
        )) {
            return MEDIA_STREAMING;
        }
        if (containsAny(
                value,
                "shareit",
                "anyshare",
                "cromite",
                "chrome",
                "chromium",
                "firefox",
                "browser",
                "filemanager",
                "file.manager",
                "packageinstaller",
                "package.installer",
                "quicktiles",
                "utilities",
                "proxyutility",
                "localsend"
        )) {
            return FILES_TOOLS;
        }
        return OTHER;
    }

    private static TaskbarCategory findById(String id) {
        String normalized = normalize(id);
        if (normalized.isEmpty()) {
            return null;
        }
        for (TaskbarCategory category : values()) {
            if (category.id.equals(normalized)) {
                return category;
            }
        }
        return null;
    }

    private static boolean isHubOrFlightPackage(String value) {
        return "ru.alikdrone.hub".equals(value)
                || value.startsWith("com.dji.")
                || value.startsWith("dji.")
                || value.startsWith("com.airdata.")
                || value.startsWith("app.airdata.")
                || value.contains(".djifly")
                || value.contains(".dji.fly")
                || value.contains(".flightrecord");
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.US);
    }
}
