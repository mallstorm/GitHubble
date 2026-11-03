package app.githubble.mobile.agent;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validates untrusted AI file proposals before explicit user approval. */
public final class AgentFilePlan {
    public static final int MAX_FILES = 12;
    public static final int MAX_FILE_BYTES = 80 * 1024;
    public static final int MAX_TOTAL_BYTES = 240 * 1024;
    public final String summary;
    public final String commitMessage;
    public final JSONArray files;
    public final int totalBytes;

    private AgentFilePlan(String summary, String message, JSONArray files, int totalBytes) {
        this.summary = summary; this.commitMessage = message; this.files = files;
        this.totalBytes = totalBytes;
    }

    public static AgentFilePlan parse(String generated) throws Exception {
        if (generated == null) throw new IllegalArgumentException("Модель не вернула план");
        String source = generated.trim();
        String fence = "\u0060\u0060\u0060";
        if (source.startsWith(fence)) {
            int first = source.indexOf('\n');
            int end = source.lastIndexOf(fence);
            if (first >= 0 && end > first) source = source.substring(first + 1, end).trim();
        }
        JSONObject root = new JSONObject(source);
        JSONArray proposed = root.optJSONArray("files");
        if (proposed == null || proposed.length() == 0 || proposed.length() > MAX_FILES)
            throw new IllegalArgumentException("План должен содержать 1–" + MAX_FILES + " текстовых файлов");
        Set<String> paths = new HashSet<>();
        JSONArray checked = new JSONArray();
        int total = 0;
        for (int i = 0; i < proposed.length(); i++) {
            JSONObject file = proposed.optJSONObject(i);
            if (file == null) throw new IllegalArgumentException("Некорректный элемент плана");
            String path = file.optString("path", "");
            String contents = file.optString("content", "");
            validatePath(path);
            if (!paths.add(path.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Повторяется путь: " + path);
            int bytes = contents.getBytes(StandardCharsets.UTF_8).length;
            if (bytes == 0 || bytes > MAX_FILE_BYTES)
                throw new IllegalArgumentException("Недопустимый размер файла: " + path);
            total += bytes;
            if (total > MAX_TOTAL_BYTES)
                throw new IllegalArgumentException("Слишком большой набор файлов");
            checked.put(new JSONObject().put("path", path).put("content", contents));
        }
        String summary = root.optString("summary", "Изменение файлов").trim();
        String message = root.optString("commit_message", "feat: implement requested changes").trim();
        if (message.isEmpty()) message = "feat: implement requested changes";
        if (message.length() > 130) message = message.substring(0, 130);
        if (summary.length() > 700) summary = summary.substring(0, 700);
        return new AgentFilePlan(summary, message, checked, total);
    }

    public static void validatePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 220 || path.startsWith("/")
                || path.startsWith("\\") || path.contains("\\") || path.contains("//")
                || path.indexOf('\0') >= 0 || path.contains("\n") || path.contains("\r")
                || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Недопустимый путь файла");
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || ".git".equalsIgnoreCase(segment))
                throw new IllegalArgumentException("Недопустимый путь: " + path);
        }
        if(!WorkspaceChangeReview.writablePath(path))
            throw new IllegalArgumentException("Защищённый путь файла: "+path);
        if (path.startsWith(".github/workflows/") && !path.endsWith(".yml") && !path.endsWith(".yaml"))
            throw new IllegalArgumentException("Workflow должен быть YAML-файлом");
    }

    public String preview() {
        StringBuilder b = new StringBuilder();
        b.append(summary).append("\n\nФайлов: ").append(files.length())
                .append(" · ").append(totalBytes).append(" байт\n\n");
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            String path = f.optString("path", "");
            String value = f.optString("content", "");
            b.append("• ").append(path).append(" (")
                    .append(value.getBytes(StandardCharsets.UTF_8).length).append(" байт)\n");
            if (path.startsWith(".github/workflows/")) b.append("  ⚠ Изменение GitHub Actions\n");
        }
        b.append("\nКоммит: ").append(commitMessage)
                .append("\n\nИзменения будут записаны только после подтверждения.");
        return b.toString();
    }
}
