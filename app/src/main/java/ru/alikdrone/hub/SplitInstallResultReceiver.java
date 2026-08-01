package ru.alikdrone.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/**
 * Receives PackageInstaller session status and opens Android's own
 * confirmation UI when required.
 */
public final class SplitInstallResultReceiver extends BroadcastReceiver {
    static final String ACTION_INSTALL_RESULT =
            "ru.alikdrone.hub.SPLIT_INSTALL_RESULT";
    private static final String INSTALL_CHANNEL = "split_install";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !ACTION_INSTALL_RESULT.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
        );
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation == null) {
                show(context, "Не удалось открыть подтверждение установки.");
                return;
            }
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirmation);
            } catch (RuntimeException error) {
                postConfirmationNotification(context, confirmation, intent);
                show(
                        context,
                        "Откройте уведомление AlikDrone и подтвердите установку."
                );
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            show(context, "Discord установлен.");
            return;
        }
        String detail = intent.getStringExtra(
                PackageInstaller.EXTRA_STATUS_MESSAGE
        );
        String safeDetail = detail == null || detail.trim().isEmpty()
                ? String.valueOf(status)
                : detail;
        show(context, "Не удалось установить Discord: " + safeDetail);
    }

    private static void show(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    private static void postConfirmationNotification(
            Context context,
            Intent confirmation,
            Intent result
    ) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(
                        Context.NOTIFICATION_SERVICE
                );
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    INSTALL_CHANNEL,
                    "Установка приложений",
                    NotificationManager.IMPORTANCE_HIGH
            ));
        }
        int sessionId = result.getIntExtra(
                PackageInstaller.EXTRA_SESSION_ID,
                0
        );
        PendingIntent action = PendingIntent.getActivity(
                context,
                sessionId,
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, INSTALL_CHANNEL)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Discord готов к установке")
                .setContentText("Нажмите, чтобы подтвердить установку.")
                .setContentIntent(action)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH);
        manager.notify(7_100 + Math.max(0, sessionId), builder.build());
    }
}
