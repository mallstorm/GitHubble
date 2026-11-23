package app.githubble.mobile.repository;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure tree-to-tree file comparison. Mirror Manager compares commit ancestry and
 * complete tree identities; this provides previously missing FILE-level deltas.
 * Never infers deletions from destination-only files.
 */
public final class RepositoryFileComparison {
    private RepositoryFileComparison() { }

    public static final int MAX_ENTRIES = 15000;
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    public static final int MAX_SELECTED_FILES = 12;
    public static final int MAX_SELECTED_BYTES = 4 * 1024 * 1024;
    public static final String ADD = "add", UPDATE = "update", TARGET_ONLY = "target_only";

    public static final class Entry {
        public final String path, sha, mode, type;
        public final long size;
        Entry(String path, String sha, String mode, String type, long size) {
            this.path = path; this.sha = sha; this.mode = mode;
            this.type = type; this.size = size;
        }
    }

    public static final class Delta {
        public final String path, status;
        public final Entry source, target;
        public final String disabledReason;
        Delta(String path, String status, Entry source, Entry target) {
            this.path = path; this.status = status;
            this.source = source; this.target = target;
            this.disabledReason = safeToCopy(source);
        }
        public boolean selectable() {
            return !TARGET_ONLY.equals(status) && disabledReason == null;
        }
    }

    static String safeToCopy(Entry entry) {
        if (entry == null) return "Есть только в репозитории назначения";
        if (!safePath(entry.path)) return "Недопустимый путь";
        if (!"blob".equals(entry.type)) return "Ссылки на Git submodules не переносятся";
        if (!"100644".equals(entry.mode) && !"100755".equals(entry.mode))
            return "Символические ссылки и специальные файлы не переносятся";
        if (entry.size < 0 || entry.size > MAX_FILE_BYTES)
            return "Размер файла превышает 1 МиБ";
        String lower = entry.path.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals(".gitmodules") || lower.equals(".env") || lower.startsWith(".env.")
                || lower.endsWith(".pem") || lower.endsWith(".p12")
                || lower.endsWith(".key") || lower.endsWith(".keystore")
                || lower.contains("/.env") || lower.contains("/id_rsa")
                || lower.startsWith(".github/workflows/"))
            return "Служебные файлы, ключи и workflows нельзя переносить выборочно";
        return null;
    }

    public static boolean safePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 600
                || path.startsWith("/") || path.endsWith("/") || path.contains("//")
                || path.indexOf('\\') >= 0) return false;
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.equalsIgnoreCase(".git")) return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 32 || c == 127) return false;
        }
        return true;
    }

    private static Map<String, Entry> parse(JSONObject result) {
        if (result == null || result.optBoolean("truncated", false))
            throw new IllegalStateException("GitHub вернул неполное дерево. Сравнение отменено");
        JSONArray tree = result.optJSONArray("tree");
        if (tree == null || tree.length() > MAX_ENTRIES)
            throw new IllegalStateException("Размер дерева не поддерживается для точного сравнения");
        Map<String, Entry> byPath = new LinkedHashMap<>();
        for (int i = 0; i < tree.length(); i++) {
            JSONObject item = tree.optJSONObject(i);
            if (item == null) throw new IllegalArgumentException("Пустая запись Git tree");
            String type = item.optString("type", ""), path = item.optString("path", "");
            if ("tree".equals(type)) continue;
            if (!safePath(path)) throw new IllegalStateException("Неверный путь Git tree");
            String sha = item.optString("sha", ""), mode = item.optString("mode", "");
            if (!sha.matches("[0-9a-fA-F]{40}") || (!"blob".equals(type) && !"commit".equals(type)))
                throw new IllegalStateException("Неподдерживаемый Git object: " + path);
            if (byPath.put(path, new Entry(path, sha, mode, type, item.optLong("size", -1))) != null)
                throw new IllegalStateException("Повтор пути в Git tree");
        }
        return byPath;
    }

    public static List<Delta> compare(JSONObject sourceTree, JSONObject destinationTree) {
        Map<String, Entry> source = parse(sourceTree), dest = parse(destinationTree);
        List<Delta> changes = new ArrayList<>();
        for (Entry from : source.values()) {
            Entry to = dest.remove(from.path);
            if (to == null) changes.add(new Delta(from.path, ADD, from, null));
            else if (!from.sha.equalsIgnoreCase(to.sha) || !from.mode.equals(to.mode)
                    || !from.type.equals(to.type))
                changes.add(new Delta(from.path, UPDATE, from, to));
        }
        for (Entry onlyDest : dest.values())
            changes.add(new Delta(onlyDest.path, TARGET_ONLY, null, onlyDest));
        Collections.sort(changes, (a,b)->a.path.compareToIgnoreCase(b.path));
        return Collections.unmodifiableList(changes);
    }
}
