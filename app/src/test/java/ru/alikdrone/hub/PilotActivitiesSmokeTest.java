package ru.alikdrone.hub;

import static org.junit.Assert.assertNotNull;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class PilotActivitiesSmokeTest {
    @Before
    public void resetLocalState() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("controller_files_saf", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        context.getSharedPreferences("mission_library_local", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        context.getSharedPreferences("maintenance_journal_local", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        context.getSharedPreferences("preflight_checklist_local", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void allPilotToolActivitiesCreateAndDestroy() {
        assertActivityStarts(OperationsActivity.class);
        assertActivityStarts(WebToolsActivity.class);
        assertActivityStarts(SupportReportActivity.class);
        assertActivityStarts(ControllerFilesActivity.class);
        assertActivityStarts(MissionLibraryActivity.class);
        assertActivityStarts(MaintenanceActivity.class);
    }

    private <T extends android.app.Activity> void assertActivityStarts(Class<T> type) {
        ActivityController<T> controller = Robolectric.buildActivity(type).setup();
        assertNotNull(controller.get());
        controller.pause().stop().destroy();
    }
}
