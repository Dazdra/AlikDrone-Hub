package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TaskbarLayoutPolicyTest {
    private final TaskbarSettings twoBySix = TaskbarSettings.defaults();

    @Test
    public void exactCapacityHasNoOverflowAndKeepsEveryApplication() {
        assertFalse(TaskbarLayoutPolicy.hasOverflow(12, twoBySix));
        assertEquals(
                12,
                TaskbarLayoutPolicy.visibleApplicationCount(12, twoBySix)
        );
        assertEquals(
                12,
                TaskbarLayoutPolicy.occupiedSlotCount(12, twoBySix)
        );
    }

    @Test
    public void overflowReservesOneCellForAllApplicationsAction() {
        assertTrue(TaskbarLayoutPolicy.hasOverflow(20, twoBySix));
        assertEquals(
                11,
                TaskbarLayoutPolicy.visibleApplicationCount(20, twoBySix)
        );
        assertEquals(
                11,
                TaskbarLayoutPolicy.visibleApplicationCount(20, 12)
        );
        assertEquals(
                12,
                TaskbarLayoutPolicy.occupiedSlotCount(20, twoBySix)
        );
    }

    @Test
    public void thirtyApplicationsFitOnlyInLargeFourByEightPreset() {
        TaskbarSettings compact = new TaskbarSettings(
                1,
                3,
                TaskbarSettings.SortMode.CATEGORIES,
                TaskbarSettings.Anchor.FREE
        );
        assertTrue(TaskbarLayoutPolicy.hasOverflow(30, compact));
        assertEquals(
                2,
                TaskbarLayoutPolicy.visibleApplicationCount(30, compact)
        );
        assertEquals(
                3,
                TaskbarLayoutPolicy.occupiedSlotCount(30, compact)
        );

        TaskbarSettings large = new TaskbarSettings(
                4,
                8,
                TaskbarSettings.SortMode.CATEGORIES,
                TaskbarSettings.Anchor.FREE
        );
        assertFalse(TaskbarLayoutPolicy.hasOverflow(30, large));
        assertEquals(
                30,
                TaskbarLayoutPolicy.visibleApplicationCount(30, large)
        );
        assertEquals(4, TaskbarLayoutPolicy.gridRows(30, 8));
        assertEquals(8, TaskbarLayoutPolicy.gridColumns(30, 8));
    }

    @Test
    public void negativeCountsAreSafelyTreatedAsEmpty() {
        assertFalse(TaskbarLayoutPolicy.hasOverflow(-1, twoBySix));
        assertEquals(
                0,
                TaskbarLayoutPolicy.visibleApplicationCount(-1, twoBySix)
        );
        assertEquals(
                0,
                TaskbarLayoutPolicy.occupiedSlotCount(-1, twoBySix)
        );
    }

    @Test
    public void gridUsesConfiguredColumnLimitAndOnlyRequiredRows() {
        assertEquals(0, TaskbarLayoutPolicy.gridRows(0, 6));
        assertEquals(0, TaskbarLayoutPolicy.gridColumns(0, 6));
        assertEquals(1, TaskbarLayoutPolicy.gridRows(5, 6));
        assertEquals(5, TaskbarLayoutPolicy.gridColumns(5, 6));
        assertEquals(2, TaskbarLayoutPolicy.gridRows(12, 6));
        assertEquals(6, TaskbarLayoutPolicy.gridColumns(12, 6));
    }

    @Test
    public void invalidColumnCountCannotDivideByZero() {
        assertEquals(5, TaskbarLayoutPolicy.gridRows(5, 0));
        assertEquals(1, TaskbarLayoutPolicy.gridColumns(5, 0));
    }

    @Test
    public void dimensionsMultiplyAndSaturateWithoutIntegerOverflow() {
        assertEquals(104, TaskbarLayoutPolicy.expandedHeight(52, 2));
        assertEquals(312, TaskbarLayoutPolicy.gridWidth(52, 6));
        assertEquals(0, TaskbarLayoutPolicy.gridWidth(-52, 6));
        assertEquals(
                Integer.MAX_VALUE,
                TaskbarLayoutPolicy.gridWidth(Integer.MAX_VALUE, 8)
        );
    }

    @Test
    public void dimensionsCanBeClampedToAvailableScreenAxis() {
        assertEquals(
                280,
                TaskbarLayoutPolicy.clampToAvailableAxis(312, 280)
        );
        assertEquals(
                104,
                TaskbarLayoutPolicy.clampToAvailableAxis(104, 280)
        );
        assertEquals(
                0,
                TaskbarLayoutPolicy.clampToAvailableAxis(104, -1)
        );
    }
}
