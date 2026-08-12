package app.githubble.mobile.copilot;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import app.githubble.mobile.MainActivity;
import app.githubble.mobile.R;
import app.githubble.mobile.controller.CopilotAgentController;

/** System notifications for meaningful Copilot cloud-agent state transitions. */
public final class CopilotNotificationHelper {
    public static final String CHANNEL_ID = "githubble_copilot_tasks";
    private static final int BASE_ID = 22300;

    private CopilotNotificationHelper() {}

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Задачи Copilot",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Завершение задач, запрос ответа и ошибки GitHub Copilot");
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

    public static void notifyTransition(Context context, String taskId, String repository,
                                        String taskName, String state) {
        createChannel(context);
        if (!canPost(context)) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Intent intent = new Intent(context, MainActivity.class)
                .setAction("app.githubble.mobile.OPEN_COPILOT_TASK")
                .putExtra("copilot_task_id", taskId == null ? "" : taskId)
                .putExtra("copilot_repository", repository == null ? "" : repository)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                BASE_ID + Math.abs((taskId == null ? "" : taskId).hashCode() % 6000),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title;
        if ("completed".equals(state)) title = "Copilot завершил задачу";
        else if ("waiting_for_user".equals(state)) title = "Copilot ждёт вашего ответа";
        else if ("failed".equals(state)) title = "Ошибка задачи Copilot";
        else if ("timed_out".equals(state)) title = "Задача Copilot превысила время";
        else if ("cancelled".equals(state)) title = "Задача Copilot остановлена";
        else title = "Обновление задачи Copilot";

        String text = (taskName == null || taskName.isEmpty() ? "Задача Copilot" : taskName)
                + (repository == null || repository.isEmpty() ? "" : " · " + repository);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_copilot)
                .setColor(0xFF5B6BFF)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text + "\nСтатус: " + CopilotAgentController.stateLabel(state)))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) builder.setPriority(Notification.PRIORITY_DEFAULT);
        manager.notify(BASE_ID + Math.abs((taskId == null ? "" : taskId).hashCode() % 6000), builder.build());
    }
}
