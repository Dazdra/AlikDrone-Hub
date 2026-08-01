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
import java.util.Locale;
import java.util.UUID;

/** Local-only index of user-selected KML/KMZ content URIs. */
public final class MissionLibraryStore {
    private static final String PREFS = "mission_library_local";
    private static final String KEY_ENTRIES = "entries";
    private final SharedPreferences preferences;

    public MissionLibraryStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized Entry add(
            String displayName,
            String contentUri,
            KmlKmzValidator.Result validation,
            String sha256
    ) {
        if (validation == null || !validation.valid) {
            throw new IllegalArgumentException("Only structurally valid missions can be indexed");
        }
        requireReadableIndex();
        String normalizedSha256 = sha256 == null
                ? ""
                : sha256.toLowerCase(Locale.ROOT);
        List<Entry> entries = list();
        for (Entry existing : entries) {
            if (!normalizedSha256.isEmpty()
                    && normalizedSha256.equalsIgnoreCase(existing.sha256)) {
                return existing;
            }
        }
        Entry entry = new Entry(
                UUID.randomUUID().toString(),
                displayName,
                contentUri,
                System.currentTimeMillis(),
                validation.summary,
                validation.warnings,
                normalizedSha256
        );
        entries.add(entry);
        save(entries);
        return entry;
    }

    public synchronized List<Entry> list() {
        List<Entry> result = new ArrayList<>();
        String raw = preferences.getString(KEY_ENTRIES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                JSONArray warningArray = object.optJSONArray("warnings");
                List<String> warnings = new ArrayList<>();
                if (warningArray != null) {
                    for (int warningIndex = 0; warningIndex < warningArray.length(); warningIndex++) {
                        String warning = warningArray.optString(warningIndex, "");
                        if (!warning.isEmpty()) {
                            warnings.add(warning);
                        }
                    }
                }
                result.add(new Entry(
                        object.optString("id", ""),
                        object.optString("name", ""),
                        object.optString("uri", ""),
                        object.optLong("importedAt", 0L),
                        object.optString("summary", ""),
                        warnings,
                        object.optString("sha256", "")
                ));
            }
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
        Collections.sort(result, new Comparator<Entry>() {
            @Override
            public int compare(Entry left, Entry right) {
                return Long.compare(right.importedAt, left.importedAt);
            }
        });
        return result;
    }

    public synchronized boolean isIndexReadable() {
        try {
            new JSONArray(preferences.getString(KEY_ENTRIES, "[]"));
            return true;
        } catch (JSONException invalid) {
            return false;
        }
    }

    public synchronized void remove(String id) {
        requireReadableIndex();
        List<Entry> entries = list();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).id.equals(id)) {
                entries.remove(i);
            }
        }
        save(entries);
    }

    private void requireReadableIndex() {
        if (!isIndexReadable()) {
            throw new IllegalStateException(
                    "Local mission index is unreadable; refusing to overwrite it"
            );
        }
    }

    private void save(List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                object.put("id", entry.id);
                object.put("name", entry.displayName);
                object.put("uri", entry.contentUri);
                object.put("importedAt", entry.importedAt);
                object.put("summary", entry.validationSummary);
                object.put("warnings", new JSONArray(entry.warnings));
                object.put("sha256", entry.sha256);
                array.put(object);
            } catch (JSONException impossible) {
                throw new IllegalStateException("Unable to encode local mission index", impossible);
            }
        }
        if (!preferences.edit().putString(KEY_ENTRIES, array.toString()).commit()) {
            throw new IllegalStateException("Unable to persist local mission index");
        }
    }

    public static final class Entry {
        public final String id;
        public final String displayName;
        public final String contentUri;
        public final long importedAt;
        public final String validationSummary;
        public final List<String> warnings;
        public final String sha256;

        Entry(
                String id,
                String displayName,
                String contentUri,
                long importedAt,
                String validationSummary,
                List<String> warnings,
                String sha256
        ) {
            this.id = id == null ? "" : id;
            this.displayName = displayName == null ? "" : displayName;
            this.contentUri = contentUri == null ? "" : contentUri;
            this.importedAt = Math.max(0L, importedAt);
            this.validationSummary = validationSummary == null ? "" : validationSummary;
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.sha256 = sha256 == null
                    ? ""
                    : sha256.toLowerCase(Locale.ROOT);
        }
    }
}
