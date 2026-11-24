package app.githubble.mobile.repository;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loads a Git tree for comparison without ever trusting a truncated listing.
 * With a folder scope only that subtree is read, so a repository larger than the
 * recursive-tree limit of GitHub (100k entries / 7 MB) can still be compared folder by folder.
 * Pure logic: network access is injected through {@link Source}.
 */
public final class RepositoryTreeLoader {
    private RepositoryTreeLoader() { }

    /** Returns the GitHub tree object for a tree SHA, optionally with ?recursive=1. */
    public interface Source {
        JSONObject tree(String treeSha, boolean recursive) throws Exception;
    }

    public static final int MAX_REQUESTS = 400;

    /** Normalises user input like "/app/src/" to "app/src"; empty means the whole repository. */
    public static String normalizeScope(String raw) {
        String scope = raw == null ? "" : raw.trim().replace('\\', '/');
        while (scope.startsWith("/")) scope = scope.substring(1);
        while (scope.endsWith("/")) scope = scope.substring(0, scope.length() - 1);
        if (scope.isEmpty()) return "";
        if (!RepositoryFileComparison.safePath(scope))
            throw new IllegalArgumentException("Недопустимый путь папки: " + raw);
        return scope;
    }

    /**
     * @return {"truncated":false,"tree":[...]} with repository-relative full paths, in the same
     * shape RepositoryFileComparison.compare expects. A missing folder yields an empty tree.
     */
    public static JSONObject load(Source source, String rootTreeSha, String rawScope)
            throws Exception {
        String scope = normalizeScope(rawScope);
        String sha = rootTreeSha;
        if (!scope.isEmpty()) {
            for (String segment : scope.split("/")) {
                JSONObject node = source.tree(sha, false);
                if (node == null || node.optBoolean("truncated", false))
                    throw new IllegalStateException("GitHub вернул неполный каталог: " + segment);
                String next = null;
                JSONArray entries = node.optJSONArray("tree");
                for (int i = 0; entries != null && i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    if (entry != null && segment.equals(entry.optString("path"))) {
                        if (next != null)
                            throw new IllegalStateException("Повтор имени в Git tree: " + segment);
                        if (!"tree".equals(entry.optString("type")))
                            throw new IllegalStateException("«" + scope + "» не является папкой");
                        next = entry.optString("sha", "");
                    }
                }
                if (next == null) return result(new ArrayList<JSONObject>());
                if (!next.matches("[0-9a-fA-F]{40}"))
                    throw new IllegalStateException("Некорректный SHA каталога");
                sha = next;
            }
        }
        String prefix = scope.isEmpty() ? "" : scope + "/";
        List<JSONObject> out = new ArrayList<>();
        JSONObject whole = source.tree(sha, true);
        if (whole != null && !whole.optBoolean("truncated", false)) {
            JSONArray entries = whole.optJSONArray("tree");
            if (entries == null) throw new IllegalStateException("Пустой ответ GitHub");
            for (int i = 0; i < entries.length(); i++) out.add(prefixed(entries.getJSONObject(i), prefix));
            if (out.size() > RepositoryFileComparison.MAX_ENTRIES) throw tooBig(scope);
            return result(out);
        }
        // Truncated: walk level by level; each directory listing is complete on its own.
        List<String[]> queue = new ArrayList<>();
        queue.add(new String[] {sha, prefix});
        int requests = 0;
        for (int head = 0; head < queue.size(); head++) {
            if (++requests > MAX_REQUESTS) throw tooBig(scope);
            JSONObject node = source.tree(queue.get(head)[0], false);
            if (node == null || node.optBoolean("truncated", false))
                throw new IllegalStateException("GitHub вернул неполный каталог. Выберите папку поменьше");
            JSONArray entries = node.optJSONArray("tree");
            if (entries == null) throw new IllegalStateException("Пустой ответ GitHub");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = prefixed(entries.getJSONObject(i), queue.get(head)[1]);
                if ("tree".equals(entry.optString("type"))) {
                    queue.add(new String[] {entry.optString("sha"), entry.optString("path") + "/"});
                } else out.add(entry);
                if (out.size() > RepositoryFileComparison.MAX_ENTRIES) throw tooBig(scope);
            }
        }
        return result(out);
    }

    private static IllegalStateException tooBig(String scope) {
        return new IllegalStateException(scope.isEmpty()
                ? "Репозиторий слишком большой для точного сравнения целиком. Укажите папку"
                : "В папке «" + scope + "» слишком много файлов. Укажите вложенную папку");
    }

    private static JSONObject prefixed(JSONObject entry, String prefix) throws Exception {
        JSONObject copy = new JSONObject(entry.toString());
        copy.put("path", prefix + entry.optString("path", ""));
        return copy;
    }

    private static JSONObject result(List<JSONObject> entries) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject e : entries) array.put(e);
        return new JSONObject().put("truncated", false).put("tree", array);
    }
}
