package app.githubble.mobile.agent;

import java.util.concurrent.ConcurrentHashMap;

/** In-process task identities. Disk snapshots alone never establish that a task is still alive. */
public final class AgentRunRegistry {
  private static final ConcurrentHashMap<String, AgentRun> RUNS = new ConcurrentHashMap<>();

  private AgentRunRegistry() {}

  private static String key(String owner, String thread) {
    return owner + "\n" + thread;
  }

  public static void put(AgentRun run) {
    if (RUNS.size() > 64)
      for (AgentRun old : RUNS.values())
        if (!old.active()) RUNS.remove(key(old.account, old.conversation), old);
    RUNS.put(key(run.account, run.conversation), run);
  }

  public static AgentRun get(String owner, String thread) {
    return RUNS.get(key(owner, thread));
  }

  public static boolean live(String id, String owner, String thread) {
    AgentRun r = get(owner, thread);
    return r != null && r.id.equals(id) && r.active();
  }

  public static AgentRun current(String owner) {
    for (AgentRun run : RUNS.values()) if (run.account.equals(owner) && run.active()) return run;
    return null;
  }

  public static void remove(AgentRun run) {
    RUNS.remove(key(run.account, run.conversation), run);
  }
}
