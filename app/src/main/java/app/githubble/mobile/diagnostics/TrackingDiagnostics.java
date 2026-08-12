package app.githubble.mobile.diagnostics;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.List;

/** Read-only diagnostics for Android/MIUI background restrictions. */
public final class TrackingDiagnostics {
    private TrackingDiagnostics() {}

    public static JSONObject inspect(Context context) {
        JSONObject out = new JSONObject();
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            List<JobInfo> jobs = js == null ? null : js.getAllPendingJobs();
            out.put("scheduled", jobs != null && !jobs.isEmpty());
            out.put("pending_jobs", jobs == null ? 0 : jobs.size());
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean ignored = Build.VERSION.SDK_INT < 23 || (pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName()));
            out.put("battery_optimization_ignored", ignored);
            out.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
            out.put("is_miui_family", isMiuiFamily());
        } catch (Exception ignored) {}
        return out;
    }

    public static Intent batteryOptimizationIntent(Context context) {
        if (Build.VERSION.SDK_INT < 23) return new Intent(Settings.ACTION_SETTINGS);
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }

    public static Intent appDetailsIntent(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName()));
    }

    private static boolean isMiuiFamily() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }
}
