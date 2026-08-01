package ru.alikdrone.hub;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.widget.Spinner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class TaskbarSettingsPresentationTest {
    @Before
    public void clearSettings() {
        Application application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                        SwitcherController.PREFERENCES_NAME,
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .commit();
        application.createDeviceProtectedStorageContext()
                .getSharedPreferences(
                        "alikdrone_switcher_direct_boot",
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void settingsDialogPersistsGridSortAndCorner() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        activity.findViewById(R.id.button_switcher_settings).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);

        Spinner rows = dialog.findViewById(R.id.spinner_taskbar_rows);
        Spinner columns = dialog.findViewById(R.id.spinner_taskbar_columns);
        Spinner sort = dialog.findViewById(R.id.spinner_taskbar_sort);
        Spinner anchor = dialog.findViewById(R.id.spinner_taskbar_anchor);
        assertNotNull(rows);
        assertNotNull(columns);
        assertNotNull(sort);
        assertNotNull(anchor);

        rows.setSelection(2);      // 3 rows
        columns.setSelection(4);   // 7 columns
        sort.setSelection(1);      // alphabetical
        anchor.setSelection(4);    // bottom-right
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(
                new TaskbarSettings(
                        3,
                        7,
                        TaskbarSettings.SortMode.ALPHABETICAL,
                        TaskbarSettings.Anchor.BOTTOM_RIGHT
                ),
                SwitcherController.readTaskbarSettings(activity)
        );
        controller.pause().stop().destroy();
    }
}
