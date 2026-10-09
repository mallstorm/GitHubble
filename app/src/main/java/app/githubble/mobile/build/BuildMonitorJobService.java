package app.githubble.mobile.build;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Persistent low-frequency Actions watcher plus short-lived adaptive follow-up jobs while a
 * build is active. Delivery remains OS-controlled; this is intentionally not presented as FCM.
 */
public final class BuildMonitorJobService extends JobService {
    private static final int JOB_ID = 21043;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer, Future<?>> running = new ConcurrentHashMap<>();

    @Override public boolean onStartJob(JobParameters params) {
        final int jobId = params.getJobId();
        Future<?> task = WORK.submit(() -> {
            boolean retry = false;
            try { BuildMonitor.scan(getApplicationContext(), true); }
            catch (Exception error) { retry = true; }
            finally {
                running.remove(jobId);
                BuildAdaptiveScheduler.afterScan(getApplicationContext(), jobId);
                jobFinished(params, retry);
            }
        });
        running.put(jobId, task);
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        Future<?> task = running.remove(params.getJobId());
        if (task != null) task.cancel(true);
        return true;
    }

    public static void reschedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        scheduler.cancel(JOB_ID);
        if (!BuildMonitor.prefs(context).getBoolean(BuildMonitor.PREF_ENABLED, true)
                || BuildMonitor.watched(context).isEmpty()) {
            BuildAdaptiveScheduler.cancel(context);
            return;
        }
        long period = Math.max(JobInfo.getMinPeriodMillis(), TimeUnit.MINUTES.toMillis(15));
        long flex = Math.max(JobInfo.getMinFlexMillis(), TimeUnit.MINUTES.toMillis(5));
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, BuildMonitorJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(period, Math.min(period, flex))
                .build();
        scheduler.schedule(job);
        BuildAdaptiveScheduler.reconcile(context);
    }

    public static void checkSoon(Context context) {
        if (BuildMonitor.watched(context).isEmpty()
                || !BuildMonitor.prefs(context).getBoolean(BuildMonitor.PREF_ENABLED, true)) {
            BuildAdaptiveScheduler.cancel(context);
            return;
        }
        long last = BuildMonitor.prefs(context).getLong(
                "build_" + BuildMonitor.account(context) + "_last_check", 0L);
        if (System.currentTimeMillis() - last < 2L * 60L * 1000L) {
            BuildAdaptiveScheduler.reconcile(context);
            return;
        }
        Context app = context.getApplicationContext();
        WORK.submit(() -> {
            try { BuildMonitor.scan(app, true); }
            catch (Exception ignored) {}
            finally { BuildAdaptiveScheduler.reconcile(app); }
        });
    }
}
