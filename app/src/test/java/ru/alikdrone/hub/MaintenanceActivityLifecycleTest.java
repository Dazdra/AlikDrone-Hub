package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.widget.EditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class MaintenanceActivityLifecycleTest {
    private Context context;

    @Before
    public void resetJournal() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(
                        "maintenance_journal_local",
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void acceptedSaveCompletesAfterActivityIsDestroyed()
            throws Exception {
        ActivityController<MaintenanceActivity> controller =
                Robolectric.buildActivity(MaintenanceActivity.class).setup();
        MaintenanceActivity activity = controller.get();
        ((EditText) activity.findViewById(R.id.maintenance_action_input))
                .setText("Проверка пропеллеров");

        activity.findViewById(R.id.button_maintenance_add).performClick();
        controller.pause().stop().destroy();

        MaintenanceJournalStore store = new MaintenanceJournalStore(context);
        long deadline = System.currentTimeMillis() + 2_000L;
        while (store.list().isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        assertEquals(1, store.list().size());
    }
}
