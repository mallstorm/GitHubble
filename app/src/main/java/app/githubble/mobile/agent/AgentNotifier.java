package app.githubble.mobile.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import app.githubble.mobile.MainActivity;
import app.githubble.mobile.R;
import app.githubble.mobile.build.BuildMonitor;

/** Notifies only when the UI is not in front: approval needed, task finished. No tool arguments leave the app. */
public final class AgentNotifier implements AgentRun.Events {
  static final String CHANNEL = "githubble_agent";
  private final Context context;

  public AgentNotifier(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public void approvalWaiting(String what) {
    show(7001, "Агенту нужно подтверждение", what.isEmpty() ? "Откройте GitHubble, чтобы подтвердить действие" : what);
  }

  @Override
  public void finished(String state, String stage) {
    String title =
        "succeeded".equals(state)
            ? "Задача агента завершена"
            : "cancelled".equals(state)
                ? "Задача агента остановлена"
                : "failed".equals(state) ? "Задача агента завершилась ошибкой" : "Задача агента прервана";
    show(7002, title, stage == null ? "" : stage);
  }

  private void show(int id, String title, String text) {
    if (AgentUiSession.foreground()) return;
    if (Build.VERSION.SDK_INT >= 33
        && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return;
    if (Build.VERSION.SDK_INT >= 26)
      manager.createNotificationChannel(
          new NotificationChannel(CHANNEL, "Задачи агента", NotificationManager.IMPORTANCE_DEFAULT));
    Intent open =
        new Intent(context, MainActivity.class)
            .setAction(BuildMonitor.OPEN_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pending =
        PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder =
        Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
    builder
        .setSmallIcon(R.drawable.ic_repository)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(pending)
        .setAutoCancel(true);
    manager.notify(id, builder.build());
  }
}
