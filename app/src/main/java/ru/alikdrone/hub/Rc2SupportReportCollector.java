package ru.alikdrone.hub;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Collects only the fields approved for the support-report preview. */
final class Rc2SupportReportCollector {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = MIB * 1024L;

    private Rc2SupportReportCollector() {
    }

    static Rc2SupportReportModel.Snapshot collect(Context context) {
        Rc2SupportReportModel.Builder builder = new Rc2SupportReportModel.Builder(
                new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(new Date())
        );

        builder.add(Rc2SupportReportModel.Section.DEVICE, "Модель", safe(Build.MODEL));
        builder.add(
                Rc2SupportReportModel.Section.DEVICE,
                "Производитель",
                safe(Build.MANUFACTURER)
        );
        builder.add(
                Rc2SupportReportModel.Section.DEVICE,
                "Android",
                safe(Build.VERSION.RELEASE) + " · API " + Build.VERSION.SDK_INT
        );
        builder.add(
                Rc2SupportReportModel.Section.DEVICE,
                "Патч безопасности",
                safe(Build.VERSION.SECURITY_PATCH)
        );
        builder.add(
                Rc2SupportReportModel.Section.DEVICE,
                "Сборка Android",
                safe(Build.DISPLAY)
        );
        builder.add(
                Rc2SupportReportModel.Section.DEVICE,
                "ABI",
                Build.SUPPORTED_ABIS == null
                        ? "нет данных"
                        : join(Build.SUPPORTED_ABIS)
        );
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) context.getSystemService(
                Context.WINDOW_SERVICE
        );
        if (windowManager != null) {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics.setTo(context.getResources().getDisplayMetrics());
        }
        builder.add(
                Rc2SupportReportModel.Section.DEVICE,
                "Экран",
                metrics.widthPixels + " × " + metrics.heightPixels
                        + " · " + metrics.densityDpi + " DPI"
        );

        builder.add(
                Rc2SupportReportModel.Section.SOFTWARE,
                "AlikDrone Hub",
                ownVersion(context)
        );
        builder.add(
                Rc2SupportReportModel.Section.SOFTWARE,
                "DJI Fly",
                packageVersion(context, "dji.go.v5")
        );
        builder.add(
                Rc2SupportReportModel.Section.SOFTWARE,
                "Ядро Android",
                safe(System.getProperty("os.version"))
        );

        Intent battery = context.registerReceiver(
                null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        );
        builder.add(
                Rc2SupportReportModel.Section.RESOURCES,
                "Заряд пульта",
                batteryPercent(battery)
        );
        builder.add(
                Rc2SupportReportModel.Section.RESOURCES,
                "Температура батареи",
                batteryTemperature(battery)
        );
        builder.add(
                Rc2SupportReportModel.Section.RESOURCES,
                "RAM",
                memory(context)
        );
        builder.add(
                Rc2SupportReportModel.Section.RESOURCES,
                "Внутренняя память",
                storage(Environment.getDataDirectory())
        );
        builder.add(
                Rc2SupportReportModel.Section.RESOURCES,
                "SD-карта",
                removableStorage(context)
        );
        builder.add(
                Rc2SupportReportModel.Section.RESOURCES,
                "Время работы",
                duration(SystemClock.elapsedRealtime())
        );

        builder.add(
                Rc2SupportReportModel.Section.NETWORK,
                "Подключение",
                networkSummary(context)
        );
        builder.add(
                Rc2SupportReportModel.Section.NETWORK,
                "Примечание",
                "SSID, IP и MAC намеренно не собираются"
        );
        return builder.build();
    }

    private static String ownVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    0
            );
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            return safe(info.versionName) + " · code " + versionCode;
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return "не найдено";
        }
    }

    private static String packageVersion(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    packageName,
                    0
            );
            return safe(info.versionName);
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return "не найдено";
        }
    }

    private static String batteryPercent(Intent battery) {
        if (battery == null) {
            return "нет данных";
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) {
            return "нет данных";
        }
        return Math.round(level * 100f / scale) + "%";
    }

    private static String batteryTemperature(Intent battery) {
        if (battery == null || !battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
            return "нет данных";
        }
        int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        return String.format(Locale.US, "%.1f °C", tenths / 10.0)
                .replace('.', ',');
    }

    private static String memory(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE
        );
        if (manager == null) {
            return "нет данных";
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return capacity(info.availMem, info.totalMem);
    }

    private static String storage(File directory) {
        try {
            StatFs stats = new StatFs(directory.getAbsolutePath());
            return capacity(stats.getAvailableBytes(), stats.getTotalBytes());
        } catch (IllegalArgumentException | SecurityException error) {
            return "нет данных";
        }
    }

    private static String removableStorage(Context context) {
        File[] directories = context.getExternalFilesDirs(null);
        if (directories == null) {
            return "не найдена";
        }
        for (File directory : directories) {
            if (directory == null
                    || !Environment.isExternalStorageRemovable(directory)) {
                continue;
            }
            String state = Environment.getExternalStorageState(directory);
            if (Environment.MEDIA_MOUNTED.equals(state)
                    || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                return storage(directory);
            }
        }
        return "не найдена";
    }

    private static String capacity(long available, long total) {
        if (available < 0 || total <= 0) {
            return "нет данных";
        }
        return gib(available) + " ГиБ свободно из " + gib(total) + " ГиБ";
    }

    private static String gib(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / (double) GIB)
                .replace('.', ',');
    }

    private static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        long days = minutes / (24L * 60L);
        long hours = minutes / 60L % 24L;
        long remainingMinutes = minutes % 60L;
        if (days > 0) {
            return days + " д " + hours + " ч " + remainingMinutes + " мин";
        }
        return hours + " ч " + remainingMinutes + " мин";
    }

    private static String networkSummary(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE
        );
        if (manager == null) {
            return "нет данных";
        }
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null
                ? null
                : manager.getNetworkCapabilities(network);
        if (capabilities == null) {
            return "нет подключения";
        }
        String transport;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transport = "Wi‑Fi";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transport = "Ethernet";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transport = "мобильная сеть";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            transport = "VPN";
        } else {
            transport = "другая сеть";
        }
        boolean validated = capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
        );
        return (validated ? "интернет доступен · " : "сеть без подтверждённого интернета · ")
                + transport;
    }

    private static String join(String[] values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(value.trim());
        }
        return result.length() == 0 ? "нет данных" : result.toString();
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "нет данных" : value.trim();
    }
}
