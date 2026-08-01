package ru.alikdrone.hub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure model for the opt-in support report.
 *
 * <p>The collector supplies a narrow list of safe fields. This model applies
 * a second deny-list and inline redaction before rendering, so accidentally
 * added sensitive fields do not immediately leak into clipboard/share.</p>
 */
public final class Rc2SupportReportModel {
    public enum Section {
        DEVICE("Устройство"),
        SOFTWARE("Версии ПО"),
        RESOURCES("Ресурсы"),
        NETWORK("Сеть");

        private final String label;

        Section(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class Item {
        private final String label;
        private final String value;

        public Item(String label, String value) {
            this.label = label == null ? "" : label.trim();
            this.value = value == null ? "" : value.trim();
        }

        public String getLabel() {
            return label;
        }

        public String getValue() {
            return value;
        }
    }

    public static final class Snapshot {
        private final String generatedAt;
        private final Map<Section, List<Item>> items;

        private Snapshot(String generatedAt, Map<Section, List<Item>> items) {
            this.generatedAt = generatedAt;
            this.items = items;
        }

        public String getGeneratedAt() {
            return generatedAt;
        }

        public List<Item> getItems(Section section) {
            List<Item> sectionItems = items.get(section);
            return sectionItems == null ? Collections.emptyList() : sectionItems;
        }
    }

    public static final class Builder {
        private final String generatedAt;
        private final Map<Section, List<Item>> items = new EnumMap<>(Section.class);

        public Builder(String generatedAt) {
            this.generatedAt = generatedAt == null ? "" : generatedAt.trim();
        }

        public Builder add(Section section, String label, String value) {
            if (section == null) {
                throw new IllegalArgumentException("Section is required");
            }
            List<Item> sectionItems = items.get(section);
            if (sectionItems == null) {
                sectionItems = new ArrayList<>();
                items.put(section, sectionItems);
            }
            sectionItems.add(new Item(label, value));
            return this;
        }

        public Snapshot build() {
            Map<Section, List<Item>> immutable = new EnumMap<>(Section.class);
            for (Map.Entry<Section, List<Item>> entry : items.entrySet()) {
                immutable.put(
                        entry.getKey(),
                        Collections.unmodifiableList(new ArrayList<>(entry.getValue()))
                );
            }
            return new Snapshot(
                    generatedAt,
                    Collections.unmodifiableMap(immutable)
            );
        }
    }

    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(token|authorization|bearer|password|passwd|secret|api[_-]?key)"
                    + "\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)\\bbearer\\s+[a-z0-9._~+/=-]+"
    );
    private static final Pattern IPV4 = Pattern.compile(
            "(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)"
    );
    private static final Pattern IPV6 = Pattern.compile(
            "(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}"
                    + "(?![0-9a-f:])"
    );
    private static final Pattern MAC = Pattern.compile(
            "(?i)(?<![0-9a-f])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![0-9a-f])"
    );
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?:[a-z]:\\\\|/(?:storage|sdcard|data|home)/)[^\\r\\n]+"
    );

    private Rc2SupportReportModel() {
    }

    public static Set<Section> defaultSections() {
        return EnumSet.allOf(Section.class);
    }

    public static String render(Snapshot snapshot, Set<Section> selected) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Snapshot is required");
        }
        Set<Section> safeSelected = EnumSet.noneOf(Section.class);
        if (selected != null) {
            safeSelected.addAll(selected);
        }

        StringBuilder report = new StringBuilder();
        report.append("AlikDrone Hub — безопасный отчёт поддержки\n");
        if (!snapshot.getGeneratedAt().isEmpty()) {
            report.append("Создан: ")
                    .append(sanitizeInline(snapshot.getGeneratedAt()))
                    .append('\n');
        }
        report.append('\n');

        int visibleItems = 0;
        for (Section section : Section.values()) {
            if (!safeSelected.contains(section)) {
                continue;
            }
            StringBuilder body = new StringBuilder();
            for (Item item : snapshot.getItems(section)) {
                if (item.getLabel().isEmpty() || isSensitiveLabel(item.getLabel())) {
                    continue;
                }
                String value = sanitizeInline(item.getValue());
                if (value.isEmpty()) {
                    value = "нет данных";
                }
                body.append("• ")
                        .append(item.getLabel())
                        .append(": ")
                        .append(value)
                        .append('\n');
                visibleItems++;
            }
            if (body.length() > 0) {
                report.append(section.getLabel()).append('\n');
                report.append(body).append('\n');
            }
        }

        if (visibleItems == 0) {
            report.append("Разделы для отчёта не выбраны.\n\n");
        }
        report.append(
                "Не включено: серийный номер, координаты, SSID/Wi‑Fi, IP/MAC, "
                        + "токены, личные файлы и содержимое журналов."
        );
        return report.toString().trim();
    }

    static boolean isSensitiveLabel(String label) {
        String normalized = label == null ? "" : label.toLowerCase(Locale.US);
        return containsAny(
                normalized,
                "serial",
                "серийн",
                "ssid",
                "bssid",
                "wi-fi",
                "wifi",
                "mac",
                "ip address",
                "локальный ip",
                "latitude",
                "longitude",
                "координат",
                "gps",
                "token",
                "токен",
                "secret",
                "password",
                "парол",
                "api key",
                "путь",
                "path",
                "имя файла",
                "file name",
                "содержимое лога",
                "log content"
        ) || "ip".equals(normalized)
                || "mac".equals(normalized);
    }

    static String sanitizeInline(String input) {
        if (input == null) {
            return "";
        }
        String result = input
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        result = INLINE_SECRET.matcher(result).replaceAll("$1=<скрыто>");
        result = BEARER_TOKEN.matcher(result).replaceAll("Bearer <скрыто>");
        result = MAC.matcher(result).replaceAll("<MAC скрыт>");
        result = IPV4.matcher(result).replaceAll("<IP скрыт>");
        result = IPV6.matcher(result).replaceAll("<IPv6 скрыт>");
        result = ABSOLUTE_PATH.matcher(result).replaceAll("<путь скрыт>");
        if (result.length() > 500) {
            return result.substring(0, 500) + "…";
        }
        return result;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
