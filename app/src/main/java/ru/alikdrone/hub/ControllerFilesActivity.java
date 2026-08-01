package ru.alikdrone.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * User-controlled file browser for controller storage.
 *
 * <p>Access starts only after ACTION_OPEN_DOCUMENT_TREE. Delete is available
 * only for a file returned by the latest listing and requires a confirmation
 * dialog.</p>
 */
public final class ControllerFilesActivity extends Activity {
    private static final int REQUEST_TREE = 4101;
    private static final String PREFS = "controller_files_saf";
    private static final String KEY_TREE_URI = "tree_uri";
    static final int MAX_RENDERED_ITEMS = 200;

    private final ControllerFilesSafRepository repository = new ControllerFilesSafRepository();
    private final Set<String> currentListingUris = new HashSet<>();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger refreshGeneration = new AtomicInteger();
    private CancellationSignal refreshCancellation;
    private volatile Uri selectedTreeUri;
    private LinearLayout rows;
    private TextView status;
    private TextView location;
    private boolean hasResumedOnce;
    private boolean skipNextResumeRefresh;
    private String lastOperation = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller_files);
        rows = findViewById(R.id.controller_files_rows);
        status = findViewById(R.id.controller_files_status);
        location = findViewById(R.id.controller_files_location);

        findViewById(R.id.button_controller_files_back).setOnClickListener(ignored -> finish());
        findViewById(R.id.button_controller_files_choose).setOnClickListener(
                ignored -> chooseFolder()
        );
        findViewById(R.id.button_controller_files_refresh).setOnClickListener(
                ignored -> refresh()
        );

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedUri = prefs.getString(KEY_TREE_URI, "");
        if (ControllerFilesPolicy.isSafContentUri(savedUri)) {
            selectedTreeUri = Uri.parse(savedUri);
        }
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false;
        } else if (hasResumedOnce) {
            refresh();
        } else {
            hasResumedOnce = true;
        }
    }

    @Override
    protected void onDestroy() {
        refreshGeneration.incrementAndGet();
        if (refreshCancellation != null) {
            refreshCancellation.cancel();
        }
        fileExecutor.shutdownNow();
        super.onDestroy();
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );
        startActivityForResult(intent, REQUEST_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri treeUri = data.getData();
        if (treeUri == null || !ControllerFilesPolicy.isSafContentUri(treeUri.toString())) {
            status.setText("Android не вернул доступ к выбранной папке.");
            return;
        }
        int resultFlags = data.getFlags();
        refreshGeneration.incrementAndGet();
        if (refreshCancellation != null) {
            refreshCancellation.cancel();
        }
        status.setText("Сохраняю доступ к выбранной папке…");
        fileExecutor.execute(() -> {
            String persistedValue = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_TREE_URI, "");
            Uri previousTree = ControllerFilesPolicy.isSafContentUri(
                    persistedValue
            ) ? Uri.parse(persistedValue) : null;
            boolean newPermissionTaken = false;
            try {
                repository.persistTreePermission(
                        getContentResolver(),
                        treeUri,
                        resultFlags
                );
                newPermissionTaken = true;
                boolean committed = getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_TREE_URI, treeUri.toString())
                        .commit();
                if (!committed) {
                    throw new IllegalStateException("Unable to persist selected SAF tree");
                }
                if (previousTree != null && !previousTree.equals(treeUri)) {
                    repository.releaseTreePermission(getContentResolver(), previousTree);
                }
            } catch (RuntimeException denied) {
                if (newPermissionTaken
                        && (previousTree == null || !previousTree.equals(treeUri))) {
                    repository.releaseTreePermission(getContentResolver(), treeUri);
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        status.setText(
                                "Не удалось сохранить доступ. Выберите папку ещё раз."
                        );
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                selectedTreeUri = treeUri;
                skipNextResumeRefresh = true;
                refresh();
            });
        });
    }

    private void refresh() {
        int generation = refreshGeneration.incrementAndGet();
        if (refreshCancellation != null) {
            refreshCancellation.cancel();
        }
        refreshCancellation = new CancellationSignal();
        rows.removeAllViews();
        currentListingUris.clear();
        if (selectedTreeUri == null) {
            location.setText("Папка не выбрана");
            status.setText(ControllerFilesPolicy.scopeNotice());
            return;
        }
        location.setText("Выбранная папка: " + selectedTreeUri);
        status.setText(withLastOperation("Читаю выбранную папку…"));
        Uri tree = selectedTreeUri;
        CancellationSignal cancellation = refreshCancellation;
        fileExecutor.execute(() -> {
            ControllerFilesSafRepository.Listing listing =
                    repository.listLimited(
                    getContentResolver(),
                    tree,
                    cancellation
            );
            runOnUiThread(() -> renderListing(tree, listing, generation));
        });
    }

    private void renderListing(
            Uri tree,
            ControllerFilesSafRepository.Listing listing,
            int generation
    ) {
        if (generation != refreshGeneration.get()
                || isFinishing()
                || isDestroyed()
                || selectedTreeUri == null
                || !selectedTreeUri.equals(tree)) {
            return;
        }
        List<ControllerFileItem> items = listing.items;
        rows.removeAllViews();
        currentListingUris.clear();
        if (items.isEmpty()) {
            status.setText(withLastOperation(
                    "В выбранной папке нет доступных файлов или Android отозвал доступ."
            ));
            return;
        }
        int renderedCount = Math.min(items.size(), MAX_RENDERED_ITEMS);
        status.setText(withLastOperation(
                "Найдено: " + items.size() + ". Показано: " + renderedCount + ". "
                        + ControllerFilesPolicy.scopeNotice()
                        + " В этой версии показывается верхний уровень выбранной папки."
                        + (items.size() > renderedCount
                        ? " Для защиты интерфейса список ограничен первыми "
                        + MAX_RENDERED_ITEMS + " элементами."
                        : "")
                        + (listing.truncated
                        ? " Сканирование остановлено после "
                        + ControllerFilesSafRepository.MAX_SCANNED_ITEMS
                        + " элементов; выберите более узкую папку."
                        : "")
        ));
        for (int index = 0; index < renderedCount; index++) {
            ControllerFileItem item = items.get(index);
            currentListingUris.add(item.uri.toString());
            addRow(item);
        }
    }

    private String withLastOperation(String normalStatus) {
        if (lastOperation == null || lastOperation.isEmpty()) {
            return normalStatus;
        }
        return lastOperation + "\n\n" + normalStatus;
    }

    private void addRow(ControllerFileItem item) {
        View row = LayoutInflater.from(this).inflate(
                R.layout.controller_file_row,
                rows,
                false
        );
        TextView title = row.findViewById(R.id.controller_file_name);
        TextView details = row.findViewById(R.id.controller_file_details);
        Button delete = row.findViewById(R.id.button_controller_file_delete);
        Button open = row.findViewById(R.id.button_controller_file_open);
        Button share = row.findViewById(R.id.button_controller_file_share);
        title.setText((item.directory ? "Папка · " : "") + item.displayName);
        details.setText(fileDetails(item));
        delete.setVisibility(
                item.directory || !item.supportsDelete ? View.GONE : View.VISIBLE
        );
        open.setVisibility(item.directory ? View.GONE : View.VISIBLE);
        share.setVisibility(item.directory ? View.GONE : View.VISIBLE);
        delete.setOnClickListener(ignored -> confirmDelete(item));
        open.setOnClickListener(ignored -> openFile(item));
        share.setOnClickListener(ignored -> shareFile(item));
        rows.addView(row);
    }

    private String fileDetails(ControllerFileItem item) {
        String size = item.directory ? "каталог" : readableBytes(item.sizeBytes);
        if (item.modifiedAt <= 0L) {
            return size + " · " + item.mimeType;
        }
        String date = DateFormat.getDateFormat(this).format(new Date(item.modifiedAt));
        return size + " · " + date + " · " + item.mimeType;
    }

    private String readableBytes(long bytes) {
        if (bytes < 0L) {
            return "размер неизвестен";
        }
        if (bytes < 1024L) {
            return bytes + " Б";
        }
        double kib = bytes / 1024d;
        if (kib < 1024d) {
            return String.format(Locale.getDefault(), "%.1f КБ", kib);
        }
        return String.format(Locale.getDefault(), "%.1f МБ", kib / 1024d);
    }

    private void confirmDelete(ControllerFileItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Подтвердите удаление")
                .setMessage(ControllerFilesPolicy.deletionPrompt(item.displayName))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    boolean listed = currentListingUris.contains(item.uri.toString());
                    int confirmedGeneration = refreshGeneration.get();
                    Uri confirmedTree = selectedTreeUri;
                    lastOperation = "Удаляю файл через системное хранилище Android…";
                    status.setText(lastOperation);
                    fileExecutor.execute(() -> {
                        boolean listingStillCurrent =
                                confirmedGeneration == refreshGeneration.get()
                                        && confirmedTree != null
                                        && confirmedTree.equals(selectedTreeUri);
                        boolean deleted = listingStillCurrent
                                && repository.delete(
                                        getContentResolver(),
                                        item,
                                        true,
                                        listed
                                );
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            lastOperation = deleted
                                    ? "Файл удалён через системное хранилище Android."
                                    : "Удалить файл не удалось. Проверьте доступ к папке.";
                            refresh();
                        });
                    });
                })
                .show();
    }

    private void openFile(ControllerFileItem item) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(item.uri, item.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri(item.displayName, item.uri));
        try {
            startActivity(view);
        } catch (RuntimeException noViewer) {
            lastOperation = "Для этого типа файла на пульте нет подходящего приложения.";
            refresh();
        }
    }

    private void shareFile(ControllerFileItem item) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(item.mimeType)
                .putExtra(Intent.EXTRA_STREAM, item.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(item.displayName, item.uri));
        try {
            startActivity(Intent.createChooser(send, "Отправить файл"));
        } catch (RuntimeException noReceiver) {
            lastOperation = "На пульте нет приложения, которое может принять этот файл.";
            refresh();
        }
    }
}
