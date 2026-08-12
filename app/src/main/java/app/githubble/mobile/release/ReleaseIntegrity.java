package app.githubble.mobile.release;

import app.githubble.mobile.BuildConfig;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import org.json.JSONObject;

/** Exposes non-secret release metadata for diagnostics and RC verification. */
public final class ReleaseIntegrity {
    private ReleaseIntegrity() { }

    public static JSONObject inspect(Context context) {
        JSONObject out = new JSONObject();
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), 0);
            ApplicationInfo app = context.getApplicationInfo();
            out.put("package", context.getPackageName());
            out.put("version_name", info.versionName == null ? "" : info.versionName);
            out.put("version_code", android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
            out.put("debuggable", (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            out.put("feature_freeze", false);
            out.put("channel", BuildConfig.RC_CHANNEL);
        } catch (Exception ignored) { }
        return out;
    }
}
