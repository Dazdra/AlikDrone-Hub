package ru.alikdrone.hub;

import java.util.Locale;
import java.util.UUID;

/** Immutable local maintenance note with an explicit source/provenance. */
public final class MaintenanceEntry {
    private static final int MAX_ACTION_LENGTH = 160;
    private static final int MAX_NOTE_LENGTH = 1000;
    private static final int MAX_SOURCE_LENGTH = 120;
    public enum Asset {
        CONTROLLER("Пульт DJI RC 2"),
        AIRCRAFT("Дрон"),
        FLIGHT_BATTERY("Полётная батарея"),
        PROPELLERS("Пропеллеры"),
        OTHER("Другое");

        public final String label;

        Asset(String label) {
            this.label = label;
        }

        public static Asset fromStorage(String value) {
            if (value == null) {
                return OTHER;
            }
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                return OTHER;
            }
        }
    }

    public enum Provenance {
        MANUAL("введено пользователем вручную"),
        IMPORTED("импортировано");

        public final String label;

        Provenance(String label) {
            this.label = label;
        }
    }

    public final String id;
    public final long createdAt;
    public final Asset asset;
    public final String action;
    public final String note;
    public final Provenance provenance;
    public final String sourceLabel;

    MaintenanceEntry(
            String id,
            long createdAt,
            Asset asset,
            String action,
            String note,
            Provenance provenance,
            String sourceLabel
    ) {
        this.id = id == null ? "" : id;
        this.createdAt = Math.max(0L, createdAt);
        this.asset = asset == null ? Asset.OTHER : asset;
        this.action = normalized(action, MAX_ACTION_LENGTH);
        this.note = normalized(note, MAX_NOTE_LENGTH);
        this.provenance = provenance == null ? Provenance.MANUAL : provenance;
        this.sourceLabel = normalized(sourceLabel, MAX_SOURCE_LENGTH);
        if (this.provenance == Provenance.IMPORTED && this.sourceLabel.isEmpty()) {
            throw new IllegalArgumentException("Imported records require a source label");
        }
    }

    public static MaintenanceEntry manual(Asset asset, String action, String note) {
        if (action == null || action.trim().isEmpty()) {
            throw new IllegalArgumentException("Action is required");
        }
        return new MaintenanceEntry(
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                asset,
                action,
                note,
                Provenance.MANUAL,
                "пользователь"
        );
    }

    public String provenanceDisplay() {
        if (provenance == Provenance.MANUAL) {
            return "Источник: " + provenance.label;
        }
        return "Источник: " + provenance.label + " · " + sourceLabel;
    }

    public String assetCaution() {
        if (asset == Asset.CONTROLLER) {
            return "Запись относится к батарее/состоянию пульта, не к полётной батарее.";
        }
        if (asset == Asset.FLIGHT_BATTERY) {
            return "Данные полётной батареи введены вручную и не считаны с пульта.";
        }
        return "";
    }

    private static String normalized(String value, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximumLength
                ? normalized
                : normalized.substring(0, maximumLength);
    }
}
