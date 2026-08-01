package ru.alikdrone.hub;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads catalog APKs through Android's DownloadManager.
 *
 * <p>The controller never starts Package Installer and never performs a silent
 * install. A completed DownloadManager content URI is returned to the caller,
 * which must explicitly ask the user before starting any installation flow.</p>
 */
public final class ApkDownloadController implements AutoCloseable {
    private static final String TAG = "ApkDownloadController";
    private static final String PREFERENCES_NAME = "alikdrone_apk_download";
    private static final String KEY_DOWNLOAD_ID = "pending_download_id";
    private static final String KEY_APP_ID = "pending_app_id";
    private static final String KEY_FILE_NAME = "pending_file_name";
    private static final String KEY_DOWNLOAD_URL = "pending_download_url";
    private static final String KEY_DISPLAY_NAME = "pending_display_name";
    private static final long NO_DOWNLOAD_ID = -1L;
    private static final long MIN_APK_SIZE_BYTES = 4L;
    private static final long MAX_APK_SIZE_BYTES = 1024L * 1024L * 1024L;

    public enum State {
        IDLE,
        ENQUEUED,
        PENDING,
        RUNNING,
        PAUSED,
        VALIDATING,
        READY,
        FAILED,
        CANCELLED
    }

    public static final class Status {
        private final State state;
        private final AppCatalog.Entry entry;
        private final long downloadId;
        private final long downloadedBytes;
        private final long totalBytes;
        private final int reason;
        private final String message;

        private Status(
                State state,
                AppCatalog.Entry entry,
                long downloadId,
                long downloadedBytes,
                long totalBytes,
                int reason,
                String message
        ) {
            this.state = state;
            this.entry = entry;
            this.downloadId = downloadId;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.reason = reason;
            this.message = message;
        }

        public State getState() {
            return state;
        }

        public AppCatalog.Entry getEntry() {
            return entry;
        }

        public long getDownloadId() {
            return downloadId;
        }

        public long getDownloadedBytes() {
            return downloadedBytes;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public int getReason() {
            return reason;
        }

        public String getMessage() {
            return message;
        }
    }

    public interface Listener {
        void onStatus(Status status);

        /**
         * Called after DownloadManager success, ZIP validation, and the
         * catalog SHA-256 check when the entry is pinned.
         */
        void onApkReady(AppCatalog.Entry entry, Uri contentUri);
    }

    private static final class PendingDownload {
        final long id;
        final AppCatalog.Entry entry;
        final String fileName;

        PendingDownload(long id, AppCatalog.Entry entry, String fileName) {
            this.id = id;
            this.entry = entry;
            this.fileName = fileName;
        }
    }

    private final Context appContext;
    private final DownloadManager downloadManager;
    private final SharedPreferences preferences;
    private final Listener listener;
    private final ExecutorService validationExecutor =
            Executors.newSingleThreadExecutor();
    private final Set<Long> validatingIds = Collections.synchronizedSet(
            new HashSet<>()
    );
    private boolean receiverRegistered;
    private volatile boolean closed;

    private final BroadcastReceiver completionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }

            long completedId = intent.getLongExtra(
                    DownloadManager.EXTRA_DOWNLOAD_ID,
                    NO_DOWNLOAD_ID
            );
            PendingDownload pending = readPending();

            // Ignore broadcasts for downloads created by other applications and
            // stale/spoofed broadcasts for IDs not currently owned by this flow.
            if (pending == null || completedId != pending.id) {
                return;
            }
            inspectPending(pending);
        }
    };

    public ApkDownloadController(Context context, Listener listener) {
        if (context == null) {
            throw new IllegalArgumentException("Context is required");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Listener is required");
        }

        appContext = context.getApplicationContext();
        Object service = appContext.getSystemService(Context.DOWNLOAD_SERVICE);
        if (!(service instanceof DownloadManager)) {
            throw new IllegalStateException("DownloadManager is unavailable");
        }

        downloadManager = (DownloadManager) service;
        preferences = appContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
        this.listener = listener;
    }

    /**
     * Registers the completion receiver and immediately resumes inspection of
     * a download persisted before an Activity/process restart.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public synchronized void start() {
        if (closed) {
            return;
        }
        if (!receiverRegistered) {
            appContext.registerReceiver(
                    completionReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            );
            receiverRegistered = true;
        }
        refreshPending();
    }

    /**
     * Enqueues one catalog entry. Concurrent catalog downloads are rejected so
     * a caller can't accidentally lose the persisted ID of an earlier APK.
     */
    public synchronized long download(AppCatalog.Entry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Catalog entry is required");
        }
        if (!entry.isDownloadable()) {
            throw new IllegalArgumentException(
                    "Catalog entry is not a direct APK download"
            );
        }
        if (closed) {
            throw new IllegalStateException("Download controller is closed");
        }

        start();
        if (readPending() != null) {
            throw new IllegalStateException("Another APK download is still pending");
        }

        AppCatalog.validateDownloadUri(entry.getDownloadUri());
        AppCatalog.validateFileName(entry.getFileName());

        String destinationName = createDestinationName(entry);
        DownloadManager.Request request = new DownloadManager.Request(entry.getDownloadUri())
                .setTitle(entry.getDisplayName())
                .setDescription("Загрузка APK для ручной установки")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalFilesDir(
                        appContext,
                        Environment.DIRECTORY_DOWNLOADS,
                        destinationName
                );

        long downloadId = downloadManager.enqueue(request);
        if (downloadId == NO_DOWNLOAD_ID) {
            throw new IllegalStateException("DownloadManager rejected the request");
        }
        boolean saved = preferences.edit()
                .putLong(KEY_DOWNLOAD_ID, downloadId)
                .putString(KEY_APP_ID, entry.getId())
                .putString(KEY_FILE_NAME, destinationName)
                .putString(KEY_DOWNLOAD_URL, entry.getDownloadUri().toString())
                .putString(KEY_DISPLAY_NAME, entry.getDisplayName())
                .commit();
        if (!saved) {
            downloadManager.remove(downloadId);
            throw new IllegalStateException("Failed to persist pending download");
        }

        notifyStatus(new Status(
                State.ENQUEUED,
                entry,
                downloadId,
                0,
                -1,
                0,
                "Загрузка поставлена в очередь"
        ));
        return downloadId;
    }

    /**
     * Re-queries only the persisted DownloadManager ID. Call periodically when
     * progress updates are required; completion itself arrives by broadcast.
     */
    public synchronized void refreshPending() {
        PendingDownload pending = readPending();
        if (pending == null) {
            return;
        }
        inspectPending(pending);
    }

    public synchronized boolean hasPendingDownload() {
        return readPending() != null;
    }

    public synchronized AppCatalog.Entry getPendingEntry() {
        PendingDownload pending = readPending();
        return pending == null ? null : pending.entry;
    }

    public synchronized void cancelPending() {
        PendingDownload pending = readPending();
        if (pending == null) {
            return;
        }

        downloadManager.remove(pending.id);
        clearPending();
        notifyStatus(new Status(
                State.CANCELLED,
                pending.entry,
                pending.id,
                0,
                -1,
                0,
                "Загрузка отменена"
        ));
    }

    @Override
    public synchronized void close() {
        closed = true;
        validationExecutor.shutdownNow();
        if (!receiverRegistered) {
            return;
        }
        try {
            appContext.unregisterReceiver(completionReceiver);
        } catch (IllegalArgumentException ignored) {
            // The framework may already have detached it during teardown.
        }
        receiverRegistered = false;
    }

    private PendingDownload readPending() {
        long id = preferences.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD_ID);
        if (id == NO_DOWNLOAD_ID) {
            return null;
        }

        String appId = preferences.getString(KEY_APP_ID, null);
        String fileName = preferences.getString(KEY_FILE_NAME, null);
        AppCatalog.Entry entry = AppCatalog.findById(appId);

        if (entry == null && "custom".equals(appId)) {
            String downloadUrl = preferences.getString(KEY_DOWNLOAD_URL, null);
            String displayName = preferences.getString(KEY_DISPLAY_NAME, null);
            try {
                entry = AppCatalog.createCustom(displayName, downloadUrl);
            } catch (IllegalArgumentException error) {
                clearPending();
                return null;
            }
        }

        if (entry == null || !entry.isDownloadable() || fileName == null) {
            downloadManager.remove(id);
            clearPending();
            return null;
        }

        String persistedUrl = preferences.getString(KEY_DOWNLOAD_URL, null);
        if (persistedUrl == null
                || !entry.getDownloadUri().toString().equals(persistedUrl)) {
            // Do not resume an older server-backed job after switching to the
            // GitHub catalog (or after an official endpoint changes).
            downloadManager.remove(id);
            clearPending();
            return null;
        }

        try {
            AppCatalog.validateFileName(fileName);
        } catch (IllegalArgumentException error) {
            downloadManager.remove(id);
            clearPending();
            return null;
        }
        return new PendingDownload(id, entry, fileName);
    }

    private void inspectPending(PendingDownload pending) {
        DownloadManager.Query query = new DownloadManager.Query()
                .setFilterById(pending.id);

        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                failAndClear(pending, 0, "DownloadManager не нашёл загрузку");
                return;
            }

            long queriedId = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            );
            if (queriedId != pending.id) {
                failAndClear(pending, 0, "DownloadManager вернул чужой ID");
                return;
            }

            String requestUri = cursor.getString(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            );
            if (!pending.entry.getDownloadUri().toString().equals(requestUri)) {
                failAndClear(pending, 0, "Источник загрузки не совпадает с каталогом");
                return;
            }

            int status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            );
            int reason = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            );
            long downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                    )
            );
            long total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                    )
            );

            switch (status) {
                case DownloadManager.STATUS_PENDING:
                    notifyStatus(status(
                            State.PENDING,
                            pending,
                            downloaded,
                            total,
                            reason,
                            "Ожидает загрузки"
                    ));
                    break;
                case DownloadManager.STATUS_RUNNING:
                    notifyStatus(status(
                            State.RUNNING,
                            pending,
                            downloaded,
                            total,
                            reason,
                            "APK загружается"
                    ));
                    break;
                case DownloadManager.STATUS_PAUSED:
                    notifyStatus(status(
                            State.PAUSED,
                            pending,
                            downloaded,
                            total,
                            reason,
                            "Загрузка приостановлена"
                    ));
                    break;
                case DownloadManager.STATUS_SUCCESSFUL:
                    beginCompletedValidation(
                            pending,
                            downloaded,
                            total
                    );
                    break;
                case DownloadManager.STATUS_FAILED:
                    failAndClear(
                            pending,
                            reason,
                            "Загрузка APK завершилась ошибкой: " + reason
                    );
                    break;
                default:
                    failAndClear(
                            pending,
                            reason,
                            "Неизвестный статус DownloadManager: " + status
                    );
                    break;
            }
        } catch (RuntimeException error) {
            failAndClear(
                    pending,
                    0,
                    "Не удалось обработать загрузку: " + safeMessage(error)
            );
        }
    }

    private void beginCompletedValidation(
            PendingDownload pending,
            long downloaded,
            long total
    ) {
        if (!validatingIds.add(pending.id)) {
            return;
        }
        notifyStatus(status(
                State.VALIDATING,
                pending,
                downloaded,
                total,
                0,
                "Подготавливаю загруженный APK"
        ));

        validationExecutor.execute(() -> {
            try {
                Uri contentUri = validateCompletedDownload(
                        pending,
                        downloaded,
                        total
                );
                synchronized (this) {
                    if (closed || !isCurrentPending(pending.id)) {
                        return;
                    }
                    clearPending();
                }
                notifyStatus(status(
                        State.READY,
                        pending,
                        downloaded,
                        total,
                        0,
                        "APK загружен"
                ));
                notifyReady(pending.entry, contentUri);
            } catch (IOException | RuntimeException error) {
                if (isCurrentPending(pending.id)) {
                    failAndClear(
                            pending,
                            0,
                            "Не удалось подготовить APK: " + safeMessage(error)
                    );
                }
            } finally {
                validatingIds.remove(pending.id);
            }
        });
    }

    private Uri validateCompletedDownload(
            PendingDownload pending,
            long downloaded,
            long total
    ) throws IOException {
        if (downloaded <= MIN_APK_SIZE_BYTES
                || downloaded > MAX_APK_SIZE_BYTES
                || (total > 0 && downloaded != total)
                || total > MAX_APK_SIZE_BYTES) {
            throw new IOException("Некорректный размер загруженного APK");
        }

        Uri contentUri = downloadManager.getUriForDownloadedFile(pending.id);
        if (contentUri == null || !"content".equalsIgnoreCase(contentUri.getScheme())) {
            throw new IOException(
                    "DownloadManager не выдал безопасный content URI"
            );
        }

        if (!hasZipMagic(contentUri)) {
            throw new IOException("Файл не является APK/ZIP");
        }
        verifyExpectedFileSha256(
                contentUri,
                pending.entry.getExpectedFileSha256()
        );
        return contentUri;
    }

    private boolean hasZipMagic(Uri contentUri) throws IOException {
        try (InputStream input = appContext.getContentResolver().openInputStream(contentUri)) {
            if (input == null) {
                return false;
            }

            int first = input.read();
            int second = input.read();
            int third = input.read();
            int fourth = input.read();
            if (first != 0x50 || second != 0x4B) {
                return false;
            }

            return (third == 0x03 && fourth == 0x04)
                    || (third == 0x05 && fourth == 0x06)
                    || (third == 0x07 && fourth == 0x08);
        }
    }

    private void verifyExpectedFileSha256(
            Uri contentUri,
            String expectedSha256
    ) throws IOException {
        if (expectedSha256 == null) {
            return;
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }

        long hashedBytes = 0L;
        try (InputStream rawInput = appContext.getContentResolver()
                .openInputStream(contentUri)) {
            if (rawInput == null) {
                throw new IOException(
                        "DownloadManager content cannot be read"
                );
            }
            InputStream input = new BufferedInputStream(rawInput);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                hashedBytes += read;
                if (hashedBytes > MAX_APK_SIZE_BYTES) {
                    throw new IOException(
                            "Downloaded APK exceeds the size limit"
                    );
                }
                digest.update(buffer, 0, read);
            }
        }

        if (!ApkIntegrityRules.matchesExpectedSha256(
                digest.digest(),
                expectedSha256
        )) {
            throw new IOException(
                    "Downloaded APK SHA-256 does not match the catalog"
            );
        }
    }

    private synchronized void failAndClear(
            PendingDownload pending,
            int reason,
            String message
    ) {
        if (!isCurrentPending(pending.id)) {
            return;
        }
        downloadManager.remove(pending.id);
        clearPending();
        notifyStatus(status(
                State.FAILED,
                pending,
                0,
                -1,
                reason,
                message
        ));
    }

    private synchronized void clearPending() {
        preferences.edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_APP_ID)
                .remove(KEY_FILE_NAME)
                .remove(KEY_DOWNLOAD_URL)
                .remove(KEY_DISPLAY_NAME)
                .apply();
    }

    private synchronized boolean isCurrentPending(long downloadId) {
        return !closed
                && preferences.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD_ID)
                == downloadId;
    }

    private static String createDestinationName(AppCatalog.Entry entry) {
        String source = entry.getFileName();
        int extensionIndex = source.lastIndexOf('.');
        if (extensionIndex <= 0) {
            throw new IllegalArgumentException("Download file has no extension");
        }
        String stem = source.substring(0, extensionIndex);
        String extension = source.substring(extensionIndex);
        String generated = stem + "-" + System.currentTimeMillis() + extension;
        AppCatalog.validateFileName(generated);
        return generated;
    }

    private static Status status(
            State state,
            PendingDownload pending,
            long downloaded,
            long total,
            int reason,
            String message
    ) {
        return new Status(
                state,
                pending.entry,
                pending.id,
                downloaded,
                total,
                reason,
                message
        );
    }

    private void notifyStatus(Status status) {
        try {
            listener.onStatus(status);
        } catch (RuntimeException error) {
            Log.e(TAG, "Status callback failed", error);
        }
    }

    private void notifyReady(AppCatalog.Entry entry, Uri contentUri) {
        try {
            listener.onApkReady(entry, contentUri);
        } catch (RuntimeException error) {
            Log.e(TAG, "Ready callback failed", error);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
