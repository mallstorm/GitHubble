package app.githubble.mobile.build;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns raw GitHub API failures into short Russian explanations (no JSON in the UI). */
public final class ActionsErrorText {
  private static final Pattern CODE = Pattern.compile("HTTP\\s*(\\d{3})");
  private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

  private ActionsErrorText() {}

  public static String describe(String raw) {
    if (raw == null || raw.trim().isEmpty()) return "Неизвестная ошибка GitHub.";
    int code = 0;
    Matcher c = CODE.matcher(raw);
    if (c.find()) code = Integer.parseInt(c.group(1));
    String message = "";
    Matcher m = MESSAGE.matcher(raw);
    if (m.find()) message = m.group(1).replace("\\\"", "\"").trim();
    String lower = (message + " " + raw).toLowerCase(java.util.Locale.ROOT);
    if (code == 402 || lower.contains("payment method"))
      return "GitHub требует привязанный способ оплаты в аккаунте для этой функции (HTTP 402). Без него квоты кэша недоступны; действуют стандартные лимиты.";
    if (code == 401) return "Сессия GitHub недействительна. Войдите заново (HTTP 401).";
    if (lower.contains("rate limit")) return "Достигнут лимит запросов GitHub. Повторите позже.";
    if (code == 403) return "Недостаточно прав: нужны Administration и Actions (HTTP 403).";
    if (code == 404) return "Недоступно: репозиторий не найден или функция не поддерживается для вашего тарифа (HTTP 404).";
    if (code == 422) return "GitHub отклонил значение (HTTP 422)" + (message.isEmpty() ? "." : ": " + shorten(message));
    if (!message.isEmpty()) return shorten(message) + (code > 0 ? " (HTTP " + code + ")" : "");
    String plain = raw.replaceAll("\\{.*", "").trim();
    return shorten(plain.isEmpty() ? "Ошибка GitHub" : plain) + (code > 0 && !plain.contains("HTTP") ? " (HTTP " + code + ")" : "");
  }

  private static String shorten(String value) {
    return value.length() > 140 ? value.substring(0, 140) + "…" : value;
  }
}
