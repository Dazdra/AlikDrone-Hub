package ru.alikdrone.hub;

import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class MessagingWarningPresentationTest {
    @Test
    public void telegramDownloadShowsRedStabilityWarning() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        Method confirmDownload = MainActivity.class.getDeclaredMethod(
                "confirmDownload",
                AppCatalog.Entry.class
        );
        confirmDownload.setAccessible(true);
        confirmDownload.invoke(activity, AppCatalog.findById("telegram"));

        assertMessagingWarning(activity, "Telegram");
        controller.pause().stop().destroy();
    }

    @Test
    public void discordInstallShowsRedStabilityWarning() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        LinearLayout container = new LinearLayout(activity);

        Method addRow = MainActivity.class.getDeclaredMethod(
                "addDownloadedCatalogRow",
                LinearLayout.class,
                LayoutInflater.class,
                AlertDialog.class,
                ApkInventoryScanner.Item.class
        );
        addRow.setAccessible(true);
        addRow.invoke(
                activity,
                container,
                LayoutInflater.from(activity),
                null,
                downloadedItem("com.discord", "Discord")
        );
        Button install = container.getChildAt(0).findViewById(
                R.id.catalog_item_action
        );
        install.performClick();

        assertMessagingWarning(activity, "Discord");
        controller.pause().stop().destroy();
    }

    private static void assertMessagingWarning(
            MainActivity activity,
            String appName
    ) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        TextView message = dialog.findViewById(android.R.id.message);
        assertNotNull(message);
        assertTrue(message.getText().toString().contains(appName));
        assertTrue(message.getText().toString().contains("отключить уведомления"));
    }

    private static ApkInventoryScanner.Item downloadedItem(
            String packageName,
            String displayName
    ) throws Exception {
        Constructor<?> constructor =
                ApkInventoryScanner.Item.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (ApkInventoryScanner.Item) constructor.newInstance(
                null,
                packageName + "-download",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ApkSigningIdentity.singleSignerHistory(Collections.singletonList(
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                )),
                packageName,
                displayName,
                "1.0",
                1L,
                null,
                null,
                displayName + ".apk",
                32_768L,
                1L,
                null
        );
    }
}
