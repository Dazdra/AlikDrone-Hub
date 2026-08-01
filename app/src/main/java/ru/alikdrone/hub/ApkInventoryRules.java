package ru.alikdrone.hub;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure validation/classification rules shared by APK inventory code and tests.
 */
final class ApkInventoryRules {
    private static final Pattern PACKAGE_FILE_NAME = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._() +\\-]{0,159}\\.(apk|xapk)",
            Pattern.CASE_INSENSITIVE
    );

    private ApkInventoryRules() {
    }

    static boolean shouldOffer(Long installedVersion, long archiveVersion) {
        return archiveVersion >= 0
                && (installedVersion == null || archiveVersion > installedVersion);
    }

    static boolean signingCompatibleForUpdate(
            ApkSigningIdentity installed,
            ApkSigningIdentity candidate
    ) {
        return candidate != null && candidate.canUpgradeFrom(installed);
    }

    static boolean sameArtifact(
            String expectedSha256,
            ApkSigningIdentity expectedSigner,
            String actualSha256,
            ApkSigningIdentity actualSigner
    ) {
        return expectedSha256 != null
                && expectedSha256.equals(actualSha256)
                && expectedSigner != null
                && expectedSigner.equals(actualSigner);
    }

    static boolean canReuseMaterializedScan(
            long cachedSize,
            long cachedModifiedAt,
            long sourceSize,
            long sourceModifiedAt,
            long now,
            long maximumAgeMillis
    ) {
        return sourceModifiedAt > 0L
                && cachedSize == sourceSize
                && cachedModifiedAt >= sourceModifiedAt
                && cachedModifiedAt <= now
                && maximumAgeMillis >= 0L
                && now - cachedModifiedAt <= maximumAgeMillis;
    }

    static boolean isSafeApkFileName(String fileName) {
        return fileName != null
                && fileName.toLowerCase(Locale.US).endsWith(".apk")
                && isSafePackageFileName(fileName);
    }

    static boolean isSafePackageFileName(String fileName) {
        return fileName != null
                && PACKAGE_FILE_NAME.matcher(fileName).matches()
                && !fileName.contains("..")
                && (fileName.toLowerCase(Locale.US).endsWith(".apk")
                || fileName.toLowerCase(Locale.US).endsWith(".xapk"));
    }

    static boolean isSafeChild(File root, File candidate) throws IOException {
        String rootPath = root.getCanonicalPath();
        String candidatePath = candidate.getCanonicalPath();
        return candidatePath.startsWith(rootPath + File.separator)
                && candidate.isFile();
    }

    static boolean isSafeChildDirectory(File root, File candidate)
            throws IOException {
        String rootPath = root.getCanonicalPath();
        String candidatePath = candidate.getCanonicalPath();
        return candidatePath.startsWith(rootPath + File.separator)
                && candidate.isDirectory();
    }

    /**
     * Returns true when candidate B should replace candidate A in a package
     * deduplication map.
     */
    static boolean preferCandidate(
            long currentVersion,
            long currentModified,
            String currentIdentity,
            long candidateVersion,
            long candidateModified,
            String candidateIdentity
    ) {
        if (candidateVersion != currentVersion) {
            return candidateVersion > currentVersion;
        }
        if (candidateModified != currentModified) {
            return candidateModified > currentModified;
        }
        String safeCurrent = currentIdentity == null ? "" : currentIdentity;
        String safeCandidate = candidateIdentity == null ? "" : candidateIdentity;
        return safeCandidate.compareTo(safeCurrent) < 0;
    }
}
