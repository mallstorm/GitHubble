package app.githubble.mobile.copilot;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.githubble.mobile.cache.EntityCache;
import app.githubble.mobile.controller.CopilotAgentController;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubGraphQlClient;
import app.githubble.mobile.security.SecureStore;

/** Periodic native JobScheduler monitor for Copilot task transitions. */
public final class CopilotTaskMonitorJobService extends JobService {
    private static final int PERIODIC_JOB_ID = 22321;
    private static final int IMMEDIATE_JOB_ID = 22322;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> running;

    @Override public boolean onStartJob(JobParameters params) {
        running = executor.submit(() -> {
            boolean retry = false;
            SharedPreferences prefs = getSharedPreferences("githubble", MODE_PRIVATE);
            prefs.edit().putLong("copilot_monitor_last_started", System.currentTimeMillis())
                    .putString("copilot_monitor_last_error", "").apply();
            try {
                SecureStore secureStore = new SecureStore(this);
                if (secureStore.get("github_token").isEmpty()) {
                    prefs.edit().putString("copilot_monitor_last_result", "GitHub не подключён").apply();
                } else {
                    EntityCache cache = new EntityCache(this);
                    GitHubApiClient api = new GitHubApiClient(secureStore, cache);
                    CopilotAgentController controller = new CopilotAgentController(api, new GitHubGraphQlClient(api));
                    JSONObject root = controller.listUserTasks(1, "", false);
                    JSONArray tasks = CopilotAgentController.tasks(root);
                    JSONObject previous = parseObject(prefs.getString("copilot_monitor_states", "{}"));
                    JSONObject current = new JSONObject();
                    int active = 0;
                    int transitions = 0;
                    for (int i = 0; i < tasks.length(); i++) {
                        JSONObject task = tasks.optJSONObject(i);
                        if (task == null) continue;
                        String id = task.optString("id", "");
                        String state = task.optString("state", "");
                        if (id.isEmpty()) continue;
                        current.put(id, state);
                        if (CopilotAgentController.isActiveState(state)) active++;
                        String oldState = previous.optString(id, "");
                        if (!oldState.isEmpty() && !oldState.equals(state) && shouldNotify(state)) {
                            transitions++;
                            CopilotNotificationHelper.notifyTransition(
                                    this,
                                    id,
                                    CopilotAgentController.repositoryFromTask(task),
                                    task.optString("name", "Задача Copilot"),
                                    state);
                        }
                    }
                    prefs.edit()
                            .putString("copilot_monitor_states", current.toString())
                            .putInt("copilot_task_count", tasks.length())
                            .putInt("copilot_active_count", active)
                            .putLong("copilot_monitor_last_success", System.currentTimeMillis())
                            .putString("copilot_monitor_last_result", "Проверено задач: " + tasks.length() + ", изменений: " + transitions)
                            .apply();
                    cache.close();
                }
            } catch (Exception error) {
                retry = true;
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                prefs.edit().putString("copilot_monitor_last_error", message)
                        .putString("copilot_monitor_last_result", "Ошибка фоновой проверки").apply();
            } finally {
                prefs.edit().putLong("copilot_monitor_last_finished", System.currentTimeMillis()).apply();
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

    public static void schedule(Context context, int intervalMinutes) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        long interval = TimeUnit.MINUTES.toMillis(Math.max(15, Math.min(24 * 60, intervalMinutes)));
        long minimum = JobInfo.getMinPeriodMillis();
        if (interval < minimum) interval = minimum;
        long flex = Math.max(JobInfo.getMinFlexMillis(), interval / 6L);
        JobInfo info = new JobInfo.Builder(PERIODIC_JOB_ID,
                new ComponentName(context, CopilotTaskMonitorJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(interval, Math.min(flex, interval))
                .build();
        scheduler.schedule(info);
    }

    public static void runNow(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo info = new JobInfo.Builder(IMMEDIATE_JOB_ID,
                new ComponentName(context, CopilotTaskMonitorJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(0)
                .setOverrideDeadline(1000)
                .build();
        scheduler.schedule(info);
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.cancel(PERIODIC_JOB_ID);
            scheduler.cancel(IMMEDIATE_JOB_ID);
        }
    }

    private static boolean shouldNotify(String state) {
        return "completed".equals(state) || "waiting_for_user".equals(state)
                || "failed".equals(state) || "timed_out".equals(state)
                || "cancelled".equals(state);
    }

    private static JSONObject parseObject(String value) {
        try { return new JSONObject(value == null ? "{}" : value); }
        catch (Exception ignored) { return new JSONObject(); }
    }
}
