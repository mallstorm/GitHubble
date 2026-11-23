package app.githubble.mobile.repository;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.mirror.MirrorPolicy;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * File-granular transfer between existing repositories, DIFFERENT from Mirror
 * Manager's full-history sync. Selected blobs are copied into a new review
 * branch and draft PR. Both default branches remain untouched.
 */
public final class RepositorySelectiveTransfer {
    private static final String API = "https://api.github.com/repos/";
    private final GitHubAccountStore accounts;

    public RepositorySelectiveTransfer(GitHubAccountStore accounts) {
        this.accounts = accounts;
    }

    public static final class Comparison {
        public final String source, destination, sourceAccountId, destinationAccountId;
        public final String sourceBranch, destinationBranch, sourceSha, destinationSha;
        public final boolean sourcePrivate, destinationPrivate;
        public final List<RepositoryFileComparison.Delta> changes;

        Comparison(String source, String destination, String sourceAccountId,
                   String destinationAccountId, String sourceBranch,
                   String destinationBranch, String sourceSha, String destinationSha,
                   boolean sourcePrivate, boolean destinationPrivate,
                   List<RepositoryFileComparison.Delta> changes) {
            this.source = source; this.destination = destination;
            this.sourceAccountId = sourceAccountId; this.destinationAccountId = destinationAccountId;
            this.sourceBranch = sourceBranch; this.destinationBranch = destinationBranch;
            this.sourceSha = sourceSha; this.destinationSha = destinationSha;
            this.sourcePrivate = sourcePrivate; this.destinationPrivate = destinationPrivate;
            this.changes = changes;
        }
    }

    public static final class PreparedPullRequest {
        public final String url, branch;
        public final int files;
        PreparedPullRequest(String url, String branch, int files) {
            this.url = url; this.branch = branch; this.files = files;
        }
    }

    private GitHubAccountStore.Account account(String id) {
        for (GitHubAccountStore.Account a : accounts.listAccounts())
            if (a.id.equals(id)) return a;
        throw new IllegalStateException("GitHub-аккаунт не подключён");
    }

    private GitHubApiClient client(String accountId) throws Exception {
        GitHubAccountStore.Account item = account(accountId);
        String token = accounts.tokenForAccount(item.id);
        if (token == null || token.isEmpty())
            throw new IllegalStateException("Повторно авторизуйте @" + item.login);
        GitHubSession session = new GitHubSession(token, item.login, 0L);
        GitHubApiClient api = new GitHubApiClient(new GitHubSession.Source() {
            @Override public GitHubSession capture() { return session; }
            @Override public boolean isCurrent(GitHubSession current) { return current == session; }
            @Override public void reauthorization(GitHubSession current, boolean required) { }
        }, null);
        JSONObject who = api.getJsonFresh("https://api.github.com/user");
        if (!item.login.equalsIgnoreCase(who.optString("login", "")))
            throw new SecurityException("Токен не соответствует выбранному аккаунту");
        return api;
    }

    private static String enc(String value) throws Exception {
        return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static String branch(JSONObject repository) {
        String name = repository.optString("default_branch", "");
        if (name.isEmpty() || name.length() > 250 || name.contains("..")
                || name.startsWith("/") || name.endsWith("/") || name.contains("\\"))
            throw new IllegalStateException("Неподдерживаемое имя основной ветки");
        return name;
    }

    private static String head(GitHubApiClient api, String repo, String branch) throws Exception {
        JSONObject ref = api.getJsonFresh(API + repo + "/git/ref/heads/" + enc(branch));
        JSONObject object = ref.optJSONObject("object");
        String sha = object == null ? "" : object.optString("sha", "");
        if (!sha.matches("[0-9a-f]{40}"))
            throw new IllegalStateException("Не удалось проверить Git SHA ветки");
        return sha;
    }

    private static String treeSha(GitHubApiClient api, String repo, String commitSha)
            throws Exception {
        JSONObject commit = api.getJsonFresh(API + repo + "/git/commits/" + commitSha);
        JSONObject tree = commit.optJSONObject("tree");
        String sha = tree == null ? "" : tree.optString("sha", "");
        if (!sha.matches("[0-9a-f]{40}"))
            throw new IllegalStateException("Git commit не содержит проверяемого дерева");
        return sha;
    }

    private static JSONObject recursiveTree(GitHubApiClient api, String repo, String commitSha)
            throws Exception {
        return api.getJsonFresh(API + repo + "/git/trees/" + treeSha(api, repo, commitSha)
                + "?recursive=1");
    }

    /** Read-only: works for any pair of repos accessible to the selected accounts. */
    public Comparison compare(String source, String sourceAccountId,
                              String destination, String destinationAccountId) throws Exception {
        MirrorPolicy.requireDifferent(source, destination);
        GitHubAccountStore.Account destinationAccount = account(destinationAccountId);
        if (!destination.substring(0, destination.indexOf('/'))
                .equalsIgnoreCase(destinationAccount.login))
            throw new IllegalArgumentException("Репозиторий назначения должен принадлежать выбранному аккаунту");

        GitHubApiClient src = client(sourceAccountId);
        GitHubApiClient dst = client(destinationAccountId);
        JSONObject sourceInfo = src.getJsonFresh(API + source);
        JSONObject destinationInfo = dst.getJsonFresh(API + destination);
        if (sourceInfo.optBoolean("archived", false) || destinationInfo.optBoolean("archived", false))
            throw new IllegalStateException("Архивные репозитории не поддерживаются");
        JSONObject permission = destinationInfo.optJSONObject("permissions");
        if (permission != null && !permission.optBoolean("push", false))
            throw new SecurityException("У аккаунта назначения нет права записи в репозиторий");

        String srcBranch = branch(sourceInfo), dstBranch = branch(destinationInfo);
        String srcSha = head(src, source, srcBranch), dstSha = head(dst, destination, dstBranch);
        List<RepositoryFileComparison.Delta> deltas = RepositoryFileComparison.compare(
                recursiveTree(src, source, srcSha), recursiveTree(dst, destination, dstSha));
        return new Comparison(source, destination, sourceAccountId, destinationAccountId,
                srcBranch, dstBranch, srcSha, dstSha,
                sourceInfo.optBoolean("private", false),
                destinationInfo.optBoolean("private", false), deltas);
    }

    private static void verifyHeads(Comparison comparison,
                                    GitHubApiClient source, GitHubApiClient destination)
            throws Exception {
        if (!comparison.sourceSha.equals(head(source, comparison.source, comparison.sourceBranch)))
            throw new IllegalStateException("Исходная ветка изменилась: выполните сравнение заново");
        if (!comparison.destinationSha.equals(
                head(destination, comparison.destination, comparison.destinationBranch)))
            throw new IllegalStateException("Ветка назначения изменилась: выполните сравнение заново");
    }

    private static String sha1GitBlob(byte[] data) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + data.length + "\0").getBytes(StandardCharsets.UTF_8));
        sha1.update(data);
        byte[] digest = sha1.digest();
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    static List<RepositoryFileComparison.Delta> requireSelection(
            Comparison compare, List<String> selected) {
        if (selected == null || selected.isEmpty()
                || selected.size() > RepositoryFileComparison.MAX_SELECTED_FILES)
            throw new IllegalArgumentException("Выберите от 1 до 12 файлов");
        Set<String> unique = new HashSet<>(selected);
        if (unique.size() != selected.size())
            throw new IllegalArgumentException("В списке есть дублирующиеся файлы");
        long total = 0;
        List<RepositoryFileComparison.Delta> result = new ArrayList<>();
        for (String path : selected) {
            RepositoryFileComparison.Delta matched = null;
            for (RepositoryFileComparison.Delta item : compare.changes)
                if (item.path.equals(path)) { matched = item; break; }
            if (matched == null || !matched.selectable())
                throw new IllegalArgumentException("Нельзя выбрать файл: " + path);
            total += matched.source.size;
            if (total > RepositoryFileComparison.MAX_SELECTED_BYTES)
                throw new IllegalArgumentException("Максимальный размер выбранных файлов — 4 МиБ");
            result.add(matched);
        }
        return result;
    }

    /**
     * The ONLY write path: copy selected raw Git blobs, construct an immutable
     * tree/commit and publish a NEW draft PR. It does not mutate the base refs
     * or use git push --force, and never auto-merges changes.
     */
    public PreparedPullRequest preparePullRequest(Comparison comparison,
                                                   List<String> selected) throws Exception {
        List<RepositoryFileComparison.Delta> selection = requireSelection(comparison, selected);
        if (comparison.sourcePrivate && !comparison.destinationPrivate)
            throw new SecurityException("Нельзя переносить приватные файлы в публичный репозиторий");

        GitHubApiClient source = client(comparison.sourceAccountId);
        GitHubApiClient destination = client(comparison.destinationAccountId);
        JSONObject sourceRepo = source.getJsonFresh(API + comparison.source);
        JSONObject destinationRepo = destination.getJsonFresh(API + comparison.destination);
        if (!branch(sourceRepo).equals(comparison.sourceBranch)
                || !branch(destinationRepo).equals(comparison.destinationBranch)
                || sourceRepo.optBoolean("archived", false)
                || destinationRepo.optBoolean("archived", false)
                || (comparison.sourcePrivate && !sourceRepo.optBoolean("private", false))
                || destinationRepo.optBoolean("private", false) != comparison.destinationPrivate)
            throw new IllegalStateException("Настройки репозитория изменились — повторите сравнение");
        verifyHeads(comparison, source, destination);

        JSONArray treeEntries = new JSONArray();
        for (RepositoryFileComparison.Delta item : selection) {
            JSONObject blob = source.getJsonFresh(API + comparison.source
                    + "/git/blobs/" + item.source.sha);
            if (!item.source.sha.equalsIgnoreCase(blob.optString("sha", ""))
                    || !"base64".equals(blob.optString("encoding", "")))
                throw new SecurityException("GitHub вернул неподтверждённый blob: " + item.path);
            byte[] decoded;
            try {
                decoded = Base64.getMimeDecoder().decode(blob.getString("content"));
            } catch (IllegalArgumentException invalid) {
                throw new SecurityException("Некорректное содержимое файла " + item.path);
            }
            if (decoded.length != item.source.size
                    || decoded.length > RepositoryFileComparison.MAX_FILE_BYTES
                    || !sha1GitBlob(decoded).equalsIgnoreCase(item.source.sha))
                throw new SecurityException("Не совпал Git SHA содержимого: " + item.path);
            String asText = new String(decoded, StandardCharsets.UTF_8);
            if (asText.startsWith("version https://git-lfs.github.com/spec/v1\n"))
                throw new IllegalStateException("Файл хранится в Git LFS; выборочный перенос LFS "
                        + "пока не поддерживается: " + item.path);
            JSONObject created = destination.postJson(API + comparison.destination + "/git/blobs",
                    new JSONObject().put("content", Base64.getEncoder().encodeToString(decoded))
                            .put("encoding", "base64"), true);
            String createdSha = created.optString("sha", "");
            if (!createdSha.equalsIgnoreCase(item.source.sha))
                throw new SecurityException("GitHub создал blob с неожиданным SHA: " + item.path);
            treeEntries.put(new JSONObject()
                    .put("path", item.path).put("mode", item.source.mode)
                    .put("type", "blob").put("sha", createdSha));
        }
        // An unrelated commit arriving during copying cannot be overwritten.
        verifyHeads(comparison, source, destination);

        String originalTree = treeSha(destination, comparison.destination, comparison.destinationSha);
        JSONObject newTree = destination.postJson(API + comparison.destination + "/git/trees",
                new JSONObject().put("base_tree", originalTree).put("tree", treeEntries), true);
        String treeSha = newTree.optString("sha", "");
        if (!treeSha.matches("[0-9a-f]{40}"))
            throw new IllegalStateException("GitHub не создал дерево переноса");

        String message = "chore(compare): selected files from " + comparison.source;
        JSONObject commit = destination.postJson(API + comparison.destination + "/git/commits",
                new JSONObject().put("message", message).put("tree", treeSha)
                        .put("parents", new JSONArray().put(comparison.destinationSha)), true);
        String commitSha = commit.optString("sha", "");
        if (!commitSha.matches("[0-9a-f]{40}"))
            throw new IllegalStateException("GitHub не создал коммит выбранных файлов");

        String reviewBranch = "compare/selected-" +
                UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        destination.postJson(API + comparison.destination + "/git/refs",
                new JSONObject().put("ref", "refs/heads/" + reviewBranch)
                        .put("sha", commitSha), true);

        StringBuilder body = new StringBuilder("Выборочный перенос файлов GitHubble.\n\n");
        body.append("Источник: ").append(comparison.source).append(" @ ")
                .append(comparison.sourceSha).append("\n");
        body.append("Назначение: ").append(comparison.destination).append(" @ ")
                .append(comparison.destinationSha).append("\n\n");
        body.append("Выбрано файлов: ").append(selection.size()).append("\n");
        for (RepositoryFileComparison.Delta item : selection)
            body.append("- ").append(item.status).append(": ")
                    .append(item.path.replace("\r", "").replace("\n", "")).append("\n");
        body.append("\nНикакие ветки main и исходного репозитория не изменены. ")
                .append("Проверьте diff и CI перед merge.\n");
        try {
            JSONObject pr = destination.postJson(API + comparison.destination + "/pulls",
                    new JSONObject().put("title", "Selective files from " + comparison.source)
                            .put("head", reviewBranch)
                            .put("base", comparison.destinationBranch)
                            .put("draft", true)
                            .put("body", body.toString()), true);
            String url = pr.optString("html_url", "");
            if (!url.startsWith("https://github.com/" + comparison.destination + "/pull/"))
                throw new IllegalStateException("GitHub не подтвердил ссылку Pull Request");
            return new PreparedPullRequest(url, reviewBranch, selection.size());
        } catch (Exception failed) {
            // The reviewed branch already exists, but no default ref was altered.
            // Never retry this non-idempotent request automatically.
            throw new IllegalStateException("Новая ветка " + reviewBranch
                    + " создана. Pull Request не подтверждён; проверьте GitHub перед повтором. "
                    + failed.getMessage(), failed);
        }
    }
}
