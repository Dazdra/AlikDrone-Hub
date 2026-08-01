package ru.alikdrone.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local checklist and maintenance journal.
 *
 * <p>All facts are explicitly labeled as manually entered. Controller battery
 * state is never presented as aircraft/flight-battery telemetry.</p>
 */
public final class MaintenanceActivity extends Activity {
    private static final String CHECKLIST_PREFS = "preflight_checklist_local";
    private static final String KEY_CHECKLIST_DAY = "_checklist_day";
    private static final int MAX_RENDERED_ENTRIES = 100;
    private static final ExecutorService JOURNAL_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "alikdrone-maintenance-journal"
                );
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    private final Set<String> checkedIds = new HashSet<>();
    private MaintenanceJournalStore journal;
    private SharedPreferences checklistPreferences;
    private LinearLayout checklistRows;
    private LinearLayout journalRows;
    private TextView checklistStatus;
    private TextView journalStatus;
    private Spinner assetSpinner;
    private EditText actionInput;
    private EditText noteInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maintenance);
        journal = new MaintenanceJournalStore(this);
        checklistPreferences = getSharedPreferences(CHECKLIST_PREFS, MODE_PRIVATE);
        checklistRows = findViewById(R.id.maintenance_checklist_rows);
        journalRows = findViewById(R.id.maintenance_journal_rows);
        checklistStatus = findViewById(R.id.maintenance_checklist_status);
        journalStatus = findViewById(R.id.maintenance_journal_status);
        assetSpinner = findViewById(R.id.maintenance_asset_spinner);
        actionInput = findViewById(R.id.maintenance_action_input);
        noteInput = findViewById(R.id.maintenance_note_input);

        findViewById(R.id.button_maintenance_back).setOnClickListener(ignored -> finish());
        findViewById(R.id.button_maintenance_save_checklist).setOnClickListener(
                ignored -> recordChecklist()
        );
        findViewById(R.id.button_maintenance_reset_checklist).setOnClickListener(
                ignored -> resetChecklist()
        );
        findViewById(R.id.button_maintenance_add).setOnClickListener(
                ignored -> addMaintenanceEntry()
        );

        setupAssetSpinner();
        renderChecklist();
        renderJournal();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checklistPreferences != null && resetChecklistForNewDay()) {
            renderChecklist();
        }
    }

    private void setupAssetSpinner() {
        MaintenanceEntry.Asset[] assets = MaintenanceEntry.Asset.values();
        String[] labels = new String[assets.length];
        for (int index = 0; index < assets.length; index++) {
            labels[index] = assets[index].label;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        assetSpinner.setAdapter(adapter);
    }

    private void renderChecklist() {
        resetChecklistForNewDay();
        checklistRows.removeAllViews();
        checkedIds.clear();
        for (ChecklistPolicy.Item item : ChecklistPolicy.items()) {
            boolean checked = checklistPreferences.getBoolean(item.id, false);
            if (checked) {
                checkedIds.add(item.id);
            }
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(item.hint.isEmpty()
                    ? item.label
                    : item.label + "\n" + item.hint);
            checkBox.setTextColor(getColor(R.color.text_primary));
            checkBox.setTextSize(14f);
            checkBox.setPadding(6, 7, 6, 7);
            checkBox.setChecked(checked);
            checkBox.setOnCheckedChangeListener((button, isChecked) -> {
                checklistPreferences.edit().putBoolean(item.id, isChecked).apply();
                if (isChecked) {
                    checkedIds.add(item.id);
                } else {
                    checkedIds.remove(item.id);
                }
                updateChecklistStatus();
            });
            checklistRows.addView(checkBox);
        }
        updateChecklistStatus();
    }

    private void updateChecklistStatus() {
        checklistStatus.setText(
                ChecklistPolicy.evaluate(checkedIds).summary()
                        + "\n"
                        + ChecklistPolicy.disclaimer()
        );
    }

    private void recordChecklist() {
        if (resetChecklistForNewDay()) {
            renderChecklist();
            journalStatus.setText(
                    "Наступил новый день. Вчерашние отметки сброшены; заполните чек-лист заново."
            );
            return;
        }
        ChecklistPolicy.Status checklist = ChecklistPolicy.evaluate(checkedIds);
        StringBuilder details = new StringBuilder(ChecklistPolicy.disclaimer())
                .append("\nСхема чек-листа: 1.");
        if (!checklist.missing.isEmpty()) {
            details.append("\nНе отмечено: ");
            for (int index = 0; index < checklist.missing.size(); index++) {
                if (index > 0) {
                    details.append("; ");
                }
                details.append(checklist.missing.get(index));
            }
        }
        MaintenanceEntry entry = MaintenanceEntry.manual(
                MaintenanceEntry.Asset.OTHER,
                "Предполётный чек-лист: " + checklist.completed + "/" + checklist.total,
                details.toString()
        );
        JOURNAL_EXECUTOR.execute(() -> {
            final boolean oldestRemoved;
            try {
                oldestRemoved = journal.add(entry);
            } catch (IllegalStateException writeFailure) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        journalStatus.setText(
                                "Не удалось записать чек-лист. Проверьте свободное место на пульте."
                        );
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                journalStatus.setText(
                        "Состояние чек-листа записано локально."
                                + retentionNotice(oldestRemoved)
                );
                renderJournal();
            });
        });
    }

    private void resetChecklist() {
        new AlertDialog.Builder(this)
                .setTitle("Сбросить отметки?")
                .setMessage("Записи журнала не удаляются. Сбрасываются только текущие галочки.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сбросить", (dialog, which) -> {
                    checklistPreferences.edit().clear().apply();
                    renderChecklist();
                })
                .show();
    }

    private boolean resetChecklistForNewDay() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
        String savedDay = checklistPreferences.getString(KEY_CHECKLIST_DAY, "");
        if (!today.equals(savedDay)) {
            checklistPreferences.edit()
                    .clear()
                    .putString(KEY_CHECKLIST_DAY, today)
                    .apply();
            return true;
        }
        return false;
    }

    private void addMaintenanceEntry() {
        String action = actionInput.getText().toString().trim();
        if (action.isEmpty()) {
            journalStatus.setText("Укажите выполненное действие или наблюдение.");
            return;
        }
        MaintenanceEntry.Asset[] assets = MaintenanceEntry.Asset.values();
        int position = Math.max(0, Math.min(assetSpinner.getSelectedItemPosition(), assets.length - 1));
        MaintenanceEntry entry = MaintenanceEntry.manual(
                assets[position],
                action,
                noteInput.getText().toString()
        );
        JOURNAL_EXECUTOR.execute(() -> {
            final boolean oldestRemoved;
            try {
                oldestRemoved = journal.add(entry);
            } catch (IllegalStateException writeFailure) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        journalStatus.setText(
                                "Не удалось сохранить запись. Проверьте свободное место на пульте."
                        );
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                actionInput.setText("");
                noteInput.setText("");
                journalStatus.setText(
                        "Запись сохранена только на этом пульте. "
                                + entry.provenanceDisplay()
                                + retentionNotice(oldestRemoved)
                );
                renderJournal();
            });
        });
    }

    private void renderJournal() {
        journalRows.removeAllViews();
        List<MaintenanceEntry> entries = journal.list();
        if (entries.isEmpty()) {
            journalStatus.setText("Журнал пуст. Автоматическая телеметрия не подставляется.");
            return;
        }
        int rendered = 0;
        for (MaintenanceEntry entry : entries) {
            if (rendered >= MAX_RENDERED_ENTRIES) {
                break;
            }
            rendered++;
            View row = LayoutInflater.from(this).inflate(
                    R.layout.maintenance_entry_row,
                    journalRows,
                    false
            );
            ((TextView) row.findViewById(R.id.maintenance_entry_title)).setText(
                    entry.asset.label + " · " + entry.action
            );
            String date = DateFormat.getDateTimeInstance().format(new Date(entry.createdAt));
            StringBuilder details = new StringBuilder(date)
                    .append("\n")
                    .append(entry.provenanceDisplay());
            if (!entry.note.isEmpty()) {
                details.append("\n").append(entry.note);
            }
            if (!entry.assetCaution().isEmpty()) {
                details.append("\n").append(entry.assetCaution());
            }
            ((TextView) row.findViewById(R.id.maintenance_entry_details))
                    .setText(details.toString());
            row.findViewById(R.id.button_maintenance_entry_delete).setOnClickListener(
                    ignored -> new AlertDialog.Builder(this)
                            .setTitle("Удалить запись?")
                            .setMessage("Локальная запись будет удалена без восстановления.")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Удалить", (dialog, which) -> {
                                JOURNAL_EXECUTOR.execute(() -> {
                                    try {
                                        journal.remove(entry.id);
                                    } catch (IllegalStateException writeFailure) {
                                        runOnUiThread(() -> {
                                            if (!isFinishing() && !isDestroyed()) {
                                                journalStatus.setText(
                                                        "Не удалось сохранить удаление. Запись оставлена."
                                                );
                                            }
                                        });
                                        return;
                                    }
                                    runOnUiThread(() -> {
                                        if (!isFinishing() && !isDestroyed()) {
                                            renderJournal();
                                        }
                                    });
                                });
                            })
                            .show()
            );
            journalRows.addView(row);
        }
        if (entries.size() > MAX_RENDERED_ENTRIES) {
            journalStatus.setText(
                    "Показаны последние " + MAX_RENDERED_ENTRIES
                            + " записей из " + entries.size() + "."
            );
        }
    }

    private String retentionNotice(boolean oldestRemoved) {
        return oldestRemoved
                ? " Достигнут лимит 250 записей: самая старая запись удалена."
                : "";
    }
}
