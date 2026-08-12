package app.githubble.mobile.util;

import java.util.ArrayList;
import java.util.List;

/** Path safety rules shared by folder/ZIP repository imports. */
public final class RepositoryPathPolicy {
    private RepositoryPathPolicy() { }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        String[] parts = value.split("/+");
        List<String> clean = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new IllegalArgumentException("Путь выходит за пределы выбранной папки");
            if (part.indexOf('\0') >= 0) throw new IllegalArgumentException("Недопустимый нулевой символ в имени файла");
            clean.add(part);
        }
        return String.join("/", clean);
    }

    public static boolean shouldIgnore(String normalizedPath) {
        String path = normalize(normalizedPath);
        if (path.isEmpty()) return true;
        String[] parts = path.split("/");
        for (String part : parts) {
            if (".git".equals(part) || "__MACOSX".equals(part)) return true;
        }
        String name = parts[parts.length - 1];
        return ".DS_Store".equals(name) || "Thumbs.db".equalsIgnoreCase(name);
    }

    public static boolean isWorkflow(String normalizedPath) {
        String path = normalize(normalizedPath);
        return path.startsWith(".github/workflows/") && path.length() > ".github/workflows/".length();
    }
}
