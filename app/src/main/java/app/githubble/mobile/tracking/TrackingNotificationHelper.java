package app.githubble.mobile.tracking;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.List;

import app.githubble.mobile.MainActivity;
import app.githubble.mobile.R;

/** Native Android notifications for newly detected GitHub changes. */
public final class TrackingNotificationHelper {
    public static final String CHANNEL_ID = "githubble_tracking_updates";
    private static final int SUMMARY_ID = 21001;

    private TrackingNotificationHelper() {}

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Новое у разработчиков",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Новые репозитории, релизы и значимые изменения отслеживаемых разработчиков");
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    public static boolean canPost(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && (Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled());
    }

    public static void notifyEvents(Context context, List<TrackingDatabase.Event> events) {
        if (events == null || events.isEmpty()) return;
        createChannel(context);
        if (!canPost(context)) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                210,
                new Intent(context, MainActivity.class)
                        .setAction("app.githubble.mobile.OPEN_TRACKING")
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (events.size() == 1) {
            TrackingDatabase.Event event = events.get(0);
            Notification.Builder builder = builder(context)
                    .setContentTitle(event.title)
                    .setContentText(event.summary)
                    .setStyle(new Notification.BigTextStyle().bigText(event.summary))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setNumber(1);
            manager.notify(SUMMARY_ID, builder.build());
            return;
        }

        Notification.InboxStyle inbox = new Notification.InboxStyle();
        int max = Math.min(6, events.size());
        for (int i = 0; i < max; i++) inbox.addLine(events.get(i).title);
        if (events.size() > max) inbox.setSummaryText("Ещё " + (events.size() - max) + " событий");
        Notification.Builder builder = builder(context)
                .setContentTitle("GitHubble: найдено нового — " + events.size())
                .setContentText(events.get(0).title)
                .setStyle(inbox)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setNumber(events.size());
        manager.notify(SUMMARY_ID, builder.build());
    }

    private static Notification.Builder builder(Context context) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_bell)
                .setColor(0xFF4EA839)
                .setCategory(Notification.CATEGORY_SOCIAL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) builder.setPriority(Notification.PRIORITY_DEFAULT);
        return builder;
    }
}
