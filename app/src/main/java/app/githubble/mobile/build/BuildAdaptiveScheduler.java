package app.githubble.mobile.build;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Adds temporary, OS-managed follow-up checks after the normal periodic watcher discovers
 * an active Actions run. No permanent foreground service is kept alive.
 */
public final class BuildAdaptiveScheduler {
    static final int FAST_JOB_A = 21044;
    static final int FAST_JOB_B = 21045;

    private BuildAdaptiveScheduler() {}

    public static boolean needsFastFollowUp(Context context) {
        if (context == null) return false;
        if (!BuildMonitor.prefs(context).getBoolean(BuildMonitor.PREF_ENABLED, true)) return false;
        Set<String> watched = BuildMonitor.watched(context);
        if (watched.isEmpty()) return false;
        boolean useful = BuildMonitor.prefs(context).getBoolean("build_notifications", true)
                || BuildMonitor.prefs(context).getBoolean("build_auto_apk", false);
        if (!useful) return false;

        String login = BuildMonitor.account(context);
        if (login.isEmpty()) return false;
        long now = System.currentTimeMillis();
        long lastCheck = BuildMonitor.prefs(context).getLong(
                "build_" + login + "_last_check", 0L);
        if (lastCheck <= 0L || now - lastCheck > BuildWatchPolicy.ACTIVE_CACHE_MAX_AGE_MS)
            return false;

        JSONArray rows = BuildMonitor.recent(context);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || BuildMonitor.isReviewCheck(row.optString("event", ""))) continue;
            JSONArray artifacts = row.optJSONArray("artifacts");
            int artifactCount = artifacts == null ? 0 : artifacts.length();
            long updatedAt = BuildWatchPolicy.parseGitHubTime(row.optString("updated_at", ""));
            if (BuildWatchPolicy.needsFastFollowUp(
                    row.optString("status", ""), row.optString("conclusion", ""),
                    artifactCount, updatedAt, now)) return true;
        }
        return false;
    }

    /** Reconcile adaptive checks outside a JobService callback, for example on app resume. */
    public static void reconcile(Context context) {
        afterScan(context, -1);
    }

    /** Called after a build scan. Fast jobs alternate IDs so scheduling never replaces itself. */
    static void afterScan(Context context, int completedJobId) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        JobScheduler scheduler = app.getSystemService(JobScheduler.class);
        if (scheduler == null) return;

        if (!needsFastFollowUp(app)) {
            if (completedJobId != FAST_JOB_A) scheduler.cancel(FAST_JOB_A);
            if (completedJobId != FAST_JOB_B) scheduler.cancel(FAST_JOB_B);
            return;
        }

        if (completedJobId != FAST_JOB_A && completedJobId != FAST_JOB_B) {
            if (scheduler.getPendingJob(FAST_JOB_A) != null
                    || scheduler.getPendingJob(FAST_JOB_B) != null) return;
        }
        int nextJob = completedJobId == FAST_JOB_A ? FAST_JOB_B : FAST_JOB_A;
        if (scheduler.getPendingJob(nextJob) != null) return;

        long latency = adaptiveLatency(app);
        JobInfo job = new JobInfo.Builder(nextJob,
                new ComponentName(app, BuildMonitorJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setMinimumLatency(latency)
                .setOverrideDeadline(Math.min(TimeUnit.MINUTES.toMillis(15), latency * 3L))
                .build();
        scheduler.schedule(job);
    }

    public static void cancel(Context context) {
        if (context == null) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        scheduler.cancel(FAST_JOB_A);
        scheduler.cancel(FAST_JOB_B);
    }

    /** Keep API/battery load bounded when many repositories are being watched. */
    private static long adaptiveLatency(Context context) {
        int count = BuildMonitor.watched(context).size();
        if (count <= 3) return TimeUnit.MINUTES.toMillis(1);
        if (count <= 10) return TimeUnit.MINUTES.toMillis(2);
        return TimeUnit.MINUTES.toMillis(5);
    }
}
