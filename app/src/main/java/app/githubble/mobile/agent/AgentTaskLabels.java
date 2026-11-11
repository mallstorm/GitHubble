package app.githubble.mobile.agent;

/** User-facing names of task states and execution modes (pure, unit-tested). */
public final class AgentTaskLabels {
  private AgentTaskLabels() {}

  public static String state(String name) {
    switch (name == null ? "" : name) {
      case "CREATED": return "Создана";
      case "ANALYZING": return "Анализ";
      case "PLANNING": return "Планирование";
      case "APPROVAL": return "Согласование";
      case "EXECUTING": return "Выполнение";
      case "TESTING": return "Проверка";
      case "BUILDING": return "Сборка";
      case "VERIFYING": return "Проверка результата";
      case "DELIVERING": return "Доставка";
      case "COMPLETED": return "Завершена";
      case "PAUSED": return "Приостановлена";
      case "WAITING_PERMISSION": return "Ждёт подтверждения";
      case "WAITING_EXTERNAL": return "Ждёт внешнего действия";
      case "RECOVERING": return "Восстановление";
      case "FAILED": return "Ошибка";
      case "CANCELLED": return "Отменена";
      case "ROLLED_BACK": return "Откат";
      default: return name == null ? "" : name;
    }
  }

  public static String mode(String name) {
    switch (name == null ? "" : name) {
      case "AUTO": return "авто";
      case "SEMI": return "полуавто";
      case "MANUAL": return "ручной";
      default: return name == null ? "" : name;
    }
  }

  /** "Состояние: Сборка · режим: авто", or empty when the run has no journal. */
  public static String line(String taskState, String mode) {
    if (taskState == null || taskState.isEmpty()) return "";
    String m = mode(mode);
    return "Состояние: " + state(taskState) + (m.isEmpty() ? "" : " · режим: " + m);
  }

  /** States from which the user may ask the agent to continue with the recorded context. */
  public static boolean resumable(String taskState) {
    return "PAUSED".equals(taskState) || "FAILED".equals(taskState)
        || "WAITING_EXTERNAL".equals(taskState);
  }
}
