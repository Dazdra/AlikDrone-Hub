package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class MaintenanceJournalStoreTest {
    private Context context;

    @Before
    public void resetPreferences() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("maintenance_journal_local", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void journalRetainsOnlyBoundedRecentHistory() {
        MaintenanceJournalStore store = new MaintenanceJournalStore(context);
        boolean oldestRemoved = false;
        for (int index = 0; index < 260; index++) {
            oldestRemoved = store.add(MaintenanceEntry.manual(
                    MaintenanceEntry.Asset.OTHER,
                    "Запись " + index,
                    ""
            ));
        }

        assertEquals(250, store.list().size());
        assertEquals(true, oldestRemoved);
    }

    @Test
    public void corruptJournalIsNeverOverwrittenByAddOrRemove() {
        String corrupt = "[broken";
        context.getSharedPreferences(
                "maintenance_journal_local",
                Context.MODE_PRIVATE
        ).edit().putString("entries", corrupt).commit();
        MaintenanceJournalStore store = new MaintenanceJournalStore(context);

        assertThrows(
                IllegalStateException.class,
                () -> store.add(MaintenanceEntry.manual(
                        MaintenanceEntry.Asset.CONTROLLER,
                        "Проверка",
                        ""
                ))
        );
        assertThrows(
                IllegalStateException.class,
                () -> store.remove("missing")
        );
        assertEquals(
                corrupt,
                context.getSharedPreferences(
                        "maintenance_journal_local",
                        Context.MODE_PRIVATE
                ).getString("entries", "")
        );
    }
}
