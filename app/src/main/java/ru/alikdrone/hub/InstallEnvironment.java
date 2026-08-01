package ru.alikdrone.hub;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Read-only description of Android's APK installation environment.
 *
 * <p>The Hub never clears device policy and never attempts a silent install.
 * Android's Package Installer remains the final authority.</p>
 */
final class InstallEnvironment {
    static final String PACKAGE_INSTALLER = "com.android.packageinstaller";
    static final String DOCUMENTS_UI = "com.android.documentsui";
    static final String DJI_SIGNER_SHA256 =
            "a4aa1cdd2ea580cbbe67486b5f6f3cfea83f488889995afa70793daa516687da";
    static final long KNOWN_INSTALLER_VERSION = 30L;
    static final long KNOWN_DOCUMENTS_UI_VERSION = 121L;

    enum InstallState {
        READY,
        LEGACY_SYSTEM_CONFIRMATION,
        NEEDS_SOURCE_PERMISSION,
        POLICY_WARNING,
        UNKNOWN
    }

    static final class PackageComponent {
        final boolean installed;
        final String versionName;
        final long versionCode;
        final boolean djiSigned;
        final boolean updatedSystemApp;
        final boolean enabled;

        PackageComponent(
                boolean installed,
                String versionName,
                long versionCode,
                boolean djiSigned,
                boolean updatedSystemApp,
                boolean enabled
        ) {
            this.installed = installed;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.djiSigned = djiSigned;
            this.updatedSystemApp = updatedSystemApp;
            this.enabled = enabled;
        }

        boolean isKnownInstallerHelper() {
            return installed
                    && djiSigned
                    && updatedSystemApp
                    && enabled
                    && versionCode >= KNOWN_INSTALLER_VERSION;
        }

        boolean isKnownDocumentsHelper() {
            return installed
                    && djiSigned
                    && updatedSystemApp
                    && enabled
                    && versionCode >= KNOWN_DOCUMENTS_UI_VERSION;
        }
    }

    static final class Snapshot {
        final int sdk;
        final InstallState state;
        final boolean noInstallApps;
        final boolean noUnknownSources;
        final boolean noUnknownSourcesGlobally;
        final boolean noUsbFileTransfer;
        final boolean noPhysicalMedia;
        final Boolean canRequestPackageInstalls;
        final boolean inspectionFailed;
        final PackageComponent installer;
        final PackageComponent documentsUi;

        Snapshot(
                int sdk,
                InstallState state,
                boolean noInstallApps,
                boolean noUnknownSources,
                boolean noUnknownSourcesGlobally,
                boolean noUsbFileTransfer,
                boolean noPhysicalMedia,
                Boolean canRequestPackageInstalls,
                boolean inspectionFailed,
                PackageComponent installer,
                PackageComponent documentsUi
        ) {
            this.sdk = sdk;
            this.state = state;
            this.noInstallApps = noInstallApps;
            this.noUnknownSources = noUnknownSources;
            this.noUnknownSourcesGlobally = noUnknownSourcesGlobally;
            this.noUsbFileTransfer = noUsbFileTransfer;
            this.noPhysicalMedia = noPhysicalMedia;
            this.canRequestPackageInstalls = canRequestPackageInstalls;
            this.inspectionFailed = inspectionFailed;
            this.installer = installer;
            this.documentsUi = documentsUi;
        }

        boolean hasPolicyWarning() {
            return state == InstallState.POLICY_WARNING;
        }

        boolean needsSourcePermission() {
            return sdk >= Build.VERSION_CODES.O
                    && !Boolean.TRUE.equals(canRequestPackageInstalls);
        }

        boolean hasVerifiedInstallerHelper() {
            return installer != null && installer.isKnownInstallerHelper();
        }

        String restrictionSummary() {
            StringBuilder result = new StringBuilder();
            appendRestriction(result, noInstallApps, "установка приложений");
            appendRestriction(
                    result,
                    noUnknownSources,
                    "неизвестные источники"
            );
            appendRestriction(
                    result,
                    noUnknownSourcesGlobally,
                    "неизвестные источники для всех пользователей"
            );
            return result.length() == 0
                    ? "неизвестное системное ограничение"
                    : result.toString();
        }

        private static void appendRestriction(
                StringBuilder result,
                boolean enabled,
                String label
        ) {
            if (!enabled) {
                return;
            }
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(label);
        }
    }

    private InstallEnvironment() {
    }

    static Snapshot inspect(Context context) {
        int sdk = Build.VERSION.SDK_INT;
        boolean inspectionFailed = false;
        boolean noInstallApps = false;
        boolean noUnknownSources = false;
        boolean noUnknownSourcesGlobally = false;
        boolean noUsbFileTransfer = false;
        boolean noPhysicalMedia = false;

        try {
            UserManager userManager =
                    (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (userManager == null) {
                inspectionFailed = true;
            } else {
                Bundle restrictions = userManager.getUserRestrictions();
                noInstallApps = restrictions.getBoolean(
                        UserManager.DISALLOW_INSTALL_APPS,
                        false
                );
                noUnknownSources = restrictions.getBoolean(
                        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                        false
                );
                if (sdk >= Build.VERSION_CODES.Q) {
                    noUnknownSourcesGlobally = restrictions.getBoolean(
                            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
                            false
                    );
                }
                noUsbFileTransfer = restrictions.getBoolean(
                        UserManager.DISALLOW_USB_FILE_TRANSFER,
                        false
                );
                noPhysicalMedia = restrictions.getBoolean(
                        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
                        false
                );
            }
        } catch (RuntimeException error) {
            inspectionFailed = true;
        }

        Boolean canRequest = null;
        if (sdk >= Build.VERSION_CODES.O) {
            try {
                canRequest = context.getPackageManager()
                        .canRequestPackageInstalls();
            } catch (RuntimeException error) {
                inspectionFailed = true;
            }
        }

        InstallState state = evaluate(
                sdk,
                inspectionFailed,
                noInstallApps,
                noUnknownSources,
                noUnknownSourcesGlobally,
                canRequest
        );
        PackageManager packageManager = context.getPackageManager();
        return new Snapshot(
                sdk,
                state,
                noInstallApps,
                noUnknownSources,
                noUnknownSourcesGlobally,
                noUsbFileTransfer,
                noPhysicalMedia,
                canRequest,
                inspectionFailed,
                inspectPackage(packageManager, PACKAGE_INSTALLER),
                inspectPackage(packageManager, DOCUMENTS_UI)
        );
    }

    static InstallState evaluate(
            int sdk,
            boolean inspectionFailed,
            boolean noInstallApps,
            boolean noUnknownSources,
            boolean noUnknownSourcesGlobally,
            Boolean canRequestPackageInstalls
    ) {
        if (noInstallApps || noUnknownSources || noUnknownSourcesGlobally) {
            return InstallState.POLICY_WARNING;
        }
        if (inspectionFailed) {
            return InstallState.UNKNOWN;
        }
        if (sdk < Build.VERSION_CODES.O) {
            return InstallState.LEGACY_SYSTEM_CONFIRMATION;
        }
        if (!Boolean.TRUE.equals(canRequestPackageInstalls)) {
            return InstallState.NEEDS_SOURCE_PERMISSION;
        }
        return InstallState.READY;
    }

    static Intent unknownSourcesSettingsIntent(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName())
            );
        }
        return new Intent(Settings.ACTION_SECURITY_SETTINGS);
    }

    private static PackageComponent inspectPackage(
            PackageManager packageManager,
            String packageName
    ) {
        try {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo info = packageManager.getPackageInfo(packageName, flags);
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && info.signingInfo != null) {
                signatures = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            } else {
                signatures = info.signatures;
            }
            boolean djiSigned = false;
            if (signatures != null) {
                for (Signature signature : signatures) {
                    if (DJI_SIGNER_SHA256.equals(sha256(signature.toByteArray()))) {
                        djiSigned = true;
                        break;
                    }
                }
            }
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            boolean updatedSystemApp = info.applicationInfo != null
                    && (info.applicationInfo.flags
                    & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            boolean enabled = info.applicationInfo != null
                    && info.applicationInfo.enabled;
            return new PackageComponent(
                    true,
                    info.versionName == null ? "—" : info.versionName,
                    versionCode,
                    djiSigned,
                    updatedSystemApp,
                    enabled
            );
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return new PackageComponent(
                    false,
                    "—",
                    -1L,
                    false,
                    false,
                    false
            );
        }
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "";
        }
    }
}
