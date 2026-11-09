package app.githubble.mobile.agent;

import android.app.Activity;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Workers outlive a configuration change; prompts bind to the current Activity, never a dead one.
 */
public final class AgentUiSession {
  public static final ExecutorService WORKERS = Executors.newFixedThreadPool(2);
  private static WeakReference<Activity> current = new WeakReference<>(null);

  private AgentUiSession() {}

  public static synchronized void attach(Activity activity) {
    current = new WeakReference<>(activity);
  }

  public static synchronized void detach(Activity activity) {
    if (current.get() == activity) current.clear();
  }

  public static synchronized Activity activity() {
    return current.get();
  }
}
