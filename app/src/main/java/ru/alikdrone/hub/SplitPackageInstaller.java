package ru.alikdrone.hub;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.util.DisplayMetrics;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Prepares and submits split APK archives through one PackageInstaller session.
 *
 * <p>No silent installation is attempted. PackageInstaller always remains the
 * final authority and displays its normal user confirmation screen.</p>
 */
final class SplitPackageInstaller {
    private static final long MAX_ARCHIVE_BYTES = 1024L * 1024L * 1024L;
    private static final long MAX_APK_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_ARCHIVE_ENTRIES = 128;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String DISCORD_SIGNER_SHA256 =
            "3c39d23cf9367849a5c699395647fe0e5bfea5a1f1f40d8c717ddc70f8bfa113";
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();

    interface Listener {
        void onSubmitted();

        void onFailure(String reason);
    }

    static final class BundleIdentity {
        private final PackageInfo packageInfo;
        private final File baseApk;

        BundleIdentity(PackageInfo packageInfo, File baseApk) {
            this.packageInfo = packageInfo;
            this.baseApk = baseApk;
        }

        PackageInfo getPackageInfo() {
            return packageInfo;
        }

        File getBaseApk() {
            return baseApk;
        }
    }

    private SplitPackageInstaller() {
    }

    static boolean isSplitArchiveName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".xapk");
    }

    /**
     * Extracts and parses only base.apk for inventory rendering. Full split,
     * version and signature verification still happens immediately before a
     * PackageInstaller session is created.
     */
    static BundleIdentity inspectForInventory(
            Context context,
            File archive,
            File cacheDirectory
    ) throws IOException {
        if (context == null || archive == null || !archive.isFile()) {
            throw new IOException("Split archive is missing");
        }
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw new IOException("Cannot create split inspection cache");
        }

        String cacheName = sha256Text(
                archive.getCanonicalPath()
                        + ':' + archive.length()
                        + ':' + archive.lastModified()
        ) + "-base.apk";
        File cachedBase = new File(cacheDirectory, cacheName);
        try (ZipFile zip = new ZipFile(archive)) {
            Map<String, ZipEntry> entries = readSafeEntries(zip);
            SplitApkSelector.Selection selection = selectForDevice(
                    context,
                    entries.keySet()
            );
            if (!selection.getEntries().contains("base.apk")) {
                throw new IOException("Split archive has no base.apk");
            }
            if (!cachedBase.isFile() || cachedBase.length() <= 4L) {
                File temporary = new File(cacheDirectory, cacheName + ".tmp");
                extractEntry(zip, entries.get("base.apk"), temporary);
                replaceFile(temporary, cachedBase);
            }
        }

        PackageInfo info = readArchiveInfo(context, cachedBase);
        if (info == null
                || info.packageName == null
                || !hasSignatures(info)) {
            throw new IOException("base.apk has no valid package signature");
        }
        return new BundleIdentity(info, cachedBase);
    }

    static void installAsync(
            Context context,
            Uri archiveUri,
            String expectedPackage,
            long expectedVersionCode,
            String expectedSignerSha256,
            String expectedArchiveSha256,
            Listener listener
    ) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                install(
                        appContext,
                        archiveUri,
                        expectedPackage,
                        expectedVersionCode,
                        expectedSignerSha256,
                        expectedArchiveSha256
                );
                new Handler(Looper.getMainLooper()).post(listener::onSubmitted);
            } catch (IOException | RuntimeException error) {
                String reason = safeMessage(error);
                new Handler(Looper.getMainLooper()).post(
                        () -> listener.onFailure(reason)
                );
            }
        });
    }

    private static void install(
            Context context,
            Uri archiveUri,
            String expectedPackage,
            long expectedVersionCode,
            String expectedSignerSha256,
            String expectedArchiveSha256
    ) throws IOException {
        if (archiveUri == null
                || expectedPackage == null
                || expectedPackage.trim().isEmpty()) {
            throw new IOException("Split install identity is missing");
        }
        // The split installer is intentionally scoped to the native Discord
        // client. Other catalog APKs continue through the existing APK flow.
        if (!"com.discord".equals(expectedPackage)) {
            throw new IOException("Only the com.discord split bundle is allowed");
        }
        if (expectedArchiveSha256 == null
                || !expectedArchiveSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("Split bundle must match the pinned catalog");
        }

        File root = new File(context.getCacheDir(), "split-install");
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Cannot create split install cache");
        }
        File operation = new File(
                root,
                UUID.randomUUID().toString().replace("-", "")
        );
        if (!operation.mkdirs()) {
            throw new IOException("Cannot create split install operation");
        }
        File archive = new File(operation, "bundle.zip");
        try {
            String archiveDigest = copyArchive(context, archiveUri, archive);
            if (!expectedArchiveSha256.equalsIgnoreCase(archiveDigest)) {
                throw new IOException("Downloaded XAPK SHA-256 does not match catalog");
            }

            List<File> selectedApks;
            try (ZipFile zip = new ZipFile(archive)) {
                Map<String, ZipEntry> entries = readSafeEntries(zip);
                SplitApkSelector.Selection selection = selectForDevice(
                        context,
                        entries.keySet()
                );
                long selectedSize = 0L;
                for (String name : selection.getEntries()) {
                    ZipEntry selected = entries.get(name);
                    if (selected == null) {
                        throw new IOException(
                                "Selected split is missing: " + name
                        );
                    }
                    selectedSize += selected.getSize();
                    if (selectedSize > MAX_ARCHIVE_BYTES) {
                        throw new IOException(
                                "Selected split set exceeds size limit"
                        );
                    }
                }
                selectedApks = new ArrayList<>();
                for (String name : selection.getEntries()) {
                    ZipEntry entry = entries.get(name);
                    if (entry == null) {
                        throw new IOException("Selected split is missing: " + name);
                    }
                    File output = new File(operation, name);
                    extractEntry(zip, entry, output);
                    selectedApks.add(output);
                }
            }
            verifySelectedApks(
                    context,
                    selectedApks,
                    expectedPackage,
                    expectedVersionCode,
                    expectedSignerSha256
            );
            submitSession(context, expectedPackage, selectedApks);
        } finally {
            deleteRecursively(operation);
        }
    }

    private static SplitApkSelector.Selection selectForDevice(
            Context context,
            Set<String> entries
    ) {
        List<String> abis = Arrays.asList(Build.SUPPORTED_ABIS);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return SplitApkSelector.select(
                entries,
                abis,
                metrics == null ? DisplayMetrics.DENSITY_DEFAULT
                        : metrics.densityDpi,
                preferredLocales(context)
        );
    }

    private static List<String> preferredLocales(Context context) {
        List<String> locales = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList configured =
                    context.getResources().getConfiguration().getLocales();
            for (int index = 0; index < configured.size(); index++) {
                locales.add(configured.get(index).toLanguageTag());
            }
        } else {
            Locale configured =
                    context.getResources().getConfiguration().locale;
            if (configured != null) {
                locales.add(configured.toString());
            }
        }
        return locales;
    }

    private static Map<String, ZipEntry> readSafeEntries(ZipFile zip)
            throws IOException {
        Map<String, ZipEntry> result = new HashMap<>();
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        int count = 0;
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            count++;
            if (count > MAX_ARCHIVE_ENTRIES) {
                throw new IOException("Split archive has too many entries");
            }
            if (entry.isDirectory()
                    || !entry.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                continue;
            }
            if (!SplitApkSelector.isSafeFlatApkEntry(entry.getName())) {
                throw new IOException("Unsafe split entry: " + entry.getName());
            }
            if (entry.getSize() <= 4L || entry.getSize() > MAX_APK_BYTES) {
                throw new IOException("Invalid split size: " + entry.getName());
            }
            if (result.put(entry.getName(), entry) != null) {
                throw new IOException("Duplicate split entry: " + entry.getName());
            }
        }
        if (!result.containsKey("base.apk")) {
            throw new IOException("Split archive has no base.apk");
        }
        return result;
    }

    private static String copyArchive(
            Context context,
            Uri source,
            File target
    ) throws IOException {
        MessageDigest digest = sha256();
        long copied = 0L;
        try (
                InputStream raw = context.getContentResolver().openInputStream(source);
                InputStream input = raw == null ? null : new BufferedInputStream(raw);
                OutputStream output = new BufferedOutputStream(
                        new FileOutputStream(target)
                )
        ) {
            if (input == null) {
                throw new IOException("Cannot open downloaded XAPK");
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                if (copied > MAX_ARCHIVE_BYTES) {
                    throw new IOException("XAPK exceeds size limit");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException error) {
            target.delete();
            throw error;
        }
        if (copied <= 4L) {
            target.delete();
            throw new IOException("Downloaded XAPK is empty");
        }
        return toHex(digest.digest());
    }

    private static void extractEntry(
            ZipFile zip,
            ZipEntry entry,
            File output
    ) throws IOException {
        if (entry == null) {
            throw new IOException("Split entry is missing");
        }
        long copied = 0L;
        try (
                InputStream input = new BufferedInputStream(
                        zip.getInputStream(entry)
                );
                OutputStream target = new BufferedOutputStream(
                        new FileOutputStream(output)
                )
        ) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                if (copied > MAX_APK_BYTES) {
                    throw new IOException("Split APK exceeds size limit");
                }
                target.write(buffer, 0, read);
            }
        } catch (IOException error) {
            output.delete();
            throw error;
        }
        if (copied <= 4L
                || (entry.getSize() >= 0 && copied != entry.getSize())) {
            output.delete();
            throw new IOException("Split APK size changed while extracting");
        }
    }

    private static void verifySelectedApks(
            Context context,
            List<File> apks,
            String expectedPackage,
            long expectedVersionCode,
            String expectedSignerSha256
    ) throws IOException {
        if (apks.isEmpty() || !"base.apk".equals(apks.get(0).getName())) {
            throw new IOException("base.apk must be first in split session");
        }
        PackageInfo baseInfo = readArchiveInfo(context, apks.get(0));
        if (baseInfo == null
                || !expectedPackage.equals(baseInfo.packageName)
                || !hasSignatures(baseInfo)) {
            throw new IOException("Discord base APK identity mismatch");
        }
        long baseVersion = versionCode(baseInfo);
        if (expectedVersionCode >= 0L && baseVersion != expectedVersionCode) {
            throw new IOException("Discord version does not match catalog");
        }
        String requiredSigner = expectedSignerSha256 == null
                ? DISCORD_SIGNER_SHA256
                : expectedSignerSha256.toLowerCase(Locale.US);
        if (!signerDigests(baseInfo).contains(requiredSigner)) {
            throw new IOException("Discord signature does not match catalog");
        }
        /*
         * PackageManager.getPackageArchiveInfo() parses a file as a monolithic
         * base APK and returns null for config splits on Android 6–11. The
         * pinned archive digest above authenticates every selected split byte;
         * PackageInstaller then enforces package/version/signature consistency
         * for the complete session.
         */
        Set<String> names = new HashSet<>();
        for (File apk : apks) {
            if (apk == null
                    || !apk.isFile()
                    || apk.length() <= 4L
                    || !apk.getName().toLowerCase(Locale.US).endsWith(".apk")
                    || !names.add(apk.getName())) {
                throw new IOException("Invalid selected split set");
            }
        }
    }

    private static PackageInfo readArchiveInfo(Context context, File apk) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        return context.getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(),
                flags
        );
    }

    private static boolean hasSignatures(PackageInfo info) {
        return !signerDigests(info).isEmpty();
    }

    private static Set<String> signerDigests(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info == null || info.signingInfo == null) {
                return Collections.emptySet();
            }
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info == null ? null : info.signatures;
        }
        if (signatures == null || signatures.length == 0) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (Signature signature : signatures) {
            MessageDigest digest = sha256();
            result.add(toHex(digest.digest(signature.toByteArray())));
        }
        return result;
    }

    private static long versionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private static void submitSession(
            Context context,
            String packageName,
            List<File> apks
    ) throws IOException {
        PackageInstaller installer =
                context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL
                );
        params.setAppPackageName(packageName);
        long totalSize = 0L;
        for (File apk : apks) {
            totalSize += apk.length();
        }
        params.setSize(totalSize);

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = null;
        boolean committed = false;
        try {
            session = installer.openSession(sessionId);
            for (File apk : apks) {
                try (
                        InputStream input = new BufferedInputStream(
                                new FileInputStream(apk)
                        );
                        OutputStream output = session.openWrite(
                                apk.getName(),
                                0L,
                                apk.length()
                        )
                ) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    session.fsync(output);
                }
            }

            Intent result = new Intent(
                    context,
                    SplitInstallResultReceiver.class
            ).setAction(SplitInstallResultReceiver.ACTION_INSTALL_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingResult = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    result,
                    flags
            );
            session.commit(pendingResult.getIntentSender());
            committed = true;
        } finally {
            if (session != null) {
                session.close();
            }
            if (!committed) {
                installer.abandonSession(sessionId);
            }
        }
    }

    private static void replaceFile(File temporary, File target)
            throws IOException {
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("Cannot replace split cache");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Cannot finalize split cache");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String sha256Text(String value) {
        return toHex(sha256().digest(
                value.getBytes(StandardCharsets.UTF_8)
        ));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
