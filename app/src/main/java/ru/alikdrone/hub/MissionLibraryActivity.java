package ru.alikdrone.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Local KML/KMZ library. It indexes only user-selected SAF documents and never
 * executes a mission or modifies DJI Fly data.
 */
public final class MissionLibraryActivity extends Activity {
    static final int REQUEST_MISSION = 4201;
    private static final long MAX_SOURCE_BYTES = 40L * 1024L * 1024L;
    private static final String LIBRARY_DIRECTORY = "mission-library";
    private static final String STATE_OPERATION_REVISION =
            "mission_library_operation_revision";
    private static final LibraryOperationCoordinator OPERATIONS =
            new LibraryOperationCoordinator();
    private final LibraryOperationObserver operationObserver =
            new LibraryOperationObserver() {
                @Override
                public void onOperationChanged(
                        LibraryOperationSnapshot snapshot
                ) {
                    applyOperationSnapshot(snapshot);
                }

                @Override
                public void onLibraryReconciled() {
                    applyLibraryReconciled();
                }
            };
    private MissionLibraryStore store;
    private LinearLayout rows;
    private TextView status;
    private Button importButton;
    private long lastOperationRevision = -1L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mission_library);
        store = new MissionLibraryStore(this);
        if (savedInstanceState != null) {
            lastOperationRevision = savedInstanceState.getLong(
                    STATE_OPERATION_REVISION,
                    -1L
            );
        }
        OPERATIONS.cleanupInterruptedImports(getApplicationContext());
        rows = findViewById(R.id.mission_library_rows);
        status = findViewById(R.id.mission_library_status);
        findViewById(R.id.button_mission_library_back).setOnClickListener(ignored -> finish());
        importButton = findViewById(R.id.button_mission_library_import);
        importButton.setOnClickListener(ignored -> selectMission());
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        OPERATIONS.attach(operationObserver);
    }

    @Override
    protected void onStop() {
        OPERATIONS.detach(operationObserver);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        OPERATIONS.detach(operationObserver);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_OPERATION_REVISION, lastOperationRevision);
    }

    private void selectMission() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(
                Intent.EXTRA_MIME_TYPES,
                new String[]{
                        "application/vnd.google-earth.kml+xml",
                        "application/vnd.google-earth.kmz",
                        "application/xml",
                        "text/xml",
                        "application/zip"
                }
        );
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );
        startActivityForResult(intent, REQUEST_MISSION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MISSION || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null || !ControllerFilesPolicy.isSafContentUri(uri.toString())) {
            status.setText("Android не вернул доступ к файлу.");
            return;
        }
        // Provider metadata can require IPC or remote I/O. Resolve it inside
        // the application-scoped operation coordinator, never on the UI
        // thread that receives the picker result.
        String displayName = fallbackName(uri);
        status.setText("Проверяю " + displayName + "…");
        importButton.setEnabled(false);
        if (!OPERATIONS.importMission(getApplicationContext(), uri, displayName)) {
            importButton.setEnabled(true);
            status.setText("Дождитесь завершения текущей операции библиотеки.");
        }
    }

    private void applyOperationSnapshot(LibraryOperationSnapshot snapshot) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        importButton.setEnabled(!snapshot.running);
        if (snapshot.running) {
            lastOperationRevision = Math.max(
                    lastOperationRevision,
                    snapshot.revision
            );
            if (snapshot.kind == OperationKind.IMPORT) {
                status.setText("Проверяю " + snapshot.displayName + "…");
            } else if (snapshot.kind == OperationKind.REMOVE) {
                status.setText("Убираю " + snapshot.displayName + "…");
            }
            return;
        }
        if (snapshot.revision <= lastOperationRevision) {
            return;
        }
        lastOperationRevision = snapshot.revision;
        if (snapshot.kind == OperationKind.IMPORT
                && snapshot.validation != null) {
            if (snapshot.validation.valid) {
                render();
                status.setText(snapshot.alreadyIndexed
                        ? "Такой файл уже есть в библиотеке (совпадает SHA-256)."
                        : snapshot.validation.summary);
            } else {
                status.setText(snapshot.validation.summary);
                new AlertDialog.Builder(this)
                        .setTitle("Файл отклонён")
                        .setMessage(snapshot.validation.summary)
                        .setPositiveButton("Понятно", null)
                        .show();
            }
        } else if (snapshot.kind == OperationKind.REMOVE) {
            if (snapshot.successful) {
                render();
            }
            status.setText(snapshot.message);
        }
        if (snapshot.kind != OperationKind.IDLE) {
            OPERATIONS.acknowledge(snapshot.revision);
        }
    }

    private void applyLibraryReconciled() {
        if (isFinishing() || isDestroyed() || OPERATIONS.isRunning()) {
            return;
        }
        render();
    }

    private void render() {
        rows.removeAllViews();
        List<MissionLibraryStore.Entry> entries = store.list();
        if (entries.isEmpty()) {
            status.setText(
                    "Библиотека пуста. Импорт проверяет только структуру. "
                            + "Корректный KML/KMZ не означает безопасный или совместимый полёт."
            );
            return;
        }
        for (MissionLibraryStore.Entry entry : entries) {
            View row = LayoutInflater.from(this).inflate(
                    R.layout.mission_library_row,
                    rows,
                    false
            );
            ((TextView) row.findViewById(R.id.mission_library_entry_name))
                    .setText(entry.displayName);
            String warningText = entry.warnings.isEmpty()
                    ? "Проверено при импорте: " + entry.validationSummary
                    : "Проверено при импорте: " + entry.validationSummary
                            + "\nВнимание: " + join(entry.warnings);
            if (!entry.sha256.isEmpty()) {
                warningText += "\nSHA-256: " + entry.sha256.substring(
                        0,
                        Math.min(16, entry.sha256.length())
                ) + "…";
            }
            ((TextView) row.findViewById(R.id.mission_library_entry_status))
                    .setText(warningText);
            Button remove = row.findViewById(R.id.button_mission_library_remove);
            remove.setOnClickListener(ignored -> new AlertDialog.Builder(this)
                    .setTitle("Убрать из библиотеки?")
                    .setMessage(
                            "Исходный файл не удаляется. Будет удалена только локальная "
                                    + "копия из библиотеки Hub."
                    )
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Убрать", (dialog, which) -> {
                        if (!OPERATIONS.removeEntry(
                                getApplicationContext(),
                                entry
                        )) {
                            status.setText(
                                    "Дождитесь завершения текущей операции библиотеки."
                            );
                        }
                    })
                    .show());
            rows.addView(row);
        }
    }

    private static String queryName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && !value.trim().isEmpty()) {
                    return value;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to the final URI segment.
        }
        return fallbackName(uri);
    }

    private static String fallbackName(Uri uri) {
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "mission.kml" : fallback;
    }

    private static long querySize(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{OpenableColumns.SIZE},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return Math.max(0L, cursor.getLong(0));
            }
        } catch (RuntimeException ignored) {
            // Unknown size remains zero and stream limits still apply.
        }
        return 0L;
    }

    private static long copyAndDigest(
            Context context,
            Uri uri,
            File destination,
            MessageDigest digest
    ) throws Exception {
        long copied = 0L;
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("Android не открыл выбранный файл");
            }
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Импорт отменён");
                }
                copied += read;
                if (copied > MAX_SOURCE_BYTES) {
                    throw new IOException("Файл превышает лимит 40 МБ");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
        if (copied == 0L) {
            throw new IOException("Выбран пустой файл");
        }
        return copied;
    }

    private String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append("; ");
            }
            result.append(value);
        }
        return result.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static boolean deleteLibraryCopy(Context context, String storedPath) {
        if (storedPath == null || storedPath.trim().isEmpty()) {
            return true;
        }
        try {
            File root = new File(
                    context.getFilesDir(),
                    LIBRARY_DIRECTORY
            ).getCanonicalFile();
            File target = new File(storedPath).getCanonicalFile();
            File parent = target.getParentFile();
            if (root.equals(parent) && target.isFile()) {
                return target.delete();
            }
            return !target.exists();
        } catch (IOException ignored) {
            return false;
        }
    }

    static void resetOperationsForTesting() {
        OPERATIONS.resetForTesting();
    }

    static boolean isOperationRunningForTesting() {
        return OPERATIONS.hasPendingWorkForTesting();
    }

    private interface LibraryOperationObserver {
        void onOperationChanged(LibraryOperationSnapshot snapshot);

        void onLibraryReconciled();
    }

    private enum OperationKind {
        IDLE,
        IMPORT,
        REMOVE
    }

    private static final class LibraryOperationSnapshot {
        final long revision;
        final OperationKind kind;
        final boolean running;
        final String displayName;
        final KmlKmzValidator.Result validation;
        final boolean alreadyIndexed;
        final boolean successful;
        final String message;

        private LibraryOperationSnapshot(
                long revision,
                OperationKind kind,
                boolean running,
                String displayName,
                KmlKmzValidator.Result validation,
                boolean alreadyIndexed,
                boolean successful,
                String message
        ) {
            this.revision = revision;
            this.kind = kind;
            this.running = running;
            this.displayName = displayName == null ? "" : displayName;
            this.validation = validation;
            this.alreadyIndexed = alreadyIndexed;
            this.successful = successful;
            this.message = message == null ? "" : message;
        }

        static LibraryOperationSnapshot idle(long revision) {
            return new LibraryOperationSnapshot(
                    revision,
                    OperationKind.IDLE,
                    false,
                    "",
                    null,
                    false,
                    true,
                    ""
            );
        }

        static LibraryOperationSnapshot importing(
                long revision,
                String displayName
        ) {
            return new LibraryOperationSnapshot(
                    revision,
                    OperationKind.IMPORT,
                    true,
                    displayName,
                    null,
                    false,
                    false,
                    ""
            );
        }

        static LibraryOperationSnapshot importResult(
                long revision,
                String displayName,
                KmlKmzValidator.Result validation,
                boolean alreadyIndexed
        ) {
            return new LibraryOperationSnapshot(
                    revision,
                    OperationKind.IMPORT,
                    false,
                    displayName,
                    validation,
                    alreadyIndexed,
                    validation != null && validation.valid,
                    validation == null ? "" : validation.summary
            );
        }

        static LibraryOperationSnapshot removing(
                long revision,
                String displayName
        ) {
            return new LibraryOperationSnapshot(
                    revision,
                    OperationKind.REMOVE,
                    true,
                    displayName,
                    null,
                    false,
                    false,
                    ""
            );
        }

        static LibraryOperationSnapshot removeResult(
                long revision,
                String displayName,
                boolean successful,
                String message
        ) {
            return new LibraryOperationSnapshot(
                    revision,
                    OperationKind.REMOVE,
                    false,
                    displayName,
                    null,
                    false,
                    successful,
                    message
            );
        }
    }

    private static final class LibraryOperationCoordinator {
        private final ExecutorService executor =
                Executors.newSingleThreadExecutor();
        private final Handler mainHandler =
                new Handler(Looper.getMainLooper());
        private final KmlKmzValidator validator = new KmlKmzValidator();
        private WeakReference<LibraryOperationObserver> observer =
                new WeakReference<>(null);
        private LibraryOperationSnapshot snapshot =
                LibraryOperationSnapshot.idle(0L);
        private long revision;
        private boolean cleanupScheduled;

        synchronized boolean isRunning() {
            return snapshot.running;
        }

        synchronized boolean hasPendingWorkForTesting() {
            return snapshot.running || cleanupScheduled;
        }

        void attach(LibraryOperationObserver nextObserver) {
            synchronized (this) {
                observer = new WeakReference<>(nextObserver);
            }
            dispatch();
        }

        synchronized void detach(LibraryOperationObserver currentObserver) {
            if (observer.get() == currentObserver) {
                observer.clear();
            }
        }

        synchronized void acknowledge(long deliveredRevision) {
            if (!snapshot.running
                    && snapshot.kind != OperationKind.IDLE
                    && snapshot.revision == deliveredRevision) {
                snapshot = LibraryOperationSnapshot.idle(deliveredRevision);
            }
        }

        void cleanupInterruptedImports(Context context) {
            Context appContext = applicationContext(context);
            synchronized (this) {
                if (cleanupScheduled || snapshot.running) {
                    return;
                }
                cleanupScheduled = true;
            }
            try {
                executor.execute(() -> {
                    boolean indexChanged = false;
                    try {
                        indexChanged = reconcileInterruptedImports(appContext);
                    } finally {
                        synchronized (LibraryOperationCoordinator.this) {
                            cleanupScheduled = false;
                        }
                    }
                    if (indexChanged) {
                        dispatchLibraryReconciled();
                    }
                });
            } catch (RejectedExecutionException rejected) {
                synchronized (this) {
                    cleanupScheduled = false;
                }
            }
        }

        boolean importMission(Context context, Uri uri, String displayName) {
            Context appContext = applicationContext(context);
            synchronized (this) {
                if (snapshot.kind != OperationKind.IDLE) {
                    return false;
                }
                snapshot = LibraryOperationSnapshot.importing(
                        ++revision,
                        displayName
                );
            }
            dispatch();
            try {
                executor.execute(
                        () -> runImport(appContext, uri, displayName)
                );
            } catch (RejectedExecutionException rejected) {
                finishImport(
                        displayName,
                        KmlKmzValidator.Result.invalid(
                                "Не удалось запустить импорт."
                        ),
                        false
                );
            }
            return true;
        }

        boolean removeEntry(
                Context context,
                MissionLibraryStore.Entry entry
        ) {
            if (entry == null) {
                return false;
            }
            Context appContext = applicationContext(context);
            synchronized (this) {
                if (snapshot.kind != OperationKind.IDLE) {
                    return false;
                }
                snapshot = LibraryOperationSnapshot.removing(
                        ++revision,
                        entry.displayName
                );
            }
            dispatch();
            try {
                executor.execute(() -> runRemove(appContext, entry));
            } catch (RejectedExecutionException rejected) {
                finishRemove(
                        entry.displayName,
                        false,
                        "Не удалось запустить удаление из библиотеки."
                );
            }
            return true;
        }

        private void runImport(
                Context context,
                Uri uri,
                String displayName
        ) {
            MissionLibraryStore operationStore =
                    new MissionLibraryStore(context);
            File stagedFile = null;
            File storedFile = null;
            boolean indexed = false;
            String resolvedDisplayName = displayName;
            try {
                resolvedDisplayName = queryName(context, uri);
                if (querySize(context, uri) > MAX_SOURCE_BYTES) {
                    throw new IOException("File exceeds the 40 MB limit");
                }
                if (!operationStore.isIndexReadable()) {
                    throw new IOException(
                            "Локальный индекс библиотеки повреждён; перезапись запрещена"
                    );
                }
                File directory = new File(
                        context.getFilesDir(),
                        LIBRARY_DIRECTORY
                );
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException(
                            "Не удалось создать локальную библиотеку"
                    );
                }
                stagedFile = File.createTempFile(
                        "mission-",
                        ".part",
                        directory
                );
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long copied = copyAndDigest(
                        context,
                        uri,
                        stagedFile,
                        digest
                );
                KmlKmzValidator.Result validation;
                try (InputStream input = new FileInputStream(stagedFile)) {
                    validation = validator.validate(
                            resolvedDisplayName,
                            input,
                            copied
                    );
                }
                if (!validation.valid) {
                    deleteQuietly(stagedFile);
                    finishImport(resolvedDisplayName, validation, false);
                    return;
                }
                String sha256 = hex(digest.digest());
                for (MissionLibraryStore.Entry existing :
                        operationStore.list()) {
                    if (sha256.equalsIgnoreCase(existing.sha256)) {
                        deleteQuietly(stagedFile);
                        finishImport(resolvedDisplayName, validation, true);
                        return;
                    }
                }

                String extension = resolvedDisplayName.toLowerCase(Locale.ROOT)
                        .endsWith(".kmz") ? ".kmz" : ".kml";
                storedFile = new File(directory, sha256 + extension);
                if (storedFile.exists()) {
                    deleteQuietly(stagedFile);
                } else if (!stagedFile.renameTo(storedFile)) {
                    throw new IOException(
                            "Не удалось сохранить локальную копию"
                    );
                }
                operationStore.add(
                        resolvedDisplayName,
                        storedFile.getAbsolutePath(),
                        validation,
                        sha256
                );
                indexed = true;
                finishImport(resolvedDisplayName, validation, false);
            } catch (Exception error) {
                deleteQuietly(stagedFile);
                if (!indexed) {
                    deleteQuietly(storedFile);
                }
                finishImport(
                        resolvedDisplayName,
                        KmlKmzValidator.Result.invalid(
                                "Не удалось прочитать выбранный файл: "
                                        + error.getClass().getSimpleName()
                        ),
                        false
                );
            }
        }

        private void runRemove(
                Context context,
                MissionLibraryStore.Entry entry
        ) {
            try {
                // Commit the index change first. If deleting the private file
                // then fails, startup reconciliation can remove the orphan.
                new MissionLibraryStore(context).remove(entry.id);
                boolean deleted = deleteLibraryCopy(
                        context,
                        entry.contentUri
                );
                finishRemove(
                        entry.displayName,
                        true,
                        deleted
                                ? "Запись и локальная копия убраны."
                                : "Запись убрана. Локальный файл будет очищен "
                                        + "при следующем запуске."
                );
            } catch (RuntimeException writeFailure) {
                finishRemove(
                        entry.displayName,
                        false,
                        "Не удалось сохранить изменение библиотеки. "
                                + "Запись и файл оставлены."
                );
            }
        }

        private void finishImport(
                String displayName,
                KmlKmzValidator.Result validation,
                boolean alreadyIndexed
        ) {
            synchronized (this) {
                snapshot = LibraryOperationSnapshot.importResult(
                        ++revision,
                        displayName,
                        validation,
                        alreadyIndexed
                );
            }
            dispatch();
        }

        private void finishRemove(
                String displayName,
                boolean successful,
                String message
        ) {
            synchronized (this) {
                snapshot = LibraryOperationSnapshot.removeResult(
                        ++revision,
                        displayName,
                        successful,
                        message
                );
            }
            dispatch();
        }

        private void dispatch() {
            mainHandler.post(this::deliverCurrent);
        }

        private void dispatchLibraryReconciled() {
            mainHandler.post(() -> {
                LibraryOperationObserver currentObserver;
                synchronized (LibraryOperationCoordinator.this) {
                    currentObserver = observer.get();
                }
                if (currentObserver != null) {
                    currentObserver.onLibraryReconciled();
                }
            });
        }

        private void deliverCurrent() {
            LibraryOperationObserver currentObserver;
            LibraryOperationSnapshot currentSnapshot;
            synchronized (this) {
                currentObserver = observer.get();
                currentSnapshot = snapshot;
            }
            if (currentObserver != null) {
                currentObserver.onOperationChanged(currentSnapshot);
            }
        }

        synchronized void resetForTesting() {
            if (snapshot.running || cleanupScheduled) {
                throw new IllegalStateException(
                        "Cannot reset a running library operation"
                );
            }
            observer.clear();
            revision = 0L;
            snapshot = LibraryOperationSnapshot.idle(0L);
        }

        private static Context applicationContext(Context context) {
            Context candidate = context.getApplicationContext();
            return candidate == null ? context : candidate;
        }
    }

    private static boolean reconcileInterruptedImports(Context context) {
        File directory = new File(
                context.getFilesDir(),
                LIBRARY_DIRECTORY
        );
        File[] files = directory.listFiles();
        if (files == null) {
            return false;
        }
        MissionLibraryStore operationStore =
                new MissionLibraryStore(context);
        boolean indexChanged = false;
        Set<String> indexedPaths = new HashSet<>();
        List<MissionLibraryStore.Entry> indexedEntries =
                operationStore.list();
        if (operationStore.isIndexReadable()) {
            for (MissionLibraryStore.Entry entry : indexedEntries) {
                try {
                    File indexedFile = new File(entry.contentUri).getCanonicalFile();
                    if (indexedFile.isFile()) {
                        indexedPaths.add(indexedFile.getCanonicalPath());
                    } else {
                        try {
                            operationStore.remove(entry.id);
                            indexChanged = true;
                        } catch (IllegalStateException ignored) {
                            // Keep running; no file is deleted based on an
                            // index change that could not be persisted.
                        }
                    }
                } catch (IOException ignored) {
                    // Invalid index paths are never used as deletion targets.
                }
            }
        }
        boolean indexReadable = operationStore.isIndexReadable();
        for (File file : files) {
            String lowerName = file.getName().toLowerCase(Locale.ROOT);
            if (file.isFile() && lowerName.endsWith(".part")) {
                deleteQuietly(file);
                continue;
            }
            if (!indexReadable
                    || !file.isFile()
                    || !lowerName.matches("[0-9a-f]{64}\\.(?:kml|kmz)")) {
                continue;
            }
            try {
                if (!indexedPaths.contains(file.getCanonicalPath())) {
                    deleteQuietly(file);
                }
            } catch (IOException ignored) {
                // Keep anything that cannot be resolved safely.
            }
        }
        return indexChanged;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.isFile()) {
            // Failure is harmless: the file remains inside the app-private directory.
            file.delete();
        }
    }
}
