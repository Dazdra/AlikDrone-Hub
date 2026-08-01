package ru.alikdrone.hub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Device-local journal. No network, account, telemetry or cloud synchronization. */
public final class MaintenanceJournalStore {
    private static final String PREFS = "maintenance_journal_local";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 250;
    private final SharedPreferences preferences;

    public MaintenanceJournalStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized boolean add(MaintenanceEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Entry is required");
        }
        requireReadableJournal();
        List<MaintenanceEntry> entries = list();
        entries.add(entry);
        Collections.sort(entries, new Comparator<MaintenanceEntry>() {
            @Override
            public int compare(MaintenanceEntry left, MaintenanceEntry right) {
                return Long.compare(right.createdAt, left.createdAt);
            }
        });
        boolean oldestEntryRemoved = false;
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
            oldestEntryRemoved = true;
        }
        save(entries);
        return oldestEntryRemoved;
    }

    public synchronized List<MaintenanceEntry> list() {
        String raw = preferences.getString(KEY_ENTRIES, "[]");
        List<MaintenanceEntry> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) {
                    continue;
                }
                MaintenanceEntry.Provenance provenance;
                try {
                    provenance = MaintenanceEntry.Provenance.valueOf(
                            object.optString("provenance", "MANUAL")
                    );
                } catch (IllegalArgumentException invalid) {
                    provenance = MaintenanceEntry.Provenance.MANUAL;
                }
                try {
                    result.add(new MaintenanceEntry(
                            object.optString("id", ""),
                            object.optLong("createdAt", 0L),
                            MaintenanceEntry.Asset.fromStorage(object.optString("asset", "")),
                            object.optString("action", ""),
                            object.optString("note", ""),
                            provenance,
                            object.optString("sourceLabel", "")
                    ));
                } catch (IllegalArgumentException malformedEntry) {
                    // Skip an invalid local record rather than crashing the journal.
                }
            }
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
        Collections.sort(result, new Comparator<MaintenanceEntry>() {
            @Override
            public int compare(MaintenanceEntry left, MaintenanceEntry right) {
                return Long.compare(right.createdAt, left.createdAt);
            }
        });
        return result;
    }

    public synchronized void remove(String id) {
        requireReadableJournal();
        List<MaintenanceEntry> entries = list();
        for (int index = entries.size() - 1; index >= 0; index--) {
            if (entries.get(index).id.equals(id)) {
                entries.remove(index);
            }
        }
        save(entries);
    }

    boolean isJournalReadable() {
        try {
            new JSONArray(preferences.getString(KEY_ENTRIES, "[]"));
            return true;
        } catch (JSONException invalid) {
            return false;
        }
    }

    private void requireReadableJournal() {
        if (!isJournalReadable()) {
            throw new IllegalStateException(
                    "Local maintenance journal is unreadable; refusing to overwrite it"
            );
        }
    }

    private void save(List<MaintenanceEntry> entries) {
        JSONArray array = new JSONArray();
        for (MaintenanceEntry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                object.put("id", entry.id);
                object.put("createdAt", entry.createdAt);
                object.put("asset", entry.asset.name());
                object.put("action", entry.action);
                object.put("note", entry.note);
                object.put("provenance", entry.provenance.name());
                object.put("sourceLabel", entry.sourceLabel);
                array.put(object);
            } catch (JSONException impossible) {
                throw new IllegalStateException("Unable to encode local journal", impossible);
            }
        }
        if (!preferences.edit().putString(KEY_ENTRIES, array.toString()).commit()) {
            throw new IllegalStateException("Unable to persist local maintenance journal");
        }
    }
}
