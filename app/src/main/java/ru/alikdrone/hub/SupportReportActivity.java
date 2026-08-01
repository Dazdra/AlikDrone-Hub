package ru.alikdrone.hub;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Local preview and explicit copy/share screen for a privacy-minimised report.
 */
public final class SupportReportActivity extends Activity {
    private final Map<Rc2SupportReportModel.Section, CheckBox> choices =
            new EnumMap<>(Rc2SupportReportModel.Section.class);
    private Rc2SupportReportModel.Snapshot snapshot;
    private TextView preview;
    private final ExecutorService reportExecutor =
            Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private int collectionGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));
        setContentView(buildContent());
        collectSnapshot(null, false);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        collectionGeneration++;
        reportExecutor.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.background));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(content);

        Button back = secondaryButton("← Назад");
        back.setOnClickListener(ignored -> finish());
        content.addView(back, wrap());

        TextView title = text("Отчёт поддержки", 25, R.color.text_primary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addTop(content, title, 18);

        TextView note = text(
                "Проверьте содержимое перед отправкой. Отчёт никуда не "
                        + "отправляется автоматически: копирование и системное "
                        + "меню «Поделиться» запускаются только вашими кнопками.",
                15,
                R.color.text_secondary
        );
        note.setLineSpacing(0f, 1.18f);
        addTop(content, note, 8);

        TextView privacy = text(
                "Всегда исключены: серийный номер, координаты, SSID, IP/MAC, "
                        + "токены, личные файлы и содержимое логов.",
                14,
                R.color.status_success
        );
        privacy.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addTop(content, privacy, 12);

        TextView choose = text("Что включить", 17, R.color.text_primary);
        choose.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addTop(content, choose, 18);

        for (Rc2SupportReportModel.Section section
                : Rc2SupportReportModel.Section.values()) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(section.getLabel());
            checkBox.setTextColor(getColor(R.color.text_primary));
            checkBox.setTextSize(15);
            checkBox.setChecked(true);
            checkBox.setOnCheckedChangeListener((ignored, checked) -> refreshPreview());
            choices.put(section, checkBox);
            addTop(content, checkBox, 4);
        }

        TextView previewTitle = text("Предпросмотр", 17, R.color.text_primary);
        previewTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addTop(content, previewTitle, 16);

        preview = text("", 13, R.color.text_secondary);
        preview.setTextIsSelectable(true);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setPadding(dp(14), dp(12), dp(14), dp(12));
        preview.setBackgroundResource(R.drawable.card_background);
        addTop(content, preview, 8);

        Button copy = primaryButton("Скопировать отчёт");
        copy.setOnClickListener(ignored -> copyReport());
        addTop(content, copy, 14);

        Button refresh = secondaryButton("Обновить данные");
        refresh.setOnClickListener(ignored -> refreshSnapshot());
        addTop(content, refresh, 8);

        Button share = secondaryButton("Поделиться через приложение…");
        share.setOnClickListener(ignored -> shareReport());
        addTop(content, share, 8);
        return scroll;
    }

    private void refreshPreview() {
        if (preview != null && snapshot != null) {
            preview.setText(currentReport());
        }
    }

    private void refreshSnapshot() {
        collectSnapshot(null, true);
    }

    private String currentReport() {
        Set<Rc2SupportReportModel.Section> selected =
                EnumSet.noneOf(Rc2SupportReportModel.Section.class);
        for (Map.Entry<Rc2SupportReportModel.Section, CheckBox> entry
                : choices.entrySet()) {
            if (entry.getValue().isChecked()) {
                selected.add(entry.getKey());
            }
        }
        return Rc2SupportReportModel.render(snapshot, selected);
    }

    private void copyReport() {
        collectSnapshot(this::copyCurrentReport, false);
    }

    private void copyCurrentReport() {
        ClipboardManager manager = (ClipboardManager) getSystemService(
                Context.CLIPBOARD_SERVICE
        );
        if (manager == null) {
            Toast.makeText(this, "Буфер обмена недоступен", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            manager.setPrimaryClip(ClipData.newPlainText(
                    "AlikDrone support report",
                    currentReport()
            ));
            Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show();
        } catch (SecurityException error) {
            Toast.makeText(
                    this,
                    "Android запретил доступ к буферу обмена",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void shareReport() {
        collectSnapshot(this::shareCurrentReport, false);
    }

    private void shareCurrentReport() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "Отчёт AlikDrone Hub");
        share.putExtra(Intent.EXTRA_TEXT, currentReport());
        try {
            startActivity(Intent.createChooser(share, "Отправить отчёт"));
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(
                    this,
                    "На пульте нет приложения для отправки отчёта",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void collectSnapshot(Runnable after, boolean showUpdatedToast) {
        int generation = ++collectionGeneration;
        if (preview != null) {
            preview.setText("Собираю данные пульта…");
        }
        try {
            Context appContext = getApplicationContext();
            reportExecutor.execute(() -> {
                Rc2SupportReportModel.Snapshot collected =
                        Rc2SupportReportCollector.collect(appContext);
                runOnUiThread(() -> {
                    if (destroyed
                            || isFinishing()
                            || isDestroyed()
                            || generation != collectionGeneration) {
                        return;
                    }
                    snapshot = collected;
                    refreshPreview();
                    if (showUpdatedToast) {
                        Toast.makeText(
                                this,
                                "Данные обновлены",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                    if (after != null) {
                        after.run();
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            if (preview != null) {
                preview.setText("Не удалось запустить сбор данных.");
            }
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(getColor(color));
        return view;
    }

    private Button primaryButton(String label) {
        Button button = secondaryButton(label);
        button.setBackgroundResource(R.drawable.device_info_button_background);
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(getColor(R.color.text_primary));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackgroundResource(R.drawable.button_secondary);
        return button;
    }

    private void addTop(LinearLayout parent, View child, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(marginDp);
        parent.addView(child, params);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
