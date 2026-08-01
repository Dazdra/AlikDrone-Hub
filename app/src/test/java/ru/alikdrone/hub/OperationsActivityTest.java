package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class OperationsActivityTest {
    @Test
    public void opensEveryOperationsModuleFromItsButton() {
        assertButtonOpens(R.id.button_operations_web_tools, WebToolsActivity.class);
        assertButtonOpens(
                R.id.button_operations_support_report,
                SupportReportActivity.class
        );
        assertButtonOpens(
                R.id.button_operations_files,
                ControllerFilesActivity.class
        );
        assertButtonOpens(
                R.id.button_operations_missions,
                MissionLibraryActivity.class
        );
        assertButtonOpens(
                R.id.button_operations_maintenance,
                MaintenanceActivity.class
        );
    }

    private void assertButtonOpens(
            int buttonId,
            Class<? extends Activity> expectedActivity
    ) {
        OperationsActivity activity = Robolectric.buildActivity(
                OperationsActivity.class
        ).setup().get();
        activity.findViewById(buttonId).performClick();

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertEquals(
                expectedActivity.getName(),
                started.getComponent().getClassName()
        );
        activity.finish();
    }
}
