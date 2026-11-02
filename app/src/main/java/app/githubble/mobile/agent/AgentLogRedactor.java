package app.githubble.mobile.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Redacts obvious credentials and keeps only likely build-error excerpts from GitHub Actions logs.
 * Best-effort scrubbing is NOT a security boundary: UI requires explicit consent before sending
 * any diagnostic text to an external AI provider.
 */
public final class AgentLogRedactor {
    private static final int MAX_EXCERPT = 11000;
    private static final Pattern ANSI = Pattern.compile("\\u001b\\[[0-9;]*[A-Za-z]");
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)(?:gh[pousr]_[A-Za-z0-9_]{15,}|github_pat_[A-Za-z0-9_]{15,}|"
          + "sk-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]{25,})");
    private static final Pattern AUTH = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*(?:bearer|basic)\\s+)[^\\s\"']+");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|client[_-]?secret)"
          + "\\s*[:=]\\s*)[^\\s,;\"']+");
    private static final Pattern KEY_INLINE = Pattern.compile(
            "(?i)(\\\"(?:api_key|access_token|refresh_token|password|client_secret)\\\"\\s*:\\s*\\\")[^\\\"]+");
    private static final Pattern LONG_OPAQUE = Pattern.compile("[A-Za-z0-9+/=_-]{125,}");
    private static final Pattern MARKERS = Pattern.compile(
            "(?i)(\\berror\\b|\\bfailed\\b|\\bexception\\b|\\bpanic\\b|\\bfatal\\b|"
          + "compilation failed|unresolved reference|cannot find symbol|could not resolve|"
          + "execution failed for task|build failed|^e: |^> task .* failed)");

    private AgentLogRedactor() {}

    public static String redact(String source) {
        if (source == null || source.isEmpty()) return "";
        String clean = ANSI.matcher(source).replaceAll("");
        clean = TOKEN.matcher(clean).replaceAll("[REDACTED]");
        clean = AUTH.matcher(clean).replaceAll("$1[REDACTED]");
        clean = NAMED_SECRET.matcher(clean).replaceAll("$1[REDACTED]");
        clean = KEY_INLINE.matcher(clean).replaceAll("$1[REDACTED]");
        clean = LONG_OPAQUE.matcher(clean).replaceAll("[LONG_VALUE_REDACTED]");
        return clean;
    }

    /** Keeps a small error neighborhood, never full raw logs or unbounded build output. */
    public static String extract(String raw) {
        if (raw == null || raw.isEmpty()) return "(журнал пуст)";
        String[] lines = raw.split("\\r?\\n", -1);
        List<Integer> marked = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (MARKERS.matcher(lines[i]).find()) marked.add(i);
        }
        if (marked.isEmpty()) return "(автоматически выделить ошибки не удалось; проверьте журнал Actions вручную)";
        boolean[] select = new boolean[lines.length];
        for (int index : marked) {
            for (int j = Math.max(0, index - 2); j <= Math.min(lines.length - 1, index + 3); j++)
                select[j] = true;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (!select[i]) continue;
            String line = redact(lines[i]);
            if (line.length() > 600) line = line.substring(0, 600) + " …[TRUNCATED]";
            if (result.length() + line.length() + 2 > MAX_EXCERPT) {
                result.append("\n[EXCERPT_LIMIT]");
                break;
            }
            result.append(line).append('\n');
        }
        return result.length() == 0 ? "(ошибки не найдены)" : result.toString();
    }
}
