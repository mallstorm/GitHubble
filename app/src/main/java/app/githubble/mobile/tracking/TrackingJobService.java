package app.githubble.mobile.tracking;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.security.SecureStore;
import app.githubble.mobile.cache.EntityCache;

/** Platform JobScheduler worker; no external background-processing dependency is required. */
public final class TrackingJobService extends JobService {
    private static final int PERIODIC_JOB_ID = 21021;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> running;

    @Override public boolean onStartJob(JobParameters params) {
        running = executor.submit(() -> {
            boolean retry = false;
            SharedPreferences prefs = getSharedPreferences("githubble", MODE_PRIVATE);
            long startedAt = System.currentTimeMillis();
            prefs.edit().putLong("tracking_job_last_started", startedAt).putString("tracking_job_last_error", "").apply();
            try {
                TrackingDatabase db = new TrackingDatabase(this);
                if (db.getDevelopers(true).isEmpty()) {
                    cancel(this);
                    prefs.edit().putString("tracking_job_last_result", "Нет активных наблюдений").apply();
                } else {
                    EntityCache entityCache = new EntityCache(this);
                    SecureStore store=new SecureStore(this);
                    new app.githubble.mobile.auth.OAuthManager(this,store).refreshIfNeeded(app.githubble.mobile.BuildConfig.GITHUB_CLIENT_ID);
                    GitHubApiClient api = new GitHubApiClient(store, entityCache).bindCurrentSession();
                    TrackingEngine.CheckResult result = new TrackingEngine(this, db, api).checkAll();
                    TrackingNotificationHelper.notifyEvents(this, result.newEvents);
                    retry = result.failedDevelopers > 0 && result.checkedDevelopers == 0;
                    prefs.edit()
                        .putLong("tracking_job_last_success", System.currentTimeMillis())
                        .putString("tracking_job_last_result", "Проверено разработчиков: " + result.checkedDevelopers + ", репозиториев: " + result.checkedRepositories + ", новых событий: " + result.newEvents.size())
                        .apply();
                }
            } catch (Exception error) {
                retry = true;
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                prefs.edit().putString("tracking_job_last_error", message).putString("tracking_job_last_result", "Ошибка фоновой проверки").apply();
            } finally {
                prefs.edit().putLong("tracking_job_last_finished", System.currentTimeMillis()).apply();
                jobFinished(params, retry);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (running != null) running.cancel(true);
        return true;
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    public static void schedule(Context context, int intervalHours) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        long interval = TimeUnit.HOURS.toMillis(Math.max(1, Math.min(24, intervalHours)));
        long minimum = JobInfo.getMinPeriodMillis();
        if (interval < minimum) interval = minimum;
        long flex = Math.max(JobInfo.getMinFlexMillis(), interval / 6L);
        JobInfo info = new JobInfo.Builder(PERIODIC_JOB_ID,
                new ComponentName(context, TrackingJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(interval, Math.min(flex, interval))
                .build();
        scheduler.schedule(info);
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(PERIODIC_JOB_ID);
    }
}
