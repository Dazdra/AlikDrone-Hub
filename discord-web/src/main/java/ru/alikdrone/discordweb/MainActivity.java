package ru.alikdrone.discordweb;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final Uri DISCORD_WEB_URI = Uri.parse("https://discord.com/app");
    private static final String CROMITE_PACKAGE = "org.cromite.cromite";

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        findViewById(R.id.open_button).setOnClickListener(view -> openDiscordWeb());

        openDiscordWeb();
    }

    private void openDiscordWeb() {
        statusText.setText(R.string.status_opening);

        if (startBrowser(CROMITE_PACKAGE) || startBrowser(null)) {
            statusText.setText(R.string.status_return_hint);
            return;
        }

        statusText.setText(R.string.status_no_browser);
    }

    private boolean startBrowser(String packageName) {
        Intent intent = new Intent(Intent.ACTION_VIEW, DISCORD_WEB_URI);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        if (packageName != null) {
            intent.setPackage(packageName);
        }

        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }
}
