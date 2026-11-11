package app.githubble.mobile.agent;

import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-owned task state. A model's prose can never complete an operation. */
public final class AgentRun {
  public final String id = UUID.randomUUID().toString(), account, conversation;
  private final LongSupplier clock;
  private final long started, at;
  private final JSONArray operations = new JSONArray();
  private String model = "";

  public synchronized void model(String value) {
    model = value == null ? "" : value;
  }

  private String state = "running", stage = "Получаю ответ модели", preview = "", current = "";
  private long elapsed, revision = 1;
  private int iteration;
  private Runnable changed = () -> {};
  private app.githubble.mobile.agent.core.AgentRunJournal journal;

  /** Durable record of this run (task states and tool ledger); best-effort, never breaks the run. */
  public synchronized void attachJournal(app.githubble.mobile.agent.core.AgentRunJournal value) {
    journal = value;
    try {
      if (journal != null) journal.start();
    } catch (RuntimeException ignored) {
      journal = null;
    }
  }

  /** Refuses a repeat of a creation/merge call that already succeeded or has an unknown outcome. */
  public synchronized void requireNotDuplicate() {
    JSONObject op = operation(current);
    if (op != null && !op.optString("duplicate").isEmpty())
      throw new IllegalStateException(op.optString("duplicate"));
  }

  private static String canonical(JSONObject args) {
    java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
    java.util.Iterator<String> keys = args.keys();
    while (keys.hasNext()) {
      String k = keys.next();
      sorted.put(k, String.valueOf(args.opt(k)));
    }
    return sorted.toString();
  }

  public AgentRun(String account, String conversation, LongSupplier clock) {
    this.account = account;
    this.conversation = conversation;
    this.clock = clock;
    started = clock.getAsLong();
    at = System.currentTimeMillis();
  }

  public synchronized void observe(Runnable listener) {
    changed = listener == null ? () -> {} : listener;
  }

  private void notifyChanged() {
    revision++;
    changed.run();
  }

  public synchronized boolean active() {
    return state.equals("running") || state.equals("approval");
  }

  public synchronized void round(int number) {
    if (!active()) return;
    iteration = number;
    preview = "";
    stage = "Получаю ответ модели";
    notifyChanged();
  }

  public synchronized void preview(String text) {
    if (!active()) return;
    String next = AgentAnswerPreview.visible(text);
    if (next.length() > 80000) next = next.substring(0, 80000);
    if (!preview.equals(next)) {
      preview = next;
      notifyChanged();
    }
  }

  public synchronized String begin(String name, JSONObject args) {
    if (!active()) return "";
    try {
      current = UUID.randomUUID().toString();
      String target =
          args.optString("path", args.optString("repository", args.optString("query", "")));
      JSONObject op =
          new JSONObject()
              .put("id", current)
              .put("name", name)
              .put("target", clip(target, 220))
              .put("iteration", iteration)
              .put("state", "running")
              .put("started", clock.getAsLong())
              .put("elapsed_ms", 0);
      operations.put(op);
      if (journal != null)
        try {
          String repeat = journal.toolBegin(current, name, canonical(args));
          if (repeat != null) op.put("duplicate", repeat);
        } catch (RuntimeException ignored) {
          journal = null;
        }
      stage = label(name);
      preview = "";
      notifyChanged();
      return current;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public synchronized void result(String opId, String status, String error) {
    if (!active()) return;
    JSONObject op = operation(opId);
    if (op == null) return;
    boolean uncertain = op.optBoolean("remote_dispatched") && !"succeeded".equals(status);
    try {
      op.put("state", uncertain ? "unknown" : status)
          .put("elapsed_ms", Math.max(0, clock.getAsLong() - op.optLong("started")))
          .put(
              "error",
              uncertain
                  ? "Результат отправленного запроса неизвестен. Проверьте GitHub перед повтором."
                  : clip(error, 400));
      current = "";
      state = "running";
      stage = "Продолжаю задачу";
      if (journal != null)
        try {
          journal.toolResult(opId, status, error);
        } catch (RuntimeException ignored) {
          journal = null;
        }
      notifyChanged();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    if (uncertain)
      throw new IllegalStateException(
          "Результат удалённой операции неизвестен. Проверьте состояние перед повтором.");
  }

  public synchronized void files(JSONArray files, boolean applied) {
    if (!active()) return;
    JSONObject op = operation(current);
    if (op == null) return;
    try {
      op.put("files", new JSONArray(files.toString())).put("applied", applied);
      notifyChanged();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public synchronized void note(String text) {
    if (active()) {
      stage = clip(text, 180);
      notifyChanged();
    }
  }

  public synchronized void approval(boolean waiting) {
    if (active()) {
      state = waiting ? "approval" : "running";
      if (journal != null)
        try {
          journal.approval(waiting);
        } catch (RuntimeException ignored) {
          journal = null;
        }
      stage = waiting ? "Ожидает подтверждения" : "Продолжаю задачу";
      notifyChanged();
    }
  }

  public synchronized void remoteDispatched() {
    JSONObject op = operation(current);
    if (op != null && active())
      try {
        op.put("remote_dispatched", true);
        if (journal != null) journal.toolDispatched(current);
        notifyChanged();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
  }

  public synchronized void finish(String status, String message) {
    if (!active()) return;
    elapsed = Math.max(0, clock.getAsLong() - started);
    if (journal != null)
      try {
        journal.finish(status, message);
      } catch (RuntimeException ignored) {
        /* journal problems never change the run outcome */
      }
    state = status;
    stage = clip(message, 300);
    if ("succeeded".equals(status)) preview = "";
    for (int i = 0; i < operations.length(); i++) {
      JSONObject op = operations.optJSONObject(i);
      if (op != null && "running".equals(op.optString("state")))
        try {
          boolean unknown = op.optBoolean("remote_dispatched");
          op.put("state", unknown ? "unknown" : "succeeded".equals(status) ? "interrupted" : status)
              .put("elapsed_ms", Math.max(0, clock.getAsLong() - op.optLong("started")));
          if (unknown)
            op.put("error", "Запрос уже отправлен. Проверьте состояние в GitHub перед повтором.");
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
    }
    notifyChanged();
    changed = () -> {};
  }

  private JSONObject operation(String key) {
    for (int i = 0; i < operations.length(); i++) {
      JSONObject op = operations.optJSONObject(i);
      if (op != null && key.equals(op.optString("id"))) return op;
    }
    return null;
  }

  public synchronized JSONObject snapshot() {
    try {
      return new JSONObject()
          .put("schema", 1)
          .put("id", id)
          .put("account", account)
          .put("conversation", conversation)
          .put("at", at)
          .put("model", model)
          .put("state", state)
          .put("stage", stage)
          .put("iteration", iteration)
          .put("revision", revision)
          .put("elapsed_ms", active() ? Math.max(0, clock.getAsLong() - started) : elapsed)
          .put("preview", preview)
          .put("operations", new JSONArray(operations.toString()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String time(long ms) {
    long seconds = Math.max(0, ms) / 1000;
    return seconds >= 3600
        ? String.format(
            java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
  }

  private static String clip(String s, int max) {
    return s == null ? "" : s.substring(0, Math.min(s.length(), max));
  }

  public static boolean read(String name) {
    return java.util.Arrays.asList(
            "read_file",
            "repository_read_file",
            "repository_file",
            "repository_files",
            "list_files",
            "search_files",
            "symbol_index",
            "repository_info")
        .contains(name);
  }

  public static String icon(String name) {
    if (name.contains("search") || name.equals("symbol_index")) return "🔎";
    if (name.contains("list") || name.contains("import") || name.equals("create_workspace"))
      return "📂";
    if (name.contains("write") || name.contains("update") || name.contains("edit")) return "✏️";
    if (name.contains("delete")) return "🗑️";
    if (name.contains("build") || name.contains("workflow")) return "⚙️";
    if (name.contains("publish") || name.contains("commit")) return "↗";
    return "📄";
  }

  public static String label(String name) {
    switch (name) {
      case "repository_read_file":
        return "Чтение файла GitHub";
      case "repository_branches":
        return "Ветки репозитория";
      case "repository_issues":
        return "Задачи репозитория";
      case "repository_pulls":
        return "Pull Requests";
      case "issue_details":
        return "Сведения о задаче";
      case "pull_request_details":
        return "Сведения о Pull Request";
      case "create_branch":
        return "Создание ветки";
      case "update_repository":
        return "Изменение репозитория";
      case "create_repository":
        return "Создание репозитория";
      case "delete_repository":
        return "Удаление репозитория";
      case "create_issue":
        return "Создание задачи";
      case "update_issue":
        return "Изменение задачи";
      case "create_pull_request":
        return "Создание Pull Request";
      case "merge_pull_request":
        return "Объединение Pull Request";
      case "dispatch_workflow":
        return "Запуск GitHub Actions";
      case "list_files":
        return "Структура проекта";
      case "read_file":
        return "Чтение файла";
      case "search_files":
        return "Поиск в проекте";
      case "symbol_index":
        return "Поиск символов";
      case "web_search":
        return "Поиск в интернете";
      case "write_files":
        return "Изменения файлов";
      case "import_repository":
        return "Импорт репозитория";
      case "attach_repository":
        return "Подключение репозитория";
      case "create_workspace":
        return "Создание Workspace";
      case "publish_workspace":
        return "Публикация в GitHub";
      case "repository_files":
        return "Файлы репозитория";
      case "list_repositories":
        return "Репозитории аккаунта";
      case "repository_info":
        return "Сведения о репозитории";
      case "github_account":
        return "Аккаунт GitHub";
      case "recent_build_runs":
        return "Запуски сборок";
      case "build_run_diagnostics":
        return "Проверка сборки";
      default:
        return name.replace('_', ' ');
    }
  }
}
