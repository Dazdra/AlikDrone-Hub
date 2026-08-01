package ru.alikdrone.hub;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scans only app-owned external folders, directly readable public Download
 * folders, and an explicitly user-selected SAF tree.
 *
 * <p>It never requests broad storage access and never installs automatically.
 * APKs are parsed and signature-presence checked before being offered.</p>
 */
public final class ApkInventoryScanner implements AutoCloseable {
    private static final long MAX_MATERIALIZED_SCAN_AGE_MS =
            5L * 60L * 1_000L;
    private static final String PREFERENCES = "apk_inventory";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final long MIN_APK_SIZE = 8 * 1024L;
    private static final long MAX_APK_SIZE = 512 * 1024 * 1024L;
    private static final int MAX_FILES = 256;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_VISITED_ENTRIES = 2_000;
    private static final int MAX_PARSED_CACHE_ENTRIES = 64;
    private static final long MAX_PARSED_CACHE_AGE_MS = 5L * 60L * 1_000L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public interface Listener {
        void onInventory(ScanResult result);
    }

    public interface PrepareListener {
        void onPrepared(Item item, Uri contentUri);

        void onFailure(Item item, String reason);
    }

    public static final class Item {
        private final Source source;
        private final String identity;
        private final String contentSha256;
        private final ApkSigningIdentity signingIdentity;
        private final String packageName;
        private final String displayName;
        private final String versionName;
        private final long versionCode;
        private final String installedVersionName;
        private final Long installedVersionCode;
        private final String fileName;
        private final long fileSize;
        private final long modifiedAt;
        private final Drawable icon;

        Item(
                Source source,
                String identity,
                String contentSha256,
                ApkSigningIdentity signingIdentity,
                String packageName,
                String displayName,
                String versionName,
                long versionCode,
                String installedVersionName,
                Long installedVersionCode,
                String fileName,
                long fileSize,
                long modifiedAt,
                Drawable icon
        ) {
            this.source = source;
            this.identity = identity;
            this.contentSha256 = contentSha256;
            this.signingIdentity = signingIdentity;
            this.packageName = packageName;
            this.displayName = displayName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.installedVersionName = installedVersionName;
            this.installedVersionCode = installedVersionCode;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.modifiedAt = modifiedAt;
            this.icon = icon;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getVersionName() {
            return versionName;
        }

        public long getVersionCode() {
            return versionCode;
        }

        public String getInstalledVersionName() {
            return installedVersionName;
        }

        public Long getInstalledVersionCode() {
            return installedVersionCode;
        }

        public String getFileName() {
            return fileName;
        }

        public long getFileSize() {
            return fileSize;
        }

        public String getIdentity() {
            return identity;
        }

        public String getContentSha256() {
            return contentSha256;
        }

        public long getModifiedAt() {
            return modifiedAt;
        }

        public Drawable getIcon() {
            return icon;
        }

        public boolean isUpdate() {
            return installedVersionCode != null
                    && versionCode > installedVersionCode;
        }
    }

    public static final class ScanResult {
        private final List<Item> items;
        private final int scannedFiles;
        private final int ignoredFiles;
        private final boolean safFolderConfigured;
        private final boolean safFolderAccessible;

        ScanResult(
                List<Item> items,
                int scannedFiles,
                int ignoredFiles,
                boolean safFolderConfigured,
                boolean safFolderAccessible
        ) {
            this.items = Collections.unmodifiableList(items);
            this.scannedFiles = scannedFiles;
            this.ignoredFiles = ignoredFiles;
            this.safFolderConfigured = safFolderConfigured;
            this.safFolderAccessible = safFolderAccessible;
        }

        public List<Item> getItems() {
            return items;
        }

        public int getScannedFiles() {
            return scannedFiles;
        }

        public int getIgnoredFiles() {
            return ignoredFiles;
        }

        public boolean isSafFolderConfigured() {
            return safFolderConfigured;
        }

        public boolean isSafFolderAccessible() {
            return safFolderAccessible;
        }
    }

    private interface Source {
        InputStream open() throws IOException;

        String identity();

        String fileName();

        long size();

        long modifiedAt();

        File directFile();
    }

    private static final class FileSource implements Source {
        private final File file;

        FileSource(File file) {
            this.file = file;
        }

        @Override
        public InputStream open() throws IOException {
            return new FileInputStream(file);
        }

        @Override
        public String identity() {
            try {
                return "file:" + file.getCanonicalPath();
            } catch (IOException error) {
                return "file:" + file.getAbsolutePath();
            }
        }

        @Override
        public String fileName() {
            return file.getName();
        }

        @Override
        public long size() {
            return file.length();
        }

        @Override
        public long modifiedAt() {
            return file.lastModified();
        }

        @Override
        public File directFile() {
            return file;
        }
    }

    private final class UriSource implements Source {
        private final Uri uri;
        private final String name;
        private final long size;
        private final long modifiedAt;

        UriSource(Uri uri, String name, long size, long modifiedAt) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.modifiedAt = modifiedAt;
        }

        @Override
        public InputStream open() throws IOException {
            InputStream input = resolver.openInputStream(uri);
            if (input == null) {
                throw new IOException("SAF provider returned no stream");
            }
            return input;
        }

        @Override
        public String identity() {
            return "uri:" + uri;
        }

        @Override
        public String fileName() {
            return name;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long modifiedAt() {
            return modifiedAt;
        }

        @Override
        public File directFile() {
            return null;
        }
    }

    private static final class InstalledPackage {
        final String name;
        final long code;
        final ApkSigningIdentity signingIdentity;

        InstalledPackage(
                String name,
                long code,
                ApkSigningIdentity signingIdentity
        ) {
            this.name = name;
            this.code = code;
            this.signingIdentity = signingIdentity;
        }
    }

    private static final class SourceCollection {
        final List<Source> sources;
        final boolean safFolderConfigured;
        final boolean safFolderAccessible;

        SourceCollection(
                List<Source> sources,
                boolean safFolderConfigured,
                boolean safFolderAccessible
        ) {
            this.sources = sources;
            this.safFolderConfigured = safFolderConfigured;
            this.safFolderAccessible = safFolderAccessible;
        }
    }

    private static final class ScanBudget {
        private int remaining = MAX_VISITED_ENTRIES;

        boolean consume() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }

    private static final class MaterializedScan {
        final File file;
        final String copiedSha256;

        MaterializedScan(File file, String copiedSha256) {
            this.file = file;
            this.copiedSha256 = copiedSha256;
        }
    }

    private static final class CachedItem {
        final Item item;
        final long cachedAt;

        CachedItem(Item item, long cachedAt) {
            this.item = item;
            this.cachedAt = cachedAt;
        }
    }

    private final Context appContext;
    private final PackageManager packageManager;
    private final ContentResolver resolver;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final AtomicBoolean inboxesEnsured = new AtomicBoolean(false);
    private final Map<String, CachedItem> parsedItemCache =
            new LinkedHashMap<>(16, 0.75f, true);
    private long packageStateGeneration;
    private volatile boolean closed;

    public ApkInventoryScanner(Context context) {
        appContext = context.getApplicationContext();
        packageManager = appContext.getPackageManager();
        resolver = appContext.getContentResolver();
        preferences = appContext.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    /** Invalidates inventory metadata after an install/remove/update event. */
    public synchronized void invalidatePackageState() {
        packageStateGeneration++;
        parsedItemCache.clear();
    }

    public void setSafTreeUri(Uri uri) {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("A content tree URI is required");
        }
        if (!preferences.edit().putString(KEY_TREE_URI, uri.toString()).commit()) {
            throw new IllegalStateException("Failed to persist the SAF tree URI");
        }
        invalidatePackageState();
    }

    /**
     * Takes and persists a new read grant before releasing the previous one.
     *
     * <p>This method performs synchronous preference I/O and must be called
     * away from the main thread. If persistence fails, the newly acquired
     * grant is released and the previous configured grant is left intact.</p>
     */
    public void replaceSafTreeUri(Uri uri) {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("A content tree URI is required");
        }

        Uri previous = getSafTreeUri();
        resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        );

        boolean saved = preferences.edit()
                .putString(KEY_TREE_URI, uri.toString())
                .commit();
        if (!saved) {
            if (previous == null || !previous.equals(uri)) {
                releasePersistedReadGrant(uri);
            }
            throw new IllegalStateException("Failed to persist the SAF tree URI");
        }

        if (previous != null && !previous.equals(uri)) {
            releasePersistedReadGrant(previous);
        }
        invalidatePackageState();
    }

    public Uri getSafTreeUri() {
        String value = preferences.getString(KEY_TREE_URI, null);
        return value == null ? null : Uri.parse(value);
    }

    public void clearSafTreeUri() {
        if (!preferences.edit().remove(KEY_TREE_URI).commit()) {
            throw new IllegalStateException("Failed to clear the SAF tree URI");
        }
        invalidatePackageState();
    }

    public boolean hasPersistedSafTreeAccess() {
        Uri configured = getSafTreeUri();
        return hasPersistedSafTreeAccess(configured);
    }

    private boolean hasPersistedSafTreeAccess(Uri configured) {
        if (configured == null) {
            return false;
        }
        try {
            for (UriPermission permission : resolver.getPersistedUriPermissions()) {
                if (permission.isReadPermission()
                        && configured.equals(permission.getUri())) {
                    Uri document = DocumentsContract.buildDocumentUriUsingTree(
                            configured,
                            DocumentsContract.getTreeDocumentId(configured)
                    );
                    try (Cursor cursor = resolver.query(
                            document,
                            new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                            null,
                            null,
                            null
                    )) {
                        return cursor != null && cursor.moveToFirst();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    public void releaseSafTreeUri() {
        Uri configured = getSafTreeUri();
        if (configured == null) {
            clearSafTreeUri();
            return;
        }
        boolean held = false;
        try {
            for (UriPermission permission
                    : resolver.getPersistedUriPermissions()) {
                if (permission.isReadPermission()
                        && configured.equals(permission.getUri())) {
                    held = true;
                    break;
                }
            }
            if (held) {
                resolver.releasePersistableUriPermission(
                        configured,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            }
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "Failed to release the persisted SAF grant",
                    error
            );
        }
        // Clear the durable pointer only after the grant was released (or
        // confirmed absent). This avoids orphaning a live grant when Android
        // rejects the release operation.
        clearSafTreeUri();
    }

    private void releasePersistedReadGrant(Uri uri) {
        try {
            resolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (RuntimeException ignored) {
            // The provider or Android may already have revoked the grant.
        }
    }

    /**
     * Starts a scan when the previous scan has finished.
     *
     * @return {@code true} when this call started a scan, otherwise {@code false}
     */
    public boolean scanAsync(Listener listener) {
        if (closed || listener == null || !scanning.compareAndSet(false, true)) {
            return false;
        }
        executor.execute(() -> {
            ScanResult result;
            try {
                result = scan();
            } catch (RuntimeException error) {
                result = new ScanResult(
                        Collections.emptyList(),
                        0,
                        1,
                        getSafTreeUri() != null,
                        false
                );
            } finally {
                scanning.set(false);
            }
            ScanResult delivered = result;
            mainHandler.post(() -> {
                if (!closed) {
                    listener.onInventory(delivered);
                }
            });
        });
        return true;
    }

    public void prepareInstallAsync(Item item, PrepareListener listener) {
        if (closed || item == null || listener == null) {
            return;
        }
        executor.execute(() -> {
            try {
                Uri uri = prepareInstall(item);
                mainHandler.post(() -> {
                    if (!closed) {
                        listener.onPrepared(item, uri);
                    }
                });
            } catch (IOException | RuntimeException error) {
                String reason = safeMessage(error);
                mainHandler.post(() -> {
                    if (!closed) {
                        listener.onFailure(item, reason);
                    }
                });
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        synchronized (this) {
            packageStateGeneration++;
            parsedItemCache.clear();
        }
    }

    private ScanResult scan() {
        if (inboxesEnsured.compareAndSet(false, true)) {
            ensureAppInboxes();
        }
        cleanupMaterializedScanCache(
                new File(appContext.getCacheDir(), "inventory-scan"),
                null
        );
        SourceCollection collected = collectSources();
        List<Source> sources = collected.sources;
        Map<String, Item> bestByPackage = new HashMap<>();
        int ignored = 0;

        for (Source source : sources) {
            if (isCancelled()) {
                break;
            }
            try {
                Item item = parseSourceCached(source);
                if (item == null) {
                    ignored++;
                    continue;
                }
                Item current = bestByPackage.get(item.packageName);
                if (current == null || ApkInventoryRules.preferCandidate(
                        current.versionCode,
                        current.modifiedAt,
                        current.identity,
                        item.versionCode,
                        item.modifiedAt,
                        item.identity
                )) {
                    bestByPackage.put(item.packageName, item);
                }
            } catch (IOException | RuntimeException error) {
                ignored++;
            }
        }

        List<Item> items = new ArrayList<>(bestByPackage.values());
        Collections.sort(items, (left, right) ->
                left.displayName.compareToIgnoreCase(right.displayName)
        );
        return new ScanResult(
                items,
                sources.size(),
                ignored,
                collected.safFolderConfigured,
                collected.safFolderAccessible
        );
    }

    private SourceCollection collectSources() {
        List<Source> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ScanBudget budget = new ScanBudget();
        Uri treeUri = getSafTreeUri();
        boolean safConfigured = treeUri != null;
        boolean safAccessible = treeUri != null
                && hasPersistedSafTreeAccess(treeUri);

        File[] downloadRoots = appContext.getExternalFilesDirs(
                Environment.DIRECTORY_DOWNLOADS
        );
        if (downloadRoots != null) {
            for (File root : downloadRoots) {
                if (root == null) {
                    continue;
                }
                collectFileTree(root, root, 0, result, seen, budget);
            }
        }

        File[] externalRoots = appContext.getExternalFilesDirs(null);
        if (externalRoots != null) {
            for (File externalRoot : externalRoots) {
                if (externalRoot == null) {
                    continue;
                }
                File inbox = new File(externalRoot, "AlikDroneInbox");
                collectFileTree(inbox, inbox, 0, result, seen, budget);
                File handoff = new File(externalRoot, "AlikDroneHandoff");
                collectFileTree(handoff, handoff, 0, result, seen, budget);
            }
        }

        // A selected SAF tree is the authoritative public storage source.
        // Avoid traversing the same (often very large) Download folder twice.
        if (!safAccessible) try {
            File publicDownloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
            );
            if (publicDownloads != null
                    && publicDownloads.isDirectory()
                    && publicDownloads.canRead()) {
                collectFileTree(
                        publicDownloads,
                        publicDownloads,
                        0,
                        result,
                        seen,
                        budget
                );
            }
        } catch (RuntimeException ignored) {
            // Scoped storage may make the raw public folder unreadable.
        }

        if (safAccessible && result.size() < MAX_FILES && !isCancelled()) {
            try {
                String rootId = DocumentsContract.getTreeDocumentId(treeUri);
                collectDocumentTree(
                        treeUri,
                        rootId,
                        0,
                        result,
                        seen,
                        budget
                );
            } catch (RuntimeException error) {
                // SAF roots can be transient (for example, a removable card
                // or provider can disappear briefly). Keep both the user's
                // configured URI and its persisted grant so a later scan can
                // recover without asking the user to select the folder again.
            }
        }
        return new SourceCollection(result, safConfigured, safAccessible);
    }

    private void collectFileTree(
            File allowedRoot,
            File directory,
            int depth,
            List<Source> result,
            Set<String> seen,
            ScanBudget budget
    ) {
        if (depth > MAX_DEPTH
                || result.size() >= MAX_FILES
                || isCancelled()) {
            return;
        }
        String[] childNames;
        try {
            // list() avoids eagerly allocating a File object for every photo
            // or video in a large Download folder. The visit budget below
            // creates File instances only for entries we actually inspect.
            childNames = directory.list();
        } catch (SecurityException error) {
            return;
        }
        if (childNames == null) {
            return;
        }
        for (String childName : childNames) {
            if (result.size() >= MAX_FILES
                    || isCancelled()
                    || !budget.consume()) {
                return;
            }
            File child = new File(directory, childName);
            try {
                if (child.isDirectory()) {
                    if (ApkInventoryRules.isSafeChildDirectory(
                            allowedRoot,
                            child
                    )) {
                        collectFileTree(
                                allowedRoot,
                                child,
                                depth + 1,
                                result,
                                seen,
                                budget
                        );
                    }
                    continue;
                }
                if (!ApkInventoryRules.isSafePackageFileName(child.getName())
                        || !ApkInventoryRules.isSafeChild(allowedRoot, child)
                        || !validSize(child.length())) {
                    continue;
                }
                FileSource source = new FileSource(child);
                if (seen.add(source.identity())) {
                    result.add(source);
                }
            } catch (IOException | SecurityException ignored) {
                // Skip an unsafe/unreadable path without affecting other APKs.
            }
        }
    }

    private void collectDocumentTree(
            Uri treeUri,
            String parentId,
            int depth,
            List<Source> result,
            Set<String> seen,
            ScanBudget budget
    ) {
        if (depth > MAX_DEPTH
                || result.size() >= MAX_FILES
                || isCancelled()) {
            return;
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                parentId
        );
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = resolver.query(
                childrenUri,
                projection,
                null,
                null,
                null
        )) {
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext()
                    && result.size() < MAX_FILES
                    && !isCancelled()
                    && budget.consume()) {
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                long modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectDocumentTree(
                            treeUri,
                            documentId,
                            depth + 1,
                            result,
                            seen,
                            budget
                    );
                    continue;
                }
                if (!ApkInventoryRules.isSafePackageFileName(name)
                        || !validDeclaredSize(size)) {
                    continue;
                }
                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        documentId
                );
                UriSource source = new UriSource(
                        documentUri,
                        name,
                        size,
                        modified
                );
                if (seen.add(source.identity())) {
                    result.add(source);
                }
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Persisted permission may have been revoked.
        }
    }

    private Item parseSourceCached(Source source) throws IOException {
        String cacheKey = source.identity()
                + '\n' + source.fileName()
                + '\n' + source.size()
                + '\n' + source.modifiedAt();
        long now = System.currentTimeMillis();
        long generation;
        synchronized (this) {
            CachedItem cached = parsedItemCache.get(cacheKey);
            if (cached != null
                    && now - cached.cachedAt <= MAX_PARSED_CACHE_AGE_MS) {
                return cached.item;
            }
            if (cached != null) {
                parsedItemCache.remove(cacheKey);
            }
            generation = packageStateGeneration;
        }

        Item parsed = parseSource(source);
        if (isCancelled()) {
            return parsed;
        }
        synchronized (this) {
            if (generation != packageStateGeneration || closed) {
                return parsed;
            }
            // Null is a useful cached result too: unchanged incompatible,
            // older or already-installed APKs must not be hashed every poll.
            parsedItemCache.put(
                    cacheKey,
                    new CachedItem(parsed, System.currentTimeMillis())
            );
            while (parsedItemCache.size() > MAX_PARSED_CACHE_ENTRIES) {
                Iterator<String> oldest = parsedItemCache.keySet().iterator();
                if (!oldest.hasNext()) {
                    break;
                }
                oldest.next();
                oldest.remove();
            }
        }
        return parsed;
    }

    private Item parseSource(Source source) throws IOException {
        long sourceSize = source.size();
        if (!validDeclaredSize(sourceSize)
                || !ApkInventoryRules.isSafePackageFileName(source.fileName())) {
            return null;
        }
        File parseFile = source.directFile();
        String contentSha256 = null;
        if (parseFile == null) {
            MaterializedScan materialized = materializeForScan(source);
            parseFile = materialized.file;
            contentSha256 = materialized.copiedSha256;
        }
        if (contentSha256 == null) {
            contentSha256 = sha256File(parseFile, sourceSize);
        }

        PackageInfo archive;
        File identityApk = parseFile;
        if (SplitPackageInstaller.isSplitArchiveName(source.fileName())) {
            SplitPackageInstaller.BundleIdentity identity =
                    SplitPackageInstaller.inspectForInventory(
                            appContext,
                            parseFile,
                            new File(appContext.getCacheDir(), "split-scan")
                    );
            archive = identity.getPackageInfo();
            identityApk = identity.getBaseApk();
            if (!"com.discord".equals(archive.packageName)) {
                return null;
            }
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            archive = packageManager.getPackageArchiveInfo(
                    parseFile.getAbsolutePath(),
                    flags
            );
        }
        ApkSigningIdentity archiveSigningIdentity =
                ApkSigningIdentity.fromPackageInfo(archive);
        if (archive == null
                || archive.packageName == null
                || archive.packageName.trim().isEmpty()
                || archiveSigningIdentity == null) {
            return null;
        }
        long archiveVersion = versionCode(archive);
        InstalledPackage installed = installedPackage(archive.packageName);
        Long installedCode = installed == null ? null : installed.code;
        if (!ApkInventoryRules.shouldOffer(installedCode, archiveVersion)
                || (installed != null
                && !ApkInventoryRules.signingCompatibleForUpdate(
                        installed.signingIdentity,
                        archiveSigningIdentity
                ))) {
            return null;
        }

        String label = archive.packageName;
        Drawable icon = null;
        if (archive.applicationInfo != null) {
            archive.applicationInfo.sourceDir = identityApk.getAbsolutePath();
            archive.applicationInfo.publicSourceDir = identityApk.getAbsolutePath();
            try {
                CharSequence loadedLabel = archive.applicationInfo.loadLabel(
                        packageManager
                );
                if (loadedLabel != null && loadedLabel.length() > 0) {
                    label = loadedLabel.toString();
                }
                icon = archive.applicationInfo.loadIcon(packageManager);
            } catch (RuntimeException ignored) {
                // Package name and generic icon remain usable.
            }
        }

        return new Item(
                source,
                source.identity(),
                contentSha256,
                archiveSigningIdentity,
                archive.packageName,
                label,
                archive.versionName == null ? String.valueOf(archiveVersion)
                        : archive.versionName,
                archiveVersion,
                installed == null ? null : installed.name,
                installedCode,
                source.fileName(),
                sourceSize,
                source.modifiedAt(),
                icon
        );
    }

    private Uri prepareInstall(Item item) throws IOException {
        if (!validDeclaredSize(item.source.size())
                || item.source.size() != item.fileSize
                || !item.source.identity().equals(item.identity)) {
            throw new IOException("APK changed after inventory scan");
        }

        File directory = new File(
                appContext.getCacheDir(),
                InventoryApkProvider.CACHE_DIRECTORY
        );
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create install cache");
        }
        cleanupOldFiles(directory);

        String token = UUID.randomUUID().toString().replace("-", "");
        File temporary = new File(directory, token + ".tmp");
        String preparedExtension =
                SplitPackageInstaller.isSplitArchiveName(item.fileName)
                        ? ".xapk"
                        : ".apk";
        File prepared = new File(directory, token + preparedExtension);
        String copiedSha256 = copyLimited(item.source, temporary);

        try {
            if (!item.contentSha256.equals(copiedSha256)) {
                throw new IOException(
                        "APK content changed after inventory scan"
                );
            }
            PackageInfo verified;
            if (SplitPackageInstaller.isSplitArchiveName(item.fileName)) {
                SplitPackageInstaller.BundleIdentity identity =
                        SplitPackageInstaller.inspectForInventory(
                                appContext,
                                temporary,
                                new File(appContext.getCacheDir(), "split-scan")
                        );
                verified = identity.getPackageInfo();
            } else {
                int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? PackageManager.GET_SIGNING_CERTIFICATES
                        : PackageManager.GET_SIGNATURES;
                verified = packageManager.getPackageArchiveInfo(
                        temporary.getAbsolutePath(),
                        flags
                );
            }
            ApkSigningIdentity verifiedSigningIdentity =
                    ApkSigningIdentity.fromPackageInfo(verified);
            if (verified == null
                    || !item.packageName.equals(verified.packageName)
                    || item.versionCode != versionCode(verified)
                    || !ApkInventoryRules.sameArtifact(
                            item.contentSha256,
                            item.signingIdentity,
                            copiedSha256,
                            verifiedSigningIdentity
                    )) {
                throw new IOException("APK identity changed after scan");
            }

            InstalledPackage installed = installedPackage(
                    verified.packageName
            );
            if (installed != null
                    && (!ApkInventoryRules.shouldOffer(
                            installed.code,
                            versionCode(verified)
                    )
                    || !ApkInventoryRules.signingCompatibleForUpdate(
                            installed.signingIdentity,
                            verifiedSigningIdentity
                    ))) {
                throw new IOException(
                        "APK is not a compatible update for installed app"
                );
            }
            if (!temporary.renameTo(prepared)) {
                throw new IOException("Cannot finalize prepared APK");
            }
            return InventoryApkProvider.uriForName(prepared.getName());
        } finally {
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private MaterializedScan materializeForScan(Source source)
            throws IOException {
        File directory = new File(appContext.getCacheDir(), "inventory-scan");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create scan cache");
        }
        String extension = SplitPackageInstaller.isSplitArchiveName(
                source.fileName()
        ) ? ".xapk" : ".apk";
        String name = digestName(source.identity()) + extension;
        File target = new File(directory, name);
        if (target.isFile()
                && ApkInventoryRules.canReuseMaterializedScan(
                        target.length(),
                        target.lastModified(),
                        source.size(),
                        source.modifiedAt(),
                        System.currentTimeMillis(),
                        MAX_MATERIALIZED_SCAN_AGE_MS
                )) {
            return new MaterializedScan(target, null);
        }
        File temporary = new File(directory, name + ".tmp");
        String copiedSha256 = copyLimited(source, temporary);
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("Cannot replace scan cache");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Cannot finalize scan cache");
        }
        return new MaterializedScan(target, copiedSha256);
    }

    private String copyLimited(Source source, File target) throws IOException {
        long copied = 0;
        MessageDigest digest = sha256();
        try (
                InputStream rawInput = source.open();
                InputStream input = new BufferedInputStream(rawInput);
                OutputStream output = new BufferedOutputStream(
                        new FileOutputStream(target)
                )
        ) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (isCancelled()) {
                    throw new IOException("APK read cancelled");
                }
                copied += read;
                if (copied > MAX_APK_SIZE) {
                    throw new IOException("APK exceeds size limit");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException error) {
            target.delete();
            throw error;
        }
        if (!validSize(copied)
                || (source.size() >= 0 && copied != source.size())) {
            target.delete();
            throw new IOException("APK size changed while reading");
        }
        return toHex(digest.digest());
    }

    private InstalledPackage installedPackage(String packageName) {
        try {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo installed = packageManager.getPackageInfo(
                    packageName,
                    flags
            );
            return new InstalledPackage(
                    installed.versionName == null ? String.valueOf(versionCode(installed))
                            : installed.versionName,
                    versionCode(installed),
                    ApkSigningIdentity.fromPackageInfo(installed)
            );
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;
    }

    private void ensureAppInboxes() {
        File[] externalRoots = appContext.getExternalFilesDirs(null);
        if (externalRoots != null) {
            for (File root : externalRoots) {
                if (root != null) {
                    new File(root, "AlikDroneInbox").mkdirs();
                }
            }
        }

        File[] roots = appContext.getExternalFilesDirs(
                Environment.DIRECTORY_DOWNLOADS
        );
        if (roots == null) {
            return;
        }
        for (File root : roots) {
            if (root != null) {
                new File(root, "Inbox").mkdirs();
            }
        }
    }

    private static void cleanupOldFiles(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    private static void cleanupMaterializedScanCache(
            File directory,
            File currentTarget
    ) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis()
                - MAX_MATERIALIZED_SCAN_AGE_MS;
        for (File file : files) {
            if (!file.isFile() || file.equals(currentTarget)) {
                continue;
            }
            if (file.getName().endsWith(".tmp")
                    || file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    private boolean isCancelled() {
        return closed || Thread.currentThread().isInterrupted();
    }

    private String sha256File(File file, long expectedSize)
            throws IOException {
        long readTotal = 0L;
        MessageDigest digest = sha256();
        try (InputStream input = new BufferedInputStream(
                new FileInputStream(file)
        )) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (isCancelled()) {
                    throw new IOException("APK hash cancelled");
                }
                readTotal += read;
                if (readTotal > MAX_APK_SIZE) {
                    throw new IOException("APK exceeds size limit");
                }
                digest.update(buffer, 0, read);
            }
        }
        if (!validSize(readTotal)
                || (expectedSize >= 0 && readTotal != expectedSize)) {
            throw new IOException("APK size changed while hashing");
        }
        return toHex(digest.digest());
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable", error);
        }
    }

    private static String toHex(byte[] value) {
        char[] result = new char[value.length * 2];
        int offset = 0;
        for (byte part : value) {
            int unsigned = part & 0xff;
            result[offset++] = HEX[unsigned >>> 4];
            result[offset++] = HEX[unsigned & 0x0f];
        }
        return new String(result);
    }

    private static String digestName(String value) throws IOException {
        byte[] digest = sha256().digest(value.getBytes(StandardCharsets.UTF_8));
        char[] result = new char[32];
        for (int index = 0; index < 16; index++) {
            int unsigned = digest[index] & 0xff;
            result[index * 2] = HEX[unsigned >>> 4];
            result[index * 2 + 1] = HEX[unsigned & 0x0f];
        }
        return new String(result);
    }

    private static boolean validSize(long size) {
        return size >= MIN_APK_SIZE && size <= MAX_APK_SIZE;
    }

    private static boolean validDeclaredSize(long size) {
        return size < 0 || validSize(size);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
