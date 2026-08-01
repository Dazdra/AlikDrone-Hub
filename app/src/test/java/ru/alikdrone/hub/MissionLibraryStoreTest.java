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

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class MissionLibraryStoreTest {
    private Context context;

    @Before
    public void resetPreferences() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("mission_library_local", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void deduplicatesIdenticalContentBySha256() {
        MissionLibraryStore store = new MissionLibraryStore(context);
        KmlKmzValidator.Result valid = KmlKmzValidator.Result.valid(
                "Структурно корректен, не проверка безопасности полёта.",
                Collections.<String>emptyList(),
                1,
                10L
        );

        store.add("one.kml", "content://provider/one", valid, "aabbcc");
        store.add("copy.kml", "content://provider/two", valid, "AABBCC");

        assertEquals(1, store.list().size());
        assertEquals("aabbcc", store.list().get(0).sha256);
    }

    @Test
    public void corruptIndexIsNeverOverwrittenByAddOrRemove() {
        String corrupt = "{not-json";
        context.getSharedPreferences(
                "mission_library_local",
                Context.MODE_PRIVATE
        ).edit().putString("entries", corrupt).commit();
        MissionLibraryStore store = new MissionLibraryStore(context);
        KmlKmzValidator.Result valid = KmlKmzValidator.Result.valid(
                "Структурно корректен.",
                Collections.<String>emptyList(),
                1,
                10L
        );

        assertThrows(
                IllegalStateException.class,
                () -> store.add(
                        "route.kml",
                        "content://provider/route",
                        valid,
                        "aabbcc"
                )
        );
        assertThrows(
                IllegalStateException.class,
                () -> store.remove("missing")
        );
        assertEquals(
                corrupt,
                context.getSharedPreferences(
                        "mission_library_local",
                        Context.MODE_PRIVATE
                ).getString("entries", "")
        );
    }
}
