package ru.alikdrone.hub;

import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Catalog metadata shared by the regular and public GitHub editions.
 *
 * <p>The concrete entries live in a build-type source set. This keeps the
 * regular AlikDrone catalog unchanged while ensuring that the GitHub APK
 * exposes only the smaller public mirror catalog.</p>
 */
public final class AppCatalog {
    private static final Pattern SAFE_ID =
            Pattern.compile("[a-z][a-z0-9_-]{0,47}");
    private static final Pattern SAFE_FILE_NAME =
            Pattern.compile(
                    "[A-Za-z0-9][A-Za-z0-9._-]{0,94}\\.(apk|xapk)",
                    Pattern.CASE_INSENSITIVE
            );
    private static final Pattern SHA256_HEX =
            Pattern.compile("[0-9a-fA-F]{64}");

    public enum Action {
        DOWNLOAD_APK,
        OPEN_WEB
    }

    public static final class Entry {
        private final String id;
        private final String displayName;
        private final String packageName;
        private final Uri sourceUri;
        private final String fileName;
        private final long expectedVersionCode;
        private final String expectedSignerSha256;
        private final String expectedFileSha256;
        private final List<String> installedPackageNames;
        private final Action action;

        private Entry(
                Action action,
                String id,
                String displayName,
                String packageName,
                String sourceUrl,
                String fileName,
                long expectedVersionCode,
                String expectedSignerSha256,
                String expectedFileSha256,
                String... alternativePackageNames
        ) {
            if (action == null) {
                throw new IllegalArgumentException("Catalog action is required");
            }
            if (id == null || !SAFE_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Unsafe catalog id: " + id);
            }
            if (displayName == null || displayName.trim().isEmpty()) {
                throw new IllegalArgumentException("Display name is required");
            }
            if ((packageName == null || packageName.trim().isEmpty())
                    && !"custom".equals(id)) {
                throw new IllegalArgumentException("Package name is required");
            }

            Uri parsedUri = Uri.parse(sourceUrl);
            validateDownloadUri(parsedUri);
            if (action == Action.DOWNLOAD_APK) {
                validateFileName(fileName);
            } else if (fileName != null) {
                throw new IllegalArgumentException(
                        "Web catalog entries must not define an APK file"
                );
            }
            if (expectedSignerSha256 != null
                    && !SHA256_HEX.matcher(expectedSignerSha256).matches()) {
                throw new IllegalArgumentException("Invalid signer SHA-256");
            }
            if (expectedFileSha256 != null
                    && !SHA256_HEX.matcher(expectedFileSha256).matches()) {
                throw new IllegalArgumentException("Invalid file SHA-256");
            }

            this.action = action;
            this.id = id;
            this.displayName = displayName;
            this.packageName = packageName;
            this.sourceUri = parsedUri;
            this.fileName = fileName;
            this.expectedVersionCode = expectedVersionCode;
            this.expectedSignerSha256 = expectedSignerSha256 == null
                    ? null
                    : expectedSignerSha256.toLowerCase(Locale.US);
            this.expectedFileSha256 = expectedFileSha256 == null
                    ? null
                    : expectedFileSha256.toLowerCase(Locale.US);

            List<String> installedPackages = new ArrayList<>();
            if (packageName != null && !packageName.trim().isEmpty()) {
                installedPackages.add(packageName);
            }
            if (alternativePackageNames != null) {
                for (String alternative : alternativePackageNames) {
                    if (alternative != null
                            && !alternative.trim().isEmpty()
                            && !installedPackages.contains(alternative)) {
                        installedPackages.add(alternative);
                    }
                }
            }
            this.installedPackageNames = Collections.unmodifiableList(
                    installedPackages
            );
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getPackageName() {
            return packageName;
        }

        /**
         * Returns the source URI. The historical method name is retained to
         * avoid breaking download-controller state written by older builds.
         */
        public Uri getDownloadUri() {
            return sourceUri;
        }

        public Uri getSourceUri() {
            return sourceUri;
        }

        public String getFileName() {
            return fileName;
        }

        public Action getAction() {
            return action;
        }

        public boolean isDownloadable() {
            return action == Action.DOWNLOAD_APK;
        }

        /**
         * Returns the pinned version code, or {@code -1} for vendor "latest"
         * endpoints whose version changes over time.
         */
        public long getExpectedVersionCode() {
            return expectedVersionCode;
        }

        public String getExpectedSignerSha256() {
            return expectedSignerSha256;
        }

        public String getExpectedFileSha256() {
            return expectedFileSha256;
        }

        public List<String> getInstalledPackageNames() {
            return installedPackageNames;
        }
    }

    private static final List<Entry> ENTRIES;
    private static final Map<String, Entry> ENTRIES_BY_ID;

    static {
        List<Entry> entries = CatalogSource.createEntries();
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (byId.put(entry.getId(), entry) != null) {
                throw new IllegalStateException(
                        "Duplicate catalog id: " + entry.getId()
                );
            }
        }
        ENTRIES = Collections.unmodifiableList(new ArrayList<>(entries));
        ENTRIES_BY_ID = Collections.unmodifiableMap(byId);
    }

    private AppCatalog() {
    }

    static Entry downloadEntry(
            String id,
            String displayName,
            String packageName,
            String downloadUrl,
            String fileName,
            long expectedVersionCode,
            String expectedSignerSha256,
            String expectedFileSha256,
            String... alternativePackageNames
    ) {
        return new Entry(
                Action.DOWNLOAD_APK,
                id,
                displayName,
                packageName,
                downloadUrl,
                fileName,
                expectedVersionCode,
                expectedSignerSha256,
                expectedFileSha256,
                alternativePackageNames
        );
    }

    static Entry webEntry(
            String id,
            String displayName,
            String packageName,
            String sourceUrl,
            String... alternativePackageNames
    ) {
        return new Entry(
                Action.OPEN_WEB,
                id,
                displayName,
                packageName,
                sourceUrl,
                null,
                -1,
                null,
                null,
                alternativePackageNames
        );
    }

    public static List<Entry> getEntries() {
        return ENTRIES;
    }

    public static Entry findById(String id) {
        return id == null ? null : ENTRIES_BY_ID.get(id);
    }

    public static boolean isPublicEdition() {
        return CatalogSource.isPublicEdition();
    }

    public static boolean allowsCustomDownloads() {
        return CatalogSource.allowsCustomDownloads();
    }

    /** Builds a user-supplied HTTPS APK entry in the regular edition. */
    public static Entry createCustom(String displayName, String downloadUrl) {
        if (!allowsCustomDownloads()) {
            throw new IllegalArgumentException(
                    "Custom remote downloads are disabled in the GitHub edition"
            );
        }
        String safeDisplayName = displayName == null || displayName.trim().isEmpty()
                ? "Свой APK"
                : displayName.trim();
        return downloadEntry(
                "custom",
                safeDisplayName,
                null,
                downloadUrl,
                "Custom.apk",
                -1,
                null,
                null
        );
    }

    static void validateDownloadUri(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("APK URL must use HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("APK URL must contain a host");
        }

        String authority = uri.getEncodedAuthority();
        if (uri.getUserInfo() != null
                || (authority != null && authority.contains("@"))) {
            throw new IllegalArgumentException(
                    "APK URL must not contain userinfo"
            );
        }
    }

    static void validateFileName(String fileName) {
        if (fileName == null
                || fileName.contains("..")
                || !SAFE_FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException(
                    "Unsafe APK/XAPK file name: " + fileName
            );
        }
    }
}
