package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public final class TaskbarCategoryTest {
    @Test
    public void idsAndResourcesAreStableAndUnique() {
        Set<String> ids = new HashSet<>();
        for (TaskbarCategory category : TaskbarCategory.values()) {
            ids.add(category.getId());
            assertNotEquals(0, category.getLabelResId());
            assertNotEquals(0, category.getColorResId());
        }
        assertEquals(TaskbarCategory.values().length, ids.size());
    }

    @Test
    public void fromIdRestoresAllCategoriesAndRejectsUnknownValues() {
        for (TaskbarCategory category : TaskbarCategory.values()) {
            assertEquals(
                    category,
                    TaskbarCategory.fromId("  " + category.getId().toUpperCase() + " ")
            );
        }
        assertNull(TaskbarCategory.fromId(null));
        assertNull(TaskbarCategory.fromId("future_value"));
    }

    @Test
    public void classifiesRepresentativeInstalledPackages() {
        assertEquals(
                TaskbarCategory.FLIGHT,
                TaskbarCategory.classify("ru.alikdrone.hub")
        );
        assertEquals(
                TaskbarCategory.FLIGHT,
                TaskbarCategory.classify("dji.go.v5")
        );
        assertEquals(
                TaskbarCategory.COMMUNICATION,
                TaskbarCategory.classify("org.telegram.messenger")
        );
        assertEquals(
                TaskbarCategory.COMMUNICATION,
                TaskbarCategory.classify("com.discord")
        );
        assertEquals(
                TaskbarCategory.MAPS_WEATHER,
                TaskbarCategory.classify("com.windyty.android")
        );
        assertEquals(
                TaskbarCategory.MEDIA_STREAMING,
                TaskbarCategory.classify("com.google.android.youtube")
        );
        assertEquals(
                TaskbarCategory.FILES_TOOLS,
                TaskbarCategory.classify("org.cromite.cromite")
        );
        assertEquals(
                TaskbarCategory.FILES_TOOLS,
                TaskbarCategory.classify("com.lenovo.anyshare.gps")
        );
        assertEquals(
                TaskbarCategory.OTHER,
                TaskbarCategory.classify("com.vendor.special")
        );
    }

    @Test
    public void explicitOverrideWinsAndInvalidOverrideUsesAutomaticCategory() {
        assertEquals(
                TaskbarCategory.FLIGHT,
                TaskbarCategory.resolve("com.discord", "flight")
        );
        assertEquals(
                TaskbarCategory.OTHER,
                TaskbarCategory.resolve("com.discord", "other")
        );
        assertEquals(
                TaskbarCategory.COMMUNICATION,
                TaskbarCategory.resolve("com.discord", null)
        );
        assertEquals(
                TaskbarCategory.COMMUNICATION,
                TaskbarCategory.resolve("com.discord", "corrupt_value")
        );
    }
}
