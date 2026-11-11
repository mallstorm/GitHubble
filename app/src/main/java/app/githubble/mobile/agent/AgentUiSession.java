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

  private static volatile boolean foreground;

  private AgentUiSession() {}

  public static void foreground(boolean value) {
    foreground = value;
  }

  /** True while the app UI is resumed; used to decide whether a notification is needed. */
  public static boolean foreground() {
    return foreground;
  }

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
