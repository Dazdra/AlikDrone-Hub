package ru.alikdrone.hub;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public final class DeviceInfoActivity extends Activity {
    private boolean initialRenderComplete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_info);
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));

        findViewById(R.id.button_device_info_back).setOnClickListener(
                ignored -> finish()
        );
        renderDeviceInfo();
        animateScreenIn(findViewById(R.id.device_info_root));
        initialRenderComplete = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (initialRenderComplete) {
            renderDeviceInfo();
        }
    }

    private void renderDeviceInfo() {
        LinearLayout deviceRows = findViewById(R.id.device_info_device_rows);
        LinearLayout resourceRows = findViewById(R.id.device_info_resource_rows);
        LinearLayout displayRows = findViewById(R.id.device_info_display_rows);
        LinearLayout networkRows = findViewById(R.id.device_info_network_rows);
        deviceRows.removeAllViews();
        resourceRows.removeAllViews();
        displayRows.removeAllViews();
        networkRows.removeAllViews();

        addRow(deviceRows, "Модель", valueOrUnavailable(Build.MODEL));
        addRow(deviceRows, "Производитель", valueOrUnavailable(Build.MANUFACTURER));
        addRow(
                deviceRows,
                "Android",
                valueOrUnavailable(Build.VERSION.RELEASE) + " · API " + Build.VERSION.SDK_INT
        );
        addRow(deviceRows, "Сборка", valueOrUnavailable(Build.DISPLAY));
        addRow(
                deviceRows,
                "Патч безопасности",
                valueOrUnavailable(Build.VERSION.SECURITY_PATCH)
        );
        addRow(deviceRows, "CPU / ABI", formatAbi());

        Intent battery = registerReceiver(
                null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        );
        addRow(resourceRows, "Заряд пульта", batteryLevel(battery));
        addRow(resourceRows, "Питание", batteryPower(battery));
        addRow(resourceRows, "Состояние АКБ", batteryState(battery));
        addRow(resourceRows, "Температура АКБ", batteryTemperature(battery));
        addRow(resourceRows, "RAM", memoryInfo());
        addRow(resourceRows, "Внутренняя память", storageInfo(
                Environment.getDataDirectory()
        ));
        addRow(resourceRows, "SD-карта", removableStorageInfo());

        Display display = getWindowManager().getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        addRow(
                displayRows,
                "Разрешение",
                metrics.widthPixels + " × " + metrics.heightPixels
        );
        addRow(
                displayRows,
                "Плотность",
                metrics.densityDpi + " DPI · " + trimFloat(metrics.density) + "×"
        );
        addRow(
                displayRows,
                "Частота",
                DeviceInfoFormatter.formatRefreshRate(display.getRefreshRate())
        );
        addRow(
                displayRows,
                "Ядро",
                valueOrUnavailable(System.getProperty("os.version"))
        );
        addRow(
                displayRows,
                "Время работы",
                DeviceInfoFormatter.formatUptime(SystemClock.elapsedRealtime())
        );

        addRow(networkRows, "Интернет", connectionInfo());
        addRow(networkRows, "Локальный IP", localIpv4Address());
    }

    private void addRow(LinearLayout parent, String label, String value) {
        View row = LayoutInflater.from(this).inflate(
                R.layout.device_info_row,
                parent,
                false
        );
        ((TextView) row.findViewById(R.id.device_info_row_label)).setText(label);
        ((TextView) row.findViewById(R.id.device_info_row_value)).setText(
                valueOrUnavailable(value)
        );
        parent.addView(row);
    }

    private String formatAbi() {
        if (Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0) {
            return getString(R.string.device_info_unavailable);
        }
        StringBuilder result = new StringBuilder();
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi == null || abi.trim().isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(abi);
        }
        return result.length() == 0
                ? getString(R.string.device_info_unavailable)
                : result.toString();
    }

    private String batteryLevel(Intent battery) {
        if (battery == null) {
            return getString(R.string.device_info_unavailable);
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) {
            return getString(R.string.device_info_unavailable);
        }
        return Math.round(level * 100f / scale) + "%";
    }

    private String batteryPower(Intent battery) {
        if (battery == null) {
            return getString(R.string.device_info_unavailable);
        }
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC:
                return "Сетевой адаптер";
            case BatteryManager.BATTERY_PLUGGED_USB:
                return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                return "Беспроводная зарядка";
            default:
                return "От батареи";
        }
    }

    private String batteryState(Intent battery) {
        if (battery == null) {
            return getString(R.string.device_info_unavailable);
        }
        int health = battery.getIntExtra(
                BatteryManager.EXTRA_HEALTH,
                BatteryManager.BATTERY_HEALTH_UNKNOWN
        );
        int status = battery.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN
        );
        String healthLabel = batteryHealthLabel(health);
        String statusLabel = batteryStatusLabel(status);
        if (health == BatteryManager.BATTERY_HEALTH_UNKNOWN) {
            return statusLabel;
        }
        if (status == BatteryManager.BATTERY_STATUS_UNKNOWN) {
            return healthLabel;
        }
        return healthLabel + " · " + statusLabel;
    }

    private String batteryTemperature(Intent battery) {
        if (battery == null || !battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
            return getString(R.string.device_info_unavailable);
        }
        return DeviceInfoFormatter.formatTemperature(
                battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        );
    }

    private String batteryHealthLabel(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD:
                return "Нормальное";
            case BatteryManager.BATTERY_HEALTH_COLD:
                return "Низкая температура";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return "Перегрев";
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return "Неисправна";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return "Высокое напряжение";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                return "Ошибка";
            default:
                return getString(R.string.device_info_unavailable);
        }
    }

    private String batteryStatusLabel(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return "Заряжается";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "Разряжается";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "Заряжена";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "Не заряжается";
            default:
                return "Статус неизвестен";
        }
    }

    private String memoryInfo() {
        ActivityManager manager = (ActivityManager) getSystemService(
                Context.ACTIVITY_SERVICE
        );
        if (manager == null) {
            return getString(R.string.device_info_unavailable);
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return DeviceInfoFormatter.formatCapacity(info.availMem, info.totalMem);
    }

    private String storageInfo(File path) {
        if (path == null) {
            return getString(R.string.device_info_unavailable);
        }
        try {
            StatFs stats = new StatFs(path.getAbsolutePath());
            return DeviceInfoFormatter.formatCapacity(
                    stats.getAvailableBytes(),
                    stats.getTotalBytes()
            );
        } catch (IllegalArgumentException | SecurityException error) {
            return getString(R.string.device_info_unavailable);
        }
    }

    private String removableStorageInfo() {
        File[] directories = getExternalFilesDirs(null);
        if (directories == null) {
            return getString(R.string.device_info_not_found);
        }
        for (File directory : directories) {
            if (directory == null || !Environment.isExternalStorageRemovable(directory)) {
                continue;
            }
            String state = Environment.getExternalStorageState(directory);
            if (Environment.MEDIA_MOUNTED.equals(state)
                    || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                return storageInfo(directory);
            }
        }
        return getString(R.string.device_info_not_found);
    }

    private String connectionInfo() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE
        );
        if (manager == null) {
            return getString(R.string.device_info_unavailable);
        }
        Network activeNetwork = manager.getActiveNetwork();
        if (activeNetwork == null) {
            return "Нет подключения";
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(activeNetwork);
        if (capabilities == null) {
            return "Нет подключения";
        }
        String transport = networkTransport(capabilities);
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return "Подключён · " + transport;
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return "Сеть есть, интернет не подтверждён · " + transport;
        }
        return "Локальная сеть · " + transport;
    }

    private String networkTransport(NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "Wi-Fi";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "Ethernet";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "Мобильная сеть";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "VPN";
        }
        return "другое подключение";
    }

    private String localIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return getString(R.string.device_info_unavailable);
            }
            List<NetworkInterface> interfaceList = Collections.list(interfaces);
            String fallback = null;
            for (NetworkInterface networkInterface : interfaceList) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(
                        networkInterface.getInetAddresses()
                )) {
                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) {
                        continue;
                    }
                    String hostAddress = address.getHostAddress();
                    String name = networkInterface.getName().toLowerCase(Locale.US);
                    if (name.startsWith("wlan") || name.startsWith("eth")) {
                        return hostAddress;
                    }
                    if (fallback == null) {
                        fallback = hostAddress;
                    }
                }
            }
            return fallback == null
                    ? getString(R.string.device_info_unavailable)
                    : fallback;
        } catch (SocketException | SecurityException error) {
            return getString(R.string.device_info_unavailable);
        }
    }

    private String valueOrUnavailable(String value) {
        if (value == null || value.trim().isEmpty()) {
            return getString(R.string.device_info_unavailable);
        }
        return value.trim();
    }

    private String trimFloat(float value) {
        return String.format(Locale.US, "%.2f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private void animateScreenIn(View view) {
        view.setAlpha(0f);
        view.setTranslationY(dp(10));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
