package app.githubble.mobile.repository;

import app.githubble.mobile.mirror.MirrorPolicy;
import app.githubble.mobile.network.GitHubApiClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Restores precisely ONE regular file from a historical Git commit.
 *
 * Reuses RepositorySelectiveTransfer's account-bound GitHub client and Git
 * object checks, but does not duplicate Mirror Manager's full-branch transfers.
 * It only creates a new review branch and draft PR; no default-branch writes.
 */
public final class RepositoryFileRestore {
    private static final String API = "https://api.github.com/repos/";
    private final RepositorySelectiveTransfer transfer;

    public RepositoryFileRestore(RepositorySelectiveTransfer transfer) {
        this.transfer = transfer;
    }

    public static final class Snapshot {
        public final String accountId, repository, branch, branchSha;
        public final String oldCommit, path, oldBlobSha, oldMode, currentBlobSha, currentMode;
        public final long bytes;
        public final boolean currentlyExists;

        Snapshot(String accountId, String repository, String branch, String branchSha,
                 String oldCommit, String path, RepositoryFileComparison.Entry old,
                 RepositoryFileComparison.Entry current) {
            this.accountId = accountId; this.repository = repository;
            this.branch = branch; this.branchSha = branchSha;
            this.oldCommit = oldCommit; this.path = path;
            this.oldBlobSha = old.sha; this.oldMode = old.mode; this.bytes = old.size;
            this.currentlyExists = current != null;
            this.currentBlobSha = current == null ? "" : current.sha;
            this.currentMode = current == null ? "" : current.mode;
        }
    }

    public static final class Prepared {
        public final String url, branch;
        Prepared(String url, String branch) { this.url = url; this.branch = branch; }
    }

    static String safeHistoricalPath(String path) {
        if (!RepositoryFileComparison.safePath(path))
            throw new IllegalArgumentException("Небезопасное имя файла");
        if (path.split("/", -1).length > 35)
            throw new IllegalArgumentException("Путь файла слишком глубокий");
        return path;
    }

    static RepositoryFileComparison.Entry childEntry(JSONObject tree,
                                                       String segment, String fullPath) {
        if (tree == null || tree.optBoolean("truncated", false))
            throw new IllegalStateException("GitHub вернул неполное дерево Git; операция отменена");
        JSONArray entries = tree.optJSONArray("tree");
        if (entries == null)
            throw new IllegalStateException("GitHub не вернул содержимое дерева");
        RepositoryFileComparison.Entry found = null;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) throw new IllegalStateException("GitHub вернул некорректное дерево");
            if (!segment.equals(entry.optString("path", ""))) continue;
            String sha = entry.optString("sha", "");
            if (!sha.matches("[0-9a-fA-F]{40}"))
                throw new SecurityException("Некорректный SHA объекта " + fullPath);
            if (found != null)
                throw new SecurityException("Повтор имени объекта в Git tree: " + segment);
            found = new RepositoryFileComparison.Entry(fullPath, sha,
                    entry.optString("mode", ""), entry.optString("type", ""),
                    entry.optLong("size", -1L));
        }
        return found;
    }

    /**
     * Non-recursive traversal preserves correctness for very large repositories:
     * a GitHub recursive tree marked truncated would not be safe to inspect.
     */
    private static RepositoryFileComparison.Entry findPath(
            GitHubApiClient api, String repo, String commitSha, String path) throws Exception {
        String nextSha = RepositorySelectiveTransfer.treeSha(api, repo, commitSha);
        String[] levels = safeHistoricalPath(path).split("/");
        for (int i = 0; i < levels.length; i++) {
            JSONObject node = api.getJsonFresh(API + repo + "/git/trees/" + nextSha);
            RepositoryFileComparison.Entry found = childEntry(node, levels[i], path);
            if (found == null) return null;
            if (i == levels.length - 1) return found;
            if (!"tree".equals(found.type))
                throw new IllegalStateException("На месте каталога находится файл: " + levels[i]);
            nextSha = found.sha;
        }
        throw new IllegalStateException("Пустой путь Git");
    }

    static void requireRestorable(RepositoryFileComparison.Entry old,
                                  RepositoryFileComparison.Entry current) {
        if (old == null) throw new IllegalArgumentException(
                "Файл отсутствует в выбранном историческом коммите. Откройте более ранний коммит");
        String disabled = RepositoryFileComparison.safeToCopy(old);
        if (disabled != null) throw new IllegalStateException(disabled);
        if (current != null) {
            if (!"blob".equals(current.type)
                    || (!"100644".equals(current.mode) && !"100755".equals(current.mode)))
                throw new IllegalStateException(
                        "В актуальной версии файл является ссылкой или специальным объектом");
            if (current.sha.equalsIgnoreCase(old.sha) && current.mode.equals(old.mode))
                throw new IllegalStateException("Файл уже совпадает с выбранной версией");
        }
    }

    static void validateBlob(JSONObject raw, RepositoryFileComparison.Entry file)
            throws Exception {
        if (raw == null || !file.sha.equalsIgnoreCase(raw.optString("sha", ""))
                || !"base64".equals(raw.optString("encoding", "")))
            throw new SecurityException("Невозможно проверить Git blob выбранной версии");
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(raw.getString("content"));
        } catch (IllegalArgumentException invalid) {
            throw new SecurityException("Невозможно декодировать файл из GitHub");
        }
        if (bytes.length != file.size
                || bytes.length > RepositoryFileComparison.MAX_FILE_BYTES
                || !RepositorySelectiveTransfer.sha1GitBlob(bytes).equalsIgnoreCase(file.sha))
            throw new SecurityException("Git blob не соответствует SHA и размеру");
        String header = new String(bytes, 0, Math.min(bytes.length, 180), StandardCharsets.UTF_8);
        if (header.startsWith("version https://git-lfs.github.com/spec/v1"))
            throw new IllegalStateException(
                    "Нельзя восстановить только LFS-указатель без содержимого LFS");
    }

    /** Read-only preview; accounts, permissions and source objects are verified. */
    public Snapshot preview(String accountId, String repository, String commit, String path)
            throws Exception {
        MirrorPolicy.requireRepository(repository);
        safeHistoricalPath(path);
        if (commit == null || !commit.matches("[0-9a-fA-F]{40}"))
            throw new IllegalArgumentException("Некорректный SHA исторического коммита");
        GitHubApiClient api = transfer.client(accountId);
        JSONObject repo = api.getJsonFresh(API + repository);
        if (repo.optBoolean("archived", false))
            throw new IllegalStateException("Архивный репозиторий недоступен для восстановления");
        JSONObject permissions = repo.optJSONObject("permissions");
        if (permissions == null || !permissions.optBoolean("push", false))
            throw new SecurityException("Для восстановления требуется право Contents: write");
        String defaultBranch = RepositorySelectiveTransfer.branch(repo);
        String head = RepositorySelectiveTransfer.head(api, repository, defaultBranch);
        RepositoryFileComparison.Entry old = findPath(api, repository, commit, path);
        RepositoryFileComparison.Entry current = findPath(api, repository, head, path);
        requireRestorable(old, current);
        validateBlob(api.getJsonFresh(API + repository + "/git/blobs/" + old.sha), old);
        return new Snapshot(accountId, repository, defaultBranch, head, commit, path, old, current);
    }

    /**
     * Revalidates source and destination before creating an isolated branch.
     * Ambiguous write failures are NEVER retried automatically.
     */
    public Prepared prepareDraftPr(Snapshot snapshot) throws Exception {
        if (snapshot == null) throw new IllegalArgumentException("Сначала проверьте версию файла");
        safeHistoricalPath(snapshot.path);
        GitHubApiClient api = transfer.client(snapshot.accountId);
        JSONObject repo = api.getJsonFresh(API + snapshot.repository);
        if (repo.optBoolean("archived", false)
                || !snapshot.branch.equals(RepositorySelectiveTransfer.branch(repo)))
            throw new IllegalStateException("Репозиторий или основная ветка изменились");
        JSONObject permissions = repo.optJSONObject("permissions");
        if (permissions == null || !permissions.optBoolean("push", false))
            throw new SecurityException("Отсутствует право записи в репозиторий");
        String head = RepositorySelectiveTransfer.head(api, snapshot.repository, snapshot.branch);
        if (!head.equals(snapshot.branchSha))
            throw new IllegalStateException("Основная ветка изменилась. Повторите проверку файла");
        RepositoryFileComparison.Entry old = findPath(
                api, snapshot.repository, snapshot.oldCommit, snapshot.path);
        RepositoryFileComparison.Entry current = findPath(
                api, snapshot.repository, snapshot.branchSha, snapshot.path);
        requireRestorable(old, current);
        if (!old.sha.equals(snapshot.oldBlobSha) || !old.mode.equals(snapshot.oldMode)
                || (current != null) != snapshot.currentlyExists
                || (current != null && (!current.sha.equals(snapshot.currentBlobSha)
                    || !current.mode.equals(snapshot.currentMode))))
            throw new IllegalStateException("Версия файла изменилась. Повторите проверку");
        validateBlob(api.getJsonFresh(API + snapshot.repository + "/git/blobs/" + old.sha), old);

        String baseTree = RepositorySelectiveTransfer.treeSha(
                api, snapshot.repository, snapshot.branchSha);
        JSONArray patch = new JSONArray().put(new JSONObject()
                .put("path", snapshot.path)
                .put("mode", old.mode)
                .put("type", "blob")
                .put("sha", old.sha));
        JSONObject tree = api.postJson(API + snapshot.repository + "/git/trees",
                new JSONObject().put("base_tree", baseTree).put("tree", patch), true);
        String newTreeSha = tree.optString("sha", "");
        if (!newTreeSha.matches("[0-9a-f]{40}"))
            throw new IllegalStateException("GitHub не создал дерево восстановления");
        JSONObject commit = api.postJson(API + snapshot.repository + "/git/commits",
                new JSONObject().put("message", "restore: " + snapshot.path
                        + " from " + snapshot.oldCommit.substring(0, 12))
                        .put("tree", newTreeSha)
                        .put("parents", new JSONArray().put(snapshot.branchSha)), true);
        String newCommit = commit.optString("sha", "");
        if (!newCommit.matches("[0-9a-f]{40}"))
            throw new IllegalStateException("GitHub не подтвердил коммит восстановления");

        if (!snapshot.branchSha.equals(
                RepositorySelectiveTransfer.head(api, snapshot.repository, snapshot.branch)))
            throw new IllegalStateException("Основная ветка изменилась до публикации "
                    + "ветки восстановления. Повторите проверку");

        String reviewBranch = "restore/file-" +
                UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        api.postJson(API + snapshot.repository + "/git/refs",
                new JSONObject().put("ref", "refs/heads/" + reviewBranch)
                        .put("sha", newCommit), true);
        String body = "Восстановление одной версии файла через GitHubble.\n\n"
                + "Файл: " + snapshot.path + "\n"
                + "Версия из коммита: " + snapshot.oldCommit + "\n"
                + "Базовый SHA: " + snapshot.branchSha + "\n\n"
                + "Изменения не объединяются автоматически. Проверьте diff и CI.";
        try {
            JSONObject pr = api.postJson(API + snapshot.repository + "/pulls",
                    new JSONObject()
                            .put("title", "Restore " + snapshot.path + " from history")
                            .put("head", reviewBranch)
                            .put("base", snapshot.branch)
                            .put("draft", true)
                            .put("body", body), true);
            String url = pr.optString("html_url", "");
            if (!url.startsWith("https://github.com/" + snapshot.repository + "/pull/"))
                throw new IllegalStateException("GitHub не подтвердил Pull Request");
            return new Prepared(url, reviewBranch);
        } catch (Exception uncertain) {
            throw new IllegalStateException("Ветка " + reviewBranch
                    + " создана, но Pull Request не подтверждён. "
                    + "Откройте GitHub и проверьте результат до повторной попытки: "
                    + uncertain.getMessage(), uncertain);
        }
    }
}
