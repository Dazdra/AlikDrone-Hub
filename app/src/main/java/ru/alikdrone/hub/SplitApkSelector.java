package ru.alikdrone.hub;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure split-selection rules used before installing a curated XAPK archive.
 *
 * <p>The selector intentionally accepts only flat APK entries. This prevents a
 * crafted archive from escaping the extraction directory and also matches the
 * layout used by the Discord XAPK served by AlikDrone.</p>
 */
final class SplitApkSelector {
    private static final Map<String, Integer> DENSITY_DPI;

    static {
        Map<String, Integer> densities = new HashMap<>();
        densities.put("ldpi", 120);
        densities.put("mdpi", 160);
        densities.put("tvdpi", 213);
        densities.put("hdpi", 240);
        densities.put("xhdpi", 320);
        densities.put("xxhdpi", 480);
        densities.put("xxxhdpi", 640);
        DENSITY_DPI = Collections.unmodifiableMap(densities);
    }

    static final class Selection {
        private final List<String> entries;
        private final String abiEntry;
        private final String densityEntry;
        private final List<String> localeEntries;

        Selection(
                List<String> entries,
                String abiEntry,
                String densityEntry,
                List<String> localeEntries
        ) {
            this.entries = Collections.unmodifiableList(entries);
            this.abiEntry = abiEntry;
            this.densityEntry = densityEntry;
            this.localeEntries = Collections.unmodifiableList(localeEntries);
        }

        List<String> getEntries() {
            return entries;
        }

        String getAbiEntry() {
            return abiEntry;
        }

        String getDensityEntry() {
            return densityEntry;
        }

        List<String> getLocaleEntries() {
            return localeEntries;
        }
    }

    private SplitApkSelector() {
    }

    static Selection select(
            Collection<String> archiveEntries,
            List<String> supportedAbis,
            int densityDpi,
            List<String> preferredLocales
    ) {
        if (archiveEntries == null || archiveEntries.isEmpty()) {
            throw new IllegalArgumentException("Bundle is empty");
        }

        List<String> apkEntries = new ArrayList<>();
        for (String entry : archiveEntries) {
            if (!isSafeFlatApkEntry(entry)) {
                continue;
            }
            apkEntries.add(entry);
        }
        Collections.sort(apkEntries);
        if (!apkEntries.contains("base.apk")) {
            throw new IllegalArgumentException("Bundle does not contain base.apk");
        }

        List<String> abiEntries = new ArrayList<>();
        List<String> densityEntries = new ArrayList<>();
        List<String> localeEntries = new ArrayList<>();
        List<String> featureEntries = new ArrayList<>();
        for (String entry : apkEntries) {
            if ("base.apk".equals(entry)) {
                continue;
            }
            String qualifier = qualifier(entry);
            if (isAbiQualifier(qualifier)) {
                abiEntries.add(entry);
            } else if (DENSITY_DPI.containsKey(qualifier)) {
                densityEntries.add(entry);
            } else if (isLocaleQualifier(qualifier)) {
                localeEntries.add(entry);
            } else if (!isConfigSplit(entry)) {
                // A non-config feature split is required by the package and is
                // therefore installed together with the base.
                featureEntries.add(entry);
            }
        }

        String abiEntry = selectAbi(abiEntries, supportedAbis);
        String densityEntry = selectDensity(densityEntries, densityDpi);
        List<String> selectedLocales =
                selectLocales(localeEntries, preferredLocales);

        List<String> selected = new ArrayList<>();
        selected.add("base.apk");
        Collections.sort(featureEntries);
        selected.addAll(featureEntries);
        if (abiEntry != null) {
            selected.add(abiEntry);
        }
        if (densityEntry != null) {
            selected.add(densityEntry);
        }
        selected.addAll(selectedLocales);
        return new Selection(
                selected,
                abiEntry,
                densityEntry,
                selectedLocales
        );
    }

    static boolean isSafeFlatApkEntry(String name) {
        if (name == null
                || name.length() < 5
                || name.length() > 180
                || name.contains("/")
                || name.contains("\\")
                || name.contains("..")
                || !name.toLowerCase(Locale.US).endsWith(".apk")) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (Character.isLetterOrDigit(value)
                    || value == '.'
                    || value == '_'
                    || value == '-') {
                continue;
            }
            return false;
        }
        return true;
    }

    private static String selectAbi(
            List<String> available,
            List<String> supportedAbis
    ) {
        if (available.isEmpty()) {
            return null;
        }
        List<String> safeAbis = supportedAbis == null
                ? Collections.emptyList()
                : supportedAbis;
        for (String abi : safeAbis) {
            String normalizedAbi = normalizeAbi(abi);
            for (String entry : available) {
                if (normalizedAbi.equals(qualifier(entry))) {
                    return entry;
                }
            }
        }
        throw new IllegalArgumentException(
                "Bundle has no APK for this CPU architecture"
        );
    }

    private static String selectDensity(List<String> available, int densityDpi) {
        if (available.isEmpty()) {
            return null;
        }
        int safeDpi = densityDpi > 0 ? densityDpi : 160;
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestDpi = -1;
        for (String entry : available) {
            int candidateDpi = DENSITY_DPI.get(qualifier(entry));
            int distance = Math.abs(candidateDpi - safeDpi);
            // On an exact tie Android normally benefits more from the
            // higher-density resource than from upscaling.
            if (best == null
                    || distance < bestDistance
                    || (distance == bestDistance && candidateDpi > bestDpi)
                    || (distance == bestDistance
                    && candidateDpi == bestDpi
                    && entry.compareTo(best) < 0)) {
                best = entry;
                bestDistance = distance;
                bestDpi = candidateDpi;
            }
        }
        return best;
    }

    private static List<String> selectLocales(
            List<String> available,
            List<String> preferredLocales
    ) {
        if (available.isEmpty() || preferredLocales == null) {
            return Collections.emptyList();
        }
        Set<String> selected = new HashSet<>();
        for (String preferred : preferredLocales) {
            String normalized = normalizeLocale(preferred);
            if (normalized.isEmpty()) {
                continue;
            }
            String language = normalized;
            int separator = normalized.indexOf('_');
            if (separator > 0) {
                language = normalized.substring(0, separator);
            }
            for (String entry : available) {
                String qualifier = qualifier(entry);
                if (qualifier.equals(normalized)
                        || qualifier.equals(language)) {
                    selected.add(entry);
                }
            }
        }
        List<String> result = new ArrayList<>(selected);
        Collections.sort(result);
        return result;
    }

    private static boolean isConfigSplit(String entry) {
        String lower = entry.toLowerCase(Locale.US);
        return lower.startsWith("split_config.")
                || lower.startsWith("config.")
                || lower.contains(".config.");
    }

    private static String qualifier(String entry) {
        String stem = entry.substring(0, entry.length() - 4);
        int marker = stem.lastIndexOf('.');
        String value = marker < 0 ? stem : stem.substring(marker + 1);
        return value.toLowerCase(Locale.US).replace('-', '_');
    }

    private static boolean isAbiQualifier(String qualifier) {
        return "armeabi".equals(qualifier)
                || "armeabi_v7a".equals(qualifier)
                || "arm64_v8a".equals(qualifier)
                || "x86".equals(qualifier)
                || "x86_64".equals(qualifier)
                || "mips".equals(qualifier)
                || "mips64".equals(qualifier);
    }

    private static boolean isLocaleQualifier(String qualifier) {
        if (qualifier == null || qualifier.length() < 2) {
            return false;
        }
        if (qualifier.length() == 2 || qualifier.length() == 3) {
            return allLetters(qualifier);
        }
        int separator = qualifier.indexOf('_');
        return separator == 2
                && qualifier.length() >= 4
                && qualifier.length() <= 8
                && allLetters(qualifier.substring(0, separator))
                && allLetters(qualifier.substring(separator + 1));
    }

    private static boolean allLetters(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isLetter(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeAbi(String abi) {
        return abi == null
                ? ""
                : abi.trim().toLowerCase(Locale.US).replace('-', '_');
    }

    private static String normalizeLocale(String locale) {
        if (locale == null) {
            return "";
        }
        String normalized = locale.trim()
                .toLowerCase(Locale.US)
                .replace('-', '_');
        int extension = normalized.indexOf("_u_");
        return extension > 0 ? normalized.substring(0, extension) : normalized;
    }
}
