package ru.alikdrone.hub;

import java.util.Locale;

/**
 * Safety rules for the controller files screen.
 *
 * <p>The screen deliberately works only with Storage Access Framework content
 * URIs selected by the user. It never guesses filesystem paths and never tries
 * to enter another application's private Android/data directory.</p>
 */
public final class ControllerFilesPolicy {
    private ControllerFilesPolicy() {
    }

    public static boolean isSafContentUri(String uri) {
        if (uri == null) {
            return false;
        }
        String normalized = uri.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("content://") && normalized.length() > "content://".length();
    }

    public static boolean canDelete(boolean userConfirmed, boolean itemCameFromCurrentListing) {
        return userConfirmed && itemCameFromCurrentListing;
    }

    public static String deletionPrompt(String displayName) {
        String safeName = displayName == null || displayName.trim().isEmpty()
                ? "этот файл"
                : "«" + displayName.trim() + "»";
        return "Удалить " + safeName
                + "? Действие выполняет системное хранилище Android и может быть необратимым.";
    }

    public static String scopeNotice() {
        return "Показана только конечная папка, которую вы выбрали в системном окне Android. "
                + "На Android 11 системный выбор может скрывать корень памяти, корень SD-карты, "
                + "папку Download и Android/data. Если нужная папка недоступна, выберите разрешённую "
                + "подпапку. Hub не обходит ограничения Android и не получает root-доступ.";
    }
}
