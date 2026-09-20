package app.githubble.mobile.auth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Explicit, non-exported receiver for the Chrome Custom Tab code strip.
 * There is no code in the Intent / PendingIntent: it is fetched from the
 * app-private, short-lived Device Flow session.
 */
public final class DeviceCodeToolbarReceiver extends BroadcastReceiver {
    public static final String ACTION_COPY = "app.githubble.mobile.devicecode.COPY";
    public static final String ACTION_FILL = "app.githubble.mobile.devicecode.FILL";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (ACTION_COPY.equals(intent.getAction())) {
            Toast.makeText(context, DeviceCodeAssist.copy(context)
                ? "Скопированы все 8 символов. Удерживайте первое поле GitHub → Вставить."
                : "Код истёк. Начните вход заново.", Toast.LENGTH_LONG).show();
        } else if (ACTION_FILL.equals(intent.getAction())) {
            if (!DeviceCodeAssist.isAccessibilityEnabled(context)) {
                Toast.makeText(context, "Автозаполнение выключено. В GitHubble включите службу в настройках специальных возможностей.", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(context, DeviceCodeAssist.arm(context)
                ? "Пробуем заполнить код на странице GitHub. Проверьте символы перед подтверждением."
                : "Код истёк. Начните вход заново.", Toast.LENGTH_LONG).show();
        }
    }
}
