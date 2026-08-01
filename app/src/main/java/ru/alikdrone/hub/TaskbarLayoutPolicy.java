package ru.alikdrone.hub;

/**
 * Pure Taskbar grid calculations shared by the overlay and its unit tests.
 */
public final class TaskbarLayoutPolicy {
    private TaskbarLayoutPolicy() {
    }

    /**
     * Returns whether an extra "all applications" slot is required.
     */
    public static boolean hasOverflow(
            int totalApplicationCount,
            TaskbarSettings settings
    ) {
        return hasOverflow(
                totalApplicationCount,
                requireSettings(settings).getCapacity()
        );
    }

    public static boolean hasOverflow(
            int totalApplicationCount,
            int capacity
    ) {
        return nonNegative(totalApplicationCount) > nonNegative(capacity);
    }

    /**
     * Number of real applications to render. When overflowing, one grid cell
     * is reserved for the explicit "all applications" action.
     */
    public static int visibleApplicationCount(
            int totalApplicationCount,
            TaskbarSettings settings
    ) {
        return visibleApplicationCount(
                totalApplicationCount,
                requireSettings(settings).getCapacity()
        );
    }

    public static int visibleApplicationCount(
            int totalApplicationCount,
            int capacity
    ) {
        int total = nonNegative(totalApplicationCount);
        int safeCapacity = nonNegative(capacity);
        if (!hasOverflow(total, safeCapacity)) {
            return Math.min(total, safeCapacity);
        }
        return Math.min(total, Math.max(0, safeCapacity - 1));
    }

    /**
     * Total occupied slots, including the overflow action when present.
     */
    public static int occupiedSlotCount(
            int totalApplicationCount,
            TaskbarSettings settings
    ) {
        int visible = visibleApplicationCount(totalApplicationCount, settings);
        return visible + (hasOverflow(totalApplicationCount, settings) ? 1 : 0);
    }

    public static int gridRows(int slotCount, int columns) {
        int safeSlots = nonNegative(slotCount);
        int safeColumns = Math.max(1, columns);
        if (safeSlots == 0) {
            return 0;
        }
        return (int) Math.min(
                Integer.MAX_VALUE,
                ((long) safeSlots + safeColumns - 1L) / safeColumns
        );
    }

    public static int gridColumns(int slotCount, int columns) {
        int safeSlots = nonNegative(slotCount);
        if (safeSlots == 0) {
            return 0;
        }
        return Math.min(safeSlots, Math.max(1, columns));
    }

    /**
     * Saturating height multiplication. Invalid/negative inputs result in 0,
     * while extreme values cannot wrap into a negative WindowManager size.
     */
    public static int expandedHeight(int buttonSize, int usedRows) {
        return safeProduct(buttonSize, usedRows);
    }

    /**
     * Saturating width multiplication. Invalid/negative inputs result in 0,
     * while extreme values cannot wrap into a negative WindowManager size.
     */
    public static int gridWidth(int buttonSize, int usedColumns) {
        return safeProduct(buttonSize, usedColumns);
    }

    /**
     * Clamps an overlay dimension to the usable screen axis.
     */
    public static int clampToAvailableAxis(
            int desiredSize,
            int availableSize
    ) {
        return Math.min(nonNegative(desiredSize), nonNegative(availableSize));
    }

    private static int safeProduct(int first, int second) {
        long product = (long) nonNegative(first) * nonNegative(second);
        return product > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) product;
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static TaskbarSettings requireSettings(
            TaskbarSettings settings
    ) {
        if (settings == null) {
            throw new IllegalArgumentException("settings must not be null");
        }
        return settings;
    }
}
