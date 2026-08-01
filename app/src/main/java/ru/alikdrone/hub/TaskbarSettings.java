package ru.alikdrone.hub;

import java.util.Locale;
import java.util.Objects;

/**
 * Validated, persistence-friendly Taskbar layout settings.
 */
public final class TaskbarSettings {
    public static final int MIN_ROWS = 1;
    public static final int MAX_ROWS = 4;
    public static final int MIN_COLUMNS = 3;
    public static final int MAX_COLUMNS = 8;
    public static final int DEFAULT_ROWS = 2;
    public static final int DEFAULT_COLUMNS = 6;

    public enum SortMode {
        CATEGORIES("categories"),
        ALPHABETICAL("alphabetical");

        private final String id;

        SortMode(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static SortMode fromId(String id) {
            String normalized = normalize(id);
            for (SortMode mode : values()) {
                if (mode.id.equals(normalized)) {
                    return mode;
                }
            }
            return CATEGORIES;
        }
    }

    public enum Anchor {
        FREE("free"),
        TOP_LEFT("top_left"),
        TOP_RIGHT("top_right"),
        BOTTOM_LEFT("bottom_left"),
        BOTTOM_RIGHT("bottom_right");

        private final String id;

        Anchor(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static Anchor fromId(String id) {
            String normalized = normalize(id);
            for (Anchor anchor : values()) {
                if (anchor.id.equals(normalized)) {
                    return anchor;
                }
            }
            return FREE;
        }
    }

    private final int rows;
    private final int columns;
    private final SortMode sortMode;
    private final Anchor anchor;

    public TaskbarSettings(
            int rows,
            int columns,
            SortMode sortMode,
            Anchor anchor
    ) {
        this.rows = clamp(rows, MIN_ROWS, MAX_ROWS);
        this.columns = clamp(columns, MIN_COLUMNS, MAX_COLUMNS);
        this.sortMode = sortMode == null ? SortMode.CATEGORIES : sortMode;
        this.anchor = anchor == null ? Anchor.FREE : anchor;
    }

    public static TaskbarSettings defaults() {
        return new TaskbarSettings(
                DEFAULT_ROWS,
                DEFAULT_COLUMNS,
                SortMode.CATEGORIES,
                Anchor.FREE
        );
    }

    public static TaskbarSettings fromPersistedValues(
            int rows,
            int columns,
            String sortModeId,
            String anchorId
    ) {
        return new TaskbarSettings(
                rows,
                columns,
                SortMode.fromId(sortModeId),
                Anchor.fromId(anchorId)
        );
    }

    public int getRows() {
        return rows;
    }

    public int getColumns() {
        return columns;
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    public Anchor getAnchor() {
        return anchor;
    }

    public int getCapacity() {
        return rows * columns;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof TaskbarSettings)) {
            return false;
        }
        TaskbarSettings that = (TaskbarSettings) object;
        return rows == that.rows
                && columns == that.columns
                && sortMode == that.sortMode
                && anchor == that.anchor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rows, columns, sortMode, anchor);
    }

    @Override
    public String toString() {
        return "TaskbarSettings{"
                + "rows=" + rows
                + ", columns=" + columns
                + ", sortMode=" + sortMode.id
                + ", anchor=" + anchor.id
                + '}';
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.US);
    }
}
