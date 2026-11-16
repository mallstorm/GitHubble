package app.githubble.mobile.agent;

import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Immutable scope pinned when a run starts; no implicit Workspace or active-chat lookup. */
public final class TaskSession {
    public final String accountKey, projectId, taskId;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Runnable contextGuard;
    public TaskSession(String account, String project, String task, Runnable guard) {
        if (account == null || account.isBlank() || task == null || task.isBlank())
            throw new IllegalArgumentException("Нужны аккаунт и задача");
        accountKey = account; projectId = project == null ? "" : project; taskId = task;
        contextGuard = guard == null ? () -> {} : guard;
    }
    public void cancel() { cancelled.set(true); }
    public void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Задача остановлена");
        contextGuard.run();
    }
}
