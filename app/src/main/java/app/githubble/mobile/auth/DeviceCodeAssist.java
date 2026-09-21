package app.githubble.mobile.auth;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PersistableBundle;

import app.githubble.mobile.util.DeviceCodeFormatter;

/**
 * The 8-character user code is only kept for the current, expiring Device Flow.
 * No GitHub access token, password or device_code is ever sent to accessibility.
 */
public final class DeviceCodeAssist {
    private static final String PREFS = "githubble_device_login_assist";
    private static final String CODE = "user_code";
    private static final String EXPIRES = "expires_at";

    private DeviceCodeAssist() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void begin(Context context, String userCode, long expiresAtMillis) {
        clear(context);
        String normalized = DeviceCodeFormatter.normalize(userCode);
        if (!normalized.matches("[A-Z0-9]{8}") || expiresAtMillis <= System.currentTimeMillis()) return;
        prefs(context).edit().putString(CODE, normalized)
            .putLong(EXPIRES, expiresAtMillis).apply();
    }

    public static String currentCode(Context context) {
        SharedPreferences p = prefs(context);
        if (System.currentTimeMillis() >= p.getLong(EXPIRES, 0L)) {
            clear(context);
            return "";
        }
        return p.getString(CODE, "");
    }

    public static boolean copy(Context context) {
        String code = currentCode(context);
        if (!code.matches("[A-Z0-9]{8}")) return false;
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return false;
        ClipData clip = ClipData.newPlainText("GitHub one-time device code", code);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        return true;
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

}
