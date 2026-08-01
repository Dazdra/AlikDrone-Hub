package ru.alikdrone.hub;

import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class GitHubCatalogTest {
    private static final List<String> PUBLIC_IDS = Arrays.asList(
            "telegram",
            "discord",
            "youtube",
            "rutube",
            "chrome",
            "happ",
            "shareit",
            "windy"
    );
    private static final List<String> PUBLIC_URLS = Arrays.asList(
            "https://alik-drone.ru/downloads/apps/telegram.apk",
            "https://alik-drone.ru/downloads/apps/discord.xapk",
            "https://alik-drone.ru/downloads/apps/youtube.apk",
            "https://alik-drone.ru/downloads/apps/rutube.apk",
            "https://alik-drone.ru/downloads/apps/chrome.apk",
            "https://alik-drone.ru/downloads/apps/happ.apk",
            "https://alik-drone.ru/downloads/apps/shareit.apk",
            "https://alik-drone.ru/downloads/apps/windy.apk"
    );

    @Test
    public void exposesOnlyEightBroadlyUsefulApplications() {
        List<String> ids = AppCatalog.getEntries().stream()
                .map(AppCatalog.Entry::getId)
                .collect(Collectors.toList());

        assertEquals(PUBLIC_IDS, ids);
        assertTrue(AppCatalog.isPublicEdition());
        assertFalse(AppCatalog.allowsCustomDownloads());
        assertNull(AppCatalog.findById("astras"));
        assertNull(AppCatalog.findById("screenstream"));
        assertNull(AppCatalog.findById("quicktiles"));
    }

    @Test
    public void everyPublicEntryUsesTheAlikDroneMirrorAndPinnedIntegrityData() {
        List<AppCatalog.Entry> entries = AppCatalog.getEntries();
        for (int index = 0; index < entries.size(); index++) {
            AppCatalog.Entry entry = entries.get(index);
            assertTrue(entry.isDownloadable());
            assertEquals(
                    PUBLIC_URLS.get(index),
                    entry.getSourceUri().toString()
            );
            assertEquals("alik-drone.ru", entry.getSourceUri().getHost());
            assertTrue(entry.getSourceUri().getPath()
                    .startsWith("/downloads/apps/"));
            assertTrue(entry.getFileName().endsWith(".apk")
                    || entry.getFileName().endsWith(".xapk"));
            assertNotNull(entry.getExpectedSignerSha256());
            assertNotNull(entry.getExpectedFileSha256());
            assertTrue(entry.getExpectedVersionCode() > 0);
        }
    }

    @Test
    public void customRemoteDownloadsAreDisabled() {
        try {
            AppCatalog.createCustom(
                    "Unexpected",
                    "https://example.com/app.apk"
            );
            fail("GitHub edition must reject custom remote APK URLs");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("disabled"));
        }
    }

    @Test
    public void publicCatalogRowsOfferDownloadAction() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .setup()
                .get();
        Method addRow = MainActivity.class.getDeclaredMethod(
                "addServerCatalogRow",
                LinearLayout.class,
                LayoutInflater.class,
                AlertDialog.class,
                AppCatalog.Entry.class
        );
        addRow.setAccessible(true);

        for (String id : PUBLIC_IDS) {
            LinearLayout container = new LinearLayout(activity);
            addRow.invoke(
                    activity,
                    container,
                    LayoutInflater.from(activity),
                    null,
                    AppCatalog.findById(id)
            );
            Button action = container.findViewById(
                    R.id.catalog_item_action
            );
            assertEquals(
                    activity.getString(R.string.catalog_download),
                    action.getText().toString()
            );
        }
    }

    @Test
    public void catalogDoesNotRenderCustomRemoteUrlRow() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .setup()
                .get();
        LinearLayout container = new LinearLayout(activity);
        AlertDialog dialog = new AlertDialog.Builder(activity).create();
        Method render = MainActivity.class.getDeclaredMethod(
                "renderCatalogSections",
                LinearLayout.class,
                LayoutInflater.class,
                AlertDialog.class,
                List.class,
                List.class,
                java.util.Set.class
        );
        render.setAccessible(true);
        render.invoke(
                activity,
                container,
                LayoutInflater.from(activity),
                dialog,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptySet()
        );

        for (int index = 0; index < container.getChildCount(); index++) {
            View row = container.getChildAt(index);
            TextView name = row.findViewById(R.id.catalog_item_name);
            if (name != null) {
                assertFalse(activity.getString(R.string.catalog_custom_url)
                        .contentEquals(name.getText()));
            }
        }
    }
}
