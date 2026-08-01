package ru.alikdrone.hub;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A small launcher for reviewed external web tools.
 *
 * <p>No page is embedded and no DJI/account credentials pass through Hub.</p>
 */
public final class WebToolsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));
        setContentView(buildContent());
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.background));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        Button back = secondaryButton("← Назад");
        back.setOnClickListener(ignored -> finish());
        content.addView(back, widthWrap());

        TextView title = text("Веб-инструменты пилота", 25, R.color.text_primary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addWithTopMargin(content, title, 18);

        TextView note = text(
                "Hub открывает только проверенные HTTPS-адреса. "
                        + "Пароли и данные аккаунтов не проходят через AlikDrone. "
                        + "Метка совместимости показывает реальную роль сервиса.",
                15,
                R.color.text_secondary
        );
        note.setLineSpacing(0f, 1.18f);
        addWithTopMargin(content, note, 8);

        for (Rc2WebToolCatalog.Entry entry : Rc2WebToolCatalog.entries()) {
            addWithTopMargin(content, buildToolCard(entry), 12);
        }
        return scroll;
    }

    private View buildToolCard(Rc2WebToolCatalog.Entry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.card_background);

        TextView title = text(entry.getTitle(), 18, R.color.text_primary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);

        TextView labels = text(
                entry.getPlatform() + " · " + entry.getCompatibility().getLabel(),
                13,
                compatibilityColor(entry.getCompatibility())
        );
        labels.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addWithTopMargin(card, labels, 5);

        TextView description = text(entry.getDescription(), 14, R.color.text_secondary);
        description.setLineSpacing(0f, 1.15f);
        addWithTopMargin(card, description, 8);

        Button open = secondaryButton("Открыть официальный сайт →");
        open.setOnClickListener(ignored -> openEntry(entry));
        addWithTopMargin(card, open, 12);
        return card;
    }

    private int compatibilityColor(Rc2WebToolCatalog.Compatibility compatibility) {
        if (compatibility == Rc2WebToolCatalog.Compatibility.RC2_HANDOFF) {
            return R.color.status_success;
        }
        if (compatibility == Rc2WebToolCatalog.Compatibility.ENTERPRISE_ONLY
                || compatibility
                == Rc2WebToolCatalog.Compatibility.DESKTOP_PROCESSING
                || compatibility
                == Rc2WebToolCatalog.Compatibility.DESKTOP_PLANNING) {
            return R.color.status_warning;
        }
        return R.color.brand;
    }

    private void openEntry(Rc2WebToolCatalog.Entry entry) {
        if (!Rc2WebToolCatalog.isAllowed(entry)) {
            Toast.makeText(this, "Ссылка заблокирована", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(entry.getUrl()));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(
                    this,
                    "На пульте нет приложения для открытия HTTPS-ссылки",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(getColor(color));
        return view;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(getColor(R.color.text_primary));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.button_secondary);
        button.setMinHeight(dp(44));
        button.setPadding(dp(14), 0, dp(14), 0);
        return button;
    }

    private void addWithTopMargin(LinearLayout parent, View child, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(marginDp);
        parent.addView(child, params);
    }

    private LinearLayout.LayoutParams widthWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
