package ru.alikdrone.hub;

final class MessagingAppWarnings {
    private MessagingAppWarnings() {
    }

    static boolean requiresWarning(AppCatalog.Entry entry) {
        return entry != null && isMessagingEntry(entry);
    }

    static boolean requiresWarning(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        for (AppCatalog.Entry entry : AppCatalog.getEntries()) {
            if (isMessagingEntry(entry)
                    && entry.getInstalledPackageNames().contains(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMessagingEntry(AppCatalog.Entry entry) {
        return "telegram".equals(entry.getId())
                || "discord".equals(entry.getId());
    }
}
