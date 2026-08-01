package ru.alikdrone.hub;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Public entry point for the optional floating app switcher.
 *
 * <p>The switcher is off by default. Enabling it succeeds only after the user
 * has explicitly granted Android's "display over other apps" permission.</p>
 */
public final class SwitcherController {
    static final String PREFERENCES_NAME = "alikdrone_switcher";
    private static final String BOOT_PREFERENCES_NAME =
            "alikdrone_switcher_direct_boot";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_EDGE_RIGHT = "edge_right";
    static final String KEY_VERTICAL_POSITION = "vertical_position";
    static final String KEY_HIDDEN_PACKAGES = "hidden_packages";
    static final String KEY_LAYOUT_ROWS = "layout_rows";
    static final String KEY_LAYOUT_COLUMNS = "layout_columns";
    static final String KEY_SORT_MODE = "sort_mode";
    static final String KEY_ANCHOR = "anchor";
    static final String KEY_CATEGORY_OVERRIDES = "category_overrides";
    private static final String KEY_ONBOARDING_SHOWN = "onboarding_shown";
    private static final String KEY_ENABLE_PENDING = "enable_pending";
    private static final String KEY_RESTORE_ATTEMPTS = "restore_attempts";
    private static final int RESTORE_REQUEST_CODE = 2401;
    private static final int WATCHDOG_REQUEST_CODE = 2402;
    private static final long RESTORE_WATCHDOG_DELAY_MS = 15L * 60L * 1_000L;
    private static final long[] RESTORE_RETRY_DELAYS_MS = {
            5_000L,
            15_000L,
            45_000L,
            120_000L,
            300_000L,
            600_000L
    };

    private SwitcherController() {
    }

    public static synchronized boolean isEnabled(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences directBoot = bootPreferences(appContext);
        if (directBoot.contains(KEY_ENABLED)) {
            return directBoot.getBoolean(KEY_ENABLED, false);
        }
        Boolean credentialValue = readCredentialEnabled(appContext);
        if (credentialValue != null) {
            writeBootEnabled(appContext, credentialValue);
            return credentialValue;
        }
        return false;
    }

    public static boolean wasOnboardingShown(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDING_SHOWN, false);
    }

    public static void markOnboardingShown(Context context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ONBOARDING_SHOWN, true)
                .apply();
    }

    public static boolean isEnablePending(Context context) {
        try {
            return context.getSharedPreferences(
                            PREFERENCES_NAME,
                            Context.MODE_PRIVATE
                    )
                    .getBoolean(KEY_ENABLE_PENDING, false);
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    public static void setEnablePending(Context context, boolean pending) {
        try {
            context.getSharedPreferences(
                            PREFERENCES_NAME,
                            Context.MODE_PRIVATE
                    )
                    .edit()
                    .putBoolean(KEY_ENABLE_PENDING, pending)
                    .apply();
        } catch (IllegalStateException ignored) {
            // This UI-only flag is unavailable before the first unlock.
        }
    }

    /**
     * Returns whether a launcher package should be shown in the quick panel.
     *
     * <p>Package names are used deliberately: they survive application
     * updates that replace the launcher activity or its alias.</p>
     */
    static boolean isPackageVisible(Context context, String packageName) {
        return packageName == null
                || !readHiddenPackages(context).contains(packageName);
    }

    static synchronized void setPackageVisible(
            Context context,
            String packageName,
            boolean visible
    ) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Set<String> hiddenPackages =
                new HashSet<>(readHiddenPackages(appContext));
        boolean changed = visible
                ? hiddenPackages.remove(packageName)
                : hiddenPackages.add(packageName);
        if (!changed) {
            return;
        }

        // Taskbar is direct-boot aware, so mirror this preference to the
        // device-protected store. commit() makes the new value visible to the
        // already running service before its asynchronous rescan starts.
        bootPreferences(appContext)
                .edit()
                .putStringSet(
                        KEY_HIDDEN_PACKAGES,
                        new HashSet<>(hiddenPackages)
                )
                .commit();
        try {
            appContext.getSharedPreferences(
                            PREFERENCES_NAME,
                            Context.MODE_PRIVATE
                    )
                    .edit()
                    .putStringSet(
                            KEY_HIDDEN_PACKAGES,
                            new HashSet<>(hiddenPackages)
                    )
                    .apply();
        } catch (IllegalStateException ignored) {
            // Credential storage is unavailable during Direct Boot.
        }
        refreshRunningSwitcher(appContext);
    }

    static synchronized Set<String> readHiddenPackages(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences directBoot = bootPreferences(appContext);
        if (directBoot.contains(KEY_HIDDEN_PACKAGES)) {
            return copyStringSet(
                    directBoot.getStringSet(
                            KEY_HIDDEN_PACKAGES,
                            null
                    )
            );
        }
        try {
            Set<String> credentialValue = copyStringSet(
                    appContext.getSharedPreferences(
                                    PREFERENCES_NAME,
                                    Context.MODE_PRIVATE
                            )
                            .getStringSet(KEY_HIDDEN_PACKAGES, null)
            );
            directBoot.edit()
                    .putStringSet(
                            KEY_HIDDEN_PACKAGES,
                            new HashSet<>(credentialValue)
                    )
                    .commit();
            return credentialValue;
        } catch (IllegalStateException ignored) {
            return new HashSet<>();
        }
    }

    static synchronized TaskbarSettings readTaskbarSettings(Context context) {
        TaskbarSettings defaults = TaskbarSettings.defaults();
        int rows = readMirroredInt(
                context,
                KEY_LAYOUT_ROWS,
                defaults.getRows()
        );
        int columns = readMirroredInt(
                context,
                KEY_LAYOUT_COLUMNS,
                defaults.getColumns()
        );
        String sortMode = readMirroredString(
                context,
                KEY_SORT_MODE,
                defaults.getSortMode().getId()
        );
        String anchor = readMirroredString(
                context,
                KEY_ANCHOR,
                defaults.getAnchor().getId()
        );
        return new TaskbarSettings(
                rows,
                columns,
                TaskbarSettings.SortMode.fromId(sortMode),
                TaskbarSettings.Anchor.fromId(anchor)
        );
    }

    static synchronized void setTaskbarSettings(
            Context context,
            TaskbarSettings settings
    ) {
        if (settings == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        TaskbarSettings sanitized = new TaskbarSettings(
                settings.getRows(),
                settings.getColumns(),
                settings.getSortMode(),
                settings.getAnchor()
        );
        TaskbarSettings previous = readTaskbarSettings(appContext);
        boolean changed = previous.getRows() != sanitized.getRows()
                || previous.getColumns() != sanitized.getColumns()
                || previous.getSortMode() != sanitized.getSortMode()
                || previous.getAnchor() != sanitized.getAnchor();
        if (!changed) {
            return;
        }

        bootPreferences(appContext)
                .edit()
                .putInt(KEY_LAYOUT_ROWS, sanitized.getRows())
                .putInt(KEY_LAYOUT_COLUMNS, sanitized.getColumns())
                .putString(
                        KEY_SORT_MODE,
                        sanitized.getSortMode().getId()
                )
                .putString(KEY_ANCHOR, sanitized.getAnchor().getId())
                .commit();
        try {
            appContext.getSharedPreferences(
                            PREFERENCES_NAME,
                            Context.MODE_PRIVATE
                    )
                    .edit()
                    .putInt(KEY_LAYOUT_ROWS, sanitized.getRows())
                    .putInt(KEY_LAYOUT_COLUMNS, sanitized.getColumns())
                    .putString(
                            KEY_SORT_MODE,
                            sanitized.getSortMode().getId()
                    )
                    .putString(KEY_ANCHOR, sanitized.getAnchor().getId())
                    .apply();
        } catch (IllegalStateException ignored) {
            // Credential storage is unavailable during Direct Boot.
        }
        refreshRunningSwitcher(appContext);
    }

    static synchronized Map<String, String> readPackageCategoryOverrides(
            Context context
    ) {
        Set<String> serialized = readMirroredStringSet(
                context,
                KEY_CATEGORY_OVERRIDES
        );
        Map<String, String> result = new HashMap<>();
        for (String item : serialized) {
            if (item == null) {
                continue;
            }
            int separator = item.indexOf('=');
            if (separator <= 0 || separator >= item.length() - 1) {
                continue;
            }
            String packageName = item.substring(0, separator);
            String categoryId = item.substring(separator + 1);
            TaskbarCategory category = TaskbarCategory.fromId(categoryId);
            if (category != null) {
                result.put(packageName, category.getId());
            }
        }
        return result;
    }

    static synchronized void setPackageCategoryOverride(
            Context context,
            String packageName,
            TaskbarCategory category
    ) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Map<String, String> overrides =
                new HashMap<>(readPackageCategoryOverrides(appContext));
        String previous = overrides.get(packageName);
        if (category == null) {
            if (previous == null) {
                return;
            }
            overrides.remove(packageName);
        } else {
            if (category.getId().equals(previous)) {
                return;
            }
            overrides.put(packageName, category.getId());
        }

        Set<String> serialized = new HashSet<>();
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            serialized.add(entry.getKey() + "=" + entry.getValue());
        }
        writeMirroredStringSet(
                appContext,
                KEY_CATEGORY_OVERRIDES,
                serialized
        );
        refreshRunningSwitcher(appContext);
    }

    private static Set<String> copyStringSet(Set<String> values) {
        return values == null ? new HashSet<>() : new HashSet<>(values);
    }

    private static synchronized int readMirroredInt(
            Context context,
            String key,
            int defaultValue
    ) {
        Context appContext = context.getApplicationContext();
        SharedPreferences directBoot = bootPreferences(appContext);
        if (directBoot.contains(key)) {
            return directBoot.getInt(key, defaultValue);
        }
        try {
            int value = appContext.getSharedPreferences(
                            PREFERENCES_NAME,
                            Context.MODE_PRIVATE
                    )
                    .getInt(key, defaultValue);
            directBoot.edit().putInt(key, value).commit();
            return value;
        } catch (IllegalStateException ignored) {
            return defaultValue;
        }
    }

    private static synchronized String readMirroredString(
            Context context,
            String key,
            String defaultValue
    ) {
        Context appContext = context.getApplicationContext();
        SharedPreferences directBoot = bootPreferences(appContext);
        if (directBoot.contains(key)) {
            return directBoot.getString(key, defaultValue);
        }
        try {
            String value = appContext.getSharedPreferences(
                            PREFERENCES_NAME,
                            Context.MODE_PRIVATE
                    )
                    .getString(key, defaultValue);
            directBoot.edit().putString(key, value).commit();
            return value;
        } catch (IllegalStateException ignored) {
            return defaultValue;
        }
    }

    private static synchronized Set<String> readMirroredStringSet(
            Context context,
            String key
    ) {
        Context appContext = context.getApplicationContext();
        SharedPreferences directBoot = bootPreferences(appContext);
        if (directBoot.contains(key)) {
            return copyStringSet(directBoot.getStringSet(key, null));
        }
        try {
            Set<String> value = copyStringSet(
                    appContext.getSharedPreferences(
                                    PREFERENCES_NAME,
                                    Context.MODE_PRIVATE
                            )
                            .getStringSet(key, null)
            );
            directBoot.edit()
                    .putStringSet(key, new HashSet<>(value))
                    .commit();
            return value;
        } catch (IllegalStateException ignored) {
            return new HashSet<>();
        }
    }

    private static synchronized void writeMirroredStringSet(
            Context context,
            String key,
            Set<String> value
    ) {
        Context appContext = context.getApplicationContext();
        Set<String> copy = new HashSet<>(value);
        bootPreferences(appContext)
                .edit()
                .putStringSet(key, new HashSet<>(copy))
                .commit();
        try {
            appContext.getSharedPreferences(
                            PREFERENCES_NAME,
                            Context.MODE_PRIVATE
                    )
                    .edit()
                    .putStringSet(key, new HashSet<>(copy))
                    .apply();
        } catch (IllegalStateException ignored) {
            // Credential storage is unavailable during Direct Boot.
        }
    }

    /**
     * Asks the existing service for a target rescan. Starting the same service
     * does not recreate its WindowManager root, avoiding duplicate overlays.
     */
    private static void refreshRunningSwitcher(Context context) {
        if (!isEnabled(context) || !hasOverlayPermission(context)) {
            return;
        }
        Intent refresh = new Intent(context, FloatingSwitcherService.class);
        refresh.setAction(FloatingSwitcherService.ACTION_REFRESH_TARGETS);
        try {
            startService(context, refresh);
        } catch (RuntimeException ignored) {
            scheduleRestoreRetry(context);
        }
    }

    public static boolean hasOverlayPermission(Context context) {
        // minSdk is 23, where Settings.canDrawOverlays is already available.
        return Settings.canDrawOverlays(context);
    }

    public static Intent createOverlayPermissionIntent(Context context) {
        return new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName())
        );
    }

    /**
     * Enables or disables the switcher.
     *
     * @return {@code false} when permission is missing, durable preference
     * storage rejects the change, or the foreground service cannot start.
     */
    public static synchronized boolean setEnabled(
            Context context,
            boolean enabled
    ) {
        Context appContext = context.getApplicationContext();
        if (!enabled) {
            setEnablePending(appContext, false);
        }

        if (enabled && !hasOverlayPermission(appContext)) {
            writeBootEnabled(appContext, false);
            writeCredentialEnabled(appContext, false);
            clearRestoreSchedule(appContext);
            return false;
        }

        boolean bootSaved = writeBootEnabled(appContext, enabled);
        boolean credentialSaved =
                writeCredentialEnabled(appContext, enabled);
        if (!bootSaved || !credentialSaved) {
            if (enabled) {
                // Do not start an overlay that Android cannot restore with
                // the same persisted opt-in after a reboot.
                writeBootEnabled(appContext, false);
                writeCredentialEnabled(appContext, false);
            }
            clearRestoreSchedule(appContext);
            appContext.stopService(
                    new Intent(appContext, FloatingSwitcherService.class)
            );
            return false;
        }

        if (enabled) {
            try {
                startService(appContext);
                // Treat service startup as acknowledged only after the overlay
                // itself calls markRestoreHealthy(). If an OEM silently drops
                // the start request, this alarm retries instead of leaving the
                // user without the button.
                scheduleRestoreRetry(appContext);
            } catch (RuntimeException error) {
                scheduleRestoreRetry(appContext);
                return false;
            }
        } else {
            clearRestoreSchedule(appContext);
            appContext.stopService(new Intent(appContext, FloatingSwitcherService.class));
        }
        return true;
    }

    /**
     * Starts the service only when the previously saved opt-in and the Android
     * overlay permission are both still valid.
     */
    public static synchronized boolean startIfEnabled(Context context) {
        Context appContext = context.getApplicationContext();
        if (!isEnabled(appContext) || !hasOverlayPermission(appContext)) {
            return false;
        }
        try {
            Intent refresh = new Intent(
                    appContext,
                    FloatingSwitcherService.class
            );
            refresh.setAction(
                    FloatingSwitcherService.ACTION_RELOAD_CONFIGURATION
            );
            startService(appContext, refresh);
            scheduleRestoreRetry(appContext);
            return true;
        } catch (RuntimeException error) {
            scheduleRestoreRetry(appContext);
            return false;
        }
    }

    /** Runs the periodic overlay check without forcing application discovery. */
    static synchronized boolean checkHealthIfEnabled(Context context) {
        Context appContext = context.getApplicationContext();
        if (!isEnabled(appContext) || !hasOverlayPermission(appContext)) {
            return false;
        }
        try {
            Intent check = new Intent(
                    appContext,
                    FloatingSwitcherService.class
            );
            check.setAction(FloatingSwitcherService.ACTION_HEALTH_CHECK);
            startService(appContext, check);
            scheduleRestoreRetry(appContext);
            return true;
        } catch (RuntimeException error) {
            scheduleRestoreRetry(appContext);
            return false;
        }
    }

    private static void startService(Context context) {
        startService(
                context,
                new Intent(context, FloatingSwitcherService.class)
        );
    }

    private static void startService(Context context, Intent serviceIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    @SuppressLint("ApplySharedPref")
    static synchronized boolean readEdgeRight(
            Context context,
            boolean defaultValue
    ) {
        SharedPreferences directBoot = bootPreferences(context);
        if (directBoot.contains(KEY_EDGE_RIGHT)) {
            return directBoot.getBoolean(KEY_EDGE_RIGHT, defaultValue);
        }
        try {
            SharedPreferences credential = context.getSharedPreferences(
                    PREFERENCES_NAME,
                    Context.MODE_PRIVATE
            );
            boolean value = credential.getBoolean(KEY_EDGE_RIGHT, defaultValue);
            directBoot.edit().putBoolean(KEY_EDGE_RIGHT, value).commit();
            return value;
        } catch (IllegalStateException error) {
            return defaultValue;
        }
    }

    @SuppressLint("ApplySharedPref")
    static synchronized int readVerticalPosition(
            Context context,
            int defaultValue
    ) {
        SharedPreferences directBoot = bootPreferences(context);
        if (directBoot.contains(KEY_VERTICAL_POSITION)) {
            return directBoot.getInt(KEY_VERTICAL_POSITION, defaultValue);
        }
        try {
            SharedPreferences credential = context.getSharedPreferences(
                    PREFERENCES_NAME,
                    Context.MODE_PRIVATE
            );
            int value = credential.getInt(KEY_VERTICAL_POSITION, defaultValue);
            directBoot.edit().putInt(KEY_VERTICAL_POSITION, value).commit();
            return value;
        } catch (IllegalStateException error) {
            return defaultValue;
        }
    }

    @SuppressLint("ApplySharedPref")
    static synchronized void writePosition(
            Context context,
            boolean edgeRight,
            int verticalPosition
    ) {
        Context appContext = context.getApplicationContext();
        bootPreferences(appContext)
                .edit()
                .putBoolean(KEY_EDGE_RIGHT, edgeRight)
                .putInt(KEY_VERTICAL_POSITION, verticalPosition)
                .putString(
                        KEY_ANCHOR,
                        TaskbarSettings.Anchor.FREE.getId()
                )
                .commit();
        try {
            appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_EDGE_RIGHT, edgeRight)
                    .putInt(KEY_VERTICAL_POSITION, verticalPosition)
                    .putString(
                            KEY_ANCHOR,
                            TaskbarSettings.Anchor.FREE.getId()
                    )
                    .apply();
        } catch (IllegalStateException ignored) {
            // Credential storage is intentionally unavailable during Direct Boot.
        }
    }

    @SuppressLint("ApplySharedPref")
    static void clearRestoreSchedule(Context context) {
        Context appContext = context.getApplicationContext();
        bootPreferences(appContext)
                .edit()
                .putInt(KEY_RESTORE_ATTEMPTS, 0)
                .commit();
        AlarmManager manager =
                (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) {
            manager.cancel(restorePendingIntent(appContext, RESTORE_REQUEST_CODE));
            manager.cancel(restorePendingIntent(appContext, WATCHDOG_REQUEST_CODE));
        }
    }

    /**
     * Marks a successful overlay start and arms a persistent self-healing
     * check. The check is intentionally single-shot: every healthy service
     * start schedules the next one, avoiding unreliable repeating alarms on
     * vendor Android builds.
     */
    @SuppressLint("ApplySharedPref")
    static void markRestoreHealthy(Context context) {
        Context appContext = context.getApplicationContext();
        bootPreferences(appContext)
                .edit()
                .putInt(KEY_RESTORE_ATTEMPTS, 0)
                .commit();
        AlarmManager manager =
                (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        manager.cancel(restorePendingIntent(appContext, RESTORE_REQUEST_CODE));
        if (!isEnabled(appContext) || !hasOverlayPermission(appContext)) {
            manager.cancel(restorePendingIntent(appContext, WATCHDOG_REQUEST_CODE));
            return;
        }
        scheduleAlarm(
                manager,
                SystemClock.elapsedRealtime() + restoreWatchdogDelayMillis(),
                restorePendingIntent(appContext, WATCHDOG_REQUEST_CODE)
        );
    }

    @SuppressLint("ApplySharedPref")
    static void scheduleRestoreRetry(Context context) {
        Context appContext = context.getApplicationContext();
        if (!isEnabled(appContext) || !hasOverlayPermission(appContext)) {
            return;
        }
        SharedPreferences preferences = bootPreferences(appContext);
        int attempt = preferences.getInt(KEY_RESTORE_ATTEMPTS, 0);
        int nextAttempt = nextRestoreAttempt(attempt);
        preferences.edit()
                .putInt(KEY_RESTORE_ATTEMPTS, nextAttempt)
                .commit();

        AlarmManager manager =
                (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        long triggerAt = SystemClock.elapsedRealtime()
                + restoreRetryDelayMillis(attempt);
        manager.cancel(restorePendingIntent(appContext, WATCHDOG_REQUEST_CODE));
        scheduleAlarm(
                manager,
                triggerAt,
                restorePendingIntent(appContext, RESTORE_REQUEST_CODE)
        );
    }

    private static void scheduleAlarm(
            AlarmManager manager,
            long triggerAt,
            PendingIntent pendingIntent
    ) {
        try {
            manager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
            );
        } catch (RuntimeException error) {
            manager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
            );
        }
    }

    static long restoreRetryDelayMillis(int attempt) {
        int safeIndex = Math.max(
                0,
                Math.min(attempt, RESTORE_RETRY_DELAYS_MS.length - 1)
        );
        return RESTORE_RETRY_DELAYS_MS[safeIndex];
    }

    static long restoreWatchdogDelayMillis() {
        return RESTORE_WATCHDOG_DELAY_MS;
    }

    static int nextRestoreAttempt(int attempt) {
        int safeAttempt = Math.max(0, attempt);
        return Math.min(
                safeAttempt + 1,
                RESTORE_RETRY_DELAYS_MS.length
        );
    }

    private static PendingIntent restorePendingIntent(
            Context context,
            int requestCode
    ) {
        Intent intent = new Intent(context, BootReceiver.class);
        intent.setAction(BootReceiver.ACTION_RESTORE_TASKBAR);
        intent.putExtra(
                BootReceiver.EXTRA_WATCHDOG,
                requestCode == WATCHDOG_REQUEST_CODE
        );
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                flags
        );
    }

    /**
     * Returns the user preference when credential storage is available.
     *
     * <p>During Direct Boot Android intentionally blocks credential-encrypted
     * storage. In that state the mirrored device-protected preference is used
     * instead, so an explicitly enabled switcher can return before the first
     * unlock.</p>
     */
    private static Boolean readCredentialEnabled(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager userManager =
                    (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (userManager != null && !userManager.isUserUnlocked()) {
                return null;
            }
        }
        try {
            return context.getSharedPreferences(
                    PREFERENCES_NAME,
                    Context.MODE_PRIVATE
            ).getBoolean(KEY_ENABLED, false);
        } catch (IllegalStateException error) {
            return null;
        }
    }

    @SuppressLint("ApplySharedPref")
    private static boolean writeBootEnabled(
            Context context,
            boolean enabled
    ) {
        // Direct Boot reads this value immediately after the call; a
        // synchronous commit is intentional here.
        return bootPreferences(context)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .commit();
    }

    @SuppressLint("ApplySharedPref")
    private static boolean writeCredentialEnabled(
            Context context,
            boolean enabled
    ) {
        try {
            return context.getSharedPreferences(
                            PREFERENCES_NAME,
                            Context.MODE_PRIVATE
                    )
                    .edit()
                    .putBoolean(KEY_ENABLED, enabled)
                    .commit();
        } catch (IllegalStateException ignored) {
            // Direct Boot still has the mirrored device-protected value.
            return true;
        }
    }

    private static SharedPreferences bootPreferences(Context context) {
        Context storageContext = context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = context.createDeviceProtectedStorageContext();
        }
        return storageContext.getSharedPreferences(
                BOOT_PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
    }
}
