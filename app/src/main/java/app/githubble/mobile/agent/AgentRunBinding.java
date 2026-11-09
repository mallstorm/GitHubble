package app.githubble.mobile.agent;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Coalesces stream UI updates and checkpoints outside the UI thread. */
public final class AgentRunBinding {
  private static final ExecutorService DISK = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean posted = new AtomicBoolean();
  private long saved;

  public AgentRunBinding(AgentRun run, AgentProjectStore store, Runnable refresh) {
    run.observe(
        () -> {
          synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            if (saved == 0 || now - saved >= 700 || !run.active()) {
              saved = now;
              JSONObject snapshot = run.snapshot();
              DISK.execute(() -> store.upsertRun(run.account, run.conversation, snapshot));
            }
          }
          if (posted.compareAndSet(false, true))
            main.postDelayed(
                () -> {
                  posted.set(false);
                  refresh.run();
                },
                160);
        });
    DISK.execute(() -> store.upsertRun(run.account, run.conversation, run.snapshot()));
  }
}
