package ru.alikdrone.hub;

import java.util.Locale;

final class DeviceInfoFormatter {
    private static final long KIB = 1024L;
    private static final long MIB = KIB * 1024L;
    private static final long GIB = MIB * 1024L;

    private DeviceInfoFormatter() {
    }

    static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "Нет данных";
        }
        if (bytes >= GIB) {
            return decimal(bytes / (double) GIB) + " ГБ";
        }
        if (bytes >= MIB) {
            return decimal(bytes / (double) MIB) + " МБ";
        }
        if (bytes >= KIB) {
            return decimal(bytes / (double) KIB) + " КБ";
        }
        return bytes + " Б";
    }

    static String formatCapacity(long availableBytes, long totalBytes) {
        if (availableBytes < 0 || totalBytes <= 0) {
            return "Нет данных";
        }
        return formatBytes(availableBytes) + " свободно из " + formatBytes(totalBytes);
    }

    static String formatUptime(long uptimeMillis) {
        if (uptimeMillis < 0) {
            return "Нет данных";
        }
        long totalMinutes = uptimeMillis / 60_000L;
        long days = totalMinutes / (24L * 60L);
        long hours = totalMinutes / 60L % 24L;
        long minutes = totalMinutes % 60L;
        if (days > 0) {
            return days + " д " + hours + " ч " + minutes + " мин";
        }
        if (hours > 0) {
            return hours + " ч " + minutes + " мин";
        }
        return minutes + " мин";
    }

    static String formatTemperature(int tenthsCelsius) {
        return decimal(tenthsCelsius / 10.0) + " °C";
    }

    static String formatRefreshRate(float hertz) {
        if (Float.isNaN(hertz) || Float.isInfinite(hertz) || hertz <= 0f) {
            return "Нет данных";
        }
        return Math.round(hertz) + " Гц";
    }

    private static String decimal(double value) {
        return String.format(Locale.US, "%.1f", value).replace('.', ',');
    }
}
