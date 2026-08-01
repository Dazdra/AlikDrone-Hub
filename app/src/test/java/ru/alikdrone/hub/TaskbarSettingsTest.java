package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TaskbarSettingsTest {
    @Test
    public void defaultsAreSafeTwoBySixCategoryGridWithFreeAnchor() {
        TaskbarSettings settings = TaskbarSettings.defaults();

        assertEquals(2, settings.getRows());
        assertEquals(6, settings.getColumns());
        assertEquals(12, settings.getCapacity());
        assertEquals(
                TaskbarSettings.SortMode.CATEGORIES,
                settings.getSortMode()
        );
        assertEquals(TaskbarSettings.Anchor.FREE, settings.getAnchor());
    }

    @Test
    public void constructorClampsRowsAndColumns() {
        TaskbarSettings below = new TaskbarSettings(
                -10,
                -20,
                TaskbarSettings.SortMode.ALPHABETICAL,
                TaskbarSettings.Anchor.TOP_LEFT
        );
        assertEquals(1, below.getRows());
        assertEquals(3, below.getColumns());
        assertEquals(3, below.getCapacity());

        TaskbarSettings above = new TaskbarSettings(
                100,
                100,
                TaskbarSettings.SortMode.ALPHABETICAL,
                TaskbarSettings.Anchor.BOTTOM_RIGHT
        );
        assertEquals(4, above.getRows());
        assertEquals(8, above.getColumns());
        assertEquals(32, above.getCapacity());
    }

    @Test
    public void nullAndUnknownPersistedValuesUseDefaults() {
        TaskbarSettings direct = new TaskbarSettings(2, 6, null, null);
        assertEquals(
                TaskbarSettings.SortMode.CATEGORIES,
                direct.getSortMode()
        );
        assertEquals(TaskbarSettings.Anchor.FREE, direct.getAnchor());

        TaskbarSettings restored = TaskbarSettings.fromPersistedValues(
                2,
                6,
                "future_sort",
                "future_anchor"
        );
        assertEquals(
                TaskbarSettings.SortMode.CATEGORIES,
                restored.getSortMode()
        );
        assertEquals(TaskbarSettings.Anchor.FREE, restored.getAnchor());
    }

    @Test
    public void enumIdsRoundTripWithoutDependingOnOrdinals() {
        for (TaskbarSettings.SortMode mode
                : TaskbarSettings.SortMode.values()) {
            assertEquals(
                    mode,
                    TaskbarSettings.SortMode.fromId(mode.getId().toUpperCase())
            );
        }
        for (TaskbarSettings.Anchor anchor : TaskbarSettings.Anchor.values()) {
            assertEquals(
                    anchor,
                    TaskbarSettings.Anchor.fromId(anchor.getId().toUpperCase())
            );
        }
    }

    @Test
    public void valueSemanticsCompareValidatedSettings() {
        TaskbarSettings first = new TaskbarSettings(
                2,
                6,
                TaskbarSettings.SortMode.CATEGORIES,
                TaskbarSettings.Anchor.TOP_RIGHT
        );
        TaskbarSettings second = TaskbarSettings.fromPersistedValues(
                2,
                6,
                "categories",
                "top_right"
        );

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}
