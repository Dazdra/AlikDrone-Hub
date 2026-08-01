package ru.alikdrone.hub;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restores the explicitly enabled switcher after a normal Android boot.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AlikDroneBoot";
    static final String ACTION_RESTORE_TASKBAR =
            "ru.alikdrone.hub.action.RESTORE_TASKBAR";
    static final String EXTRA_WATCHDOG =
            "ru.alikdrone.hub.extra.TASKBAR_WATCHDOG";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !isRestoreAction(intent.getAction())) {
            return;
        }

        String action = intent.getAction();
        if (!ACTION_RESTORE_TASKBAR.equals(action)) {
            SwitcherController.clearRestoreSchedule(context);
        }
        if (!SwitcherController.isEnabled(context)
                || !SwitcherController.hasOverlayPermission(context)) {
            return;
        }

        try {
            boolean started = intent.getBooleanExtra(EXTRA_WATCHDOG, false)
                    ? SwitcherController.checkHealthIfEnabled(context)
                    : SwitcherController.startIfEnabled(context);
            if (!started) {
                Log.w(TAG, "Taskbar restore start rejected; retry scheduled");
                SwitcherController.scheduleRestoreRetry(context);
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Taskbar restore failed; retry scheduled", error);
            SwitcherController.scheduleRestoreRetry(context);
        }
    }

    @SuppressLint("InlinedApi")
    static boolean isRestoreAction(String action) {
        // ACTION_LOCKED_BOOT_COMPLETED is an inlined String constant and is
        // safe to compare on API 23; only API 24+ will ever send it.
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || ACTION_RESTORE_TASKBAR.equals(action);
    }
}
