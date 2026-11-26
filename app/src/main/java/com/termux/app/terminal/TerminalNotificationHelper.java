package com.termux.app.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;

/** Posts desktop-style notifications requested by terminal applications via OSC 9/99/777. */
public final class TerminalNotificationHelper {

    private static final String LOG_TAG = "TerminalNotificationHelper";
    private static final String CHANNEL_ID = "terminal-notifications";

    private TerminalNotificationHelper() {
    }

    public static void notify(Context context, String title, String body) {
        if (body == null || body.isEmpty()) return;
        try {
            NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, context.getString(R.string.terminal_notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription(context.getString(R.string.terminal_notification_channel_description));
                notificationManager.createNotificationChannel(channel);
            }

            Intent intent = new Intent(context, TermuxActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, pendingIntentFlags);

            Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle(title != null && !title.isEmpty() ? title : context.getString(R.string.application_name))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();

            notificationManager.notify((int) (System.currentTimeMillis() & 0x7fffffff), notification);
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to post terminal notification", throwable);
        }
    }
}
