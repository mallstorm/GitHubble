package app.githubble.mobile.agent;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.security.SecureStore;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Account-bound Git Data API workspace. Proposes from a pinned HEAD and performs
 * one atomic tree/commit/ref operation after the user explicitly approves.
 */
public final class AgentGitWorkspace {
    // Serializes approved plans across workspace instances, so a second tap rechecks the
    // advanced HEAD before it can create another commit from the same proposal.
    private final GitHubAccountStore accounts;
    private final SecureStore vault;

    public static final class Snapshot {
        public final String head, tree, overview;
        public Snapshot(String head, String tree, String overview) {
            this.head = head; this.tree = tree; this.overview = overview;
        }
    }

    public AgentGitWorkspace(GitHubAccountStore accounts, SecureStore vault) {
        this.accounts = accounts; this.vault = vault;
    }

    /**
     * Account check and credential capture happen atomically under the same lock that
     * account switching uses. The returned client is bound to that session: if the account
     * or token changes mid-operation, every later request fails instead of running as
     * another user.
     */
    private GitHubApiClient client(String expected) {
        synchronized (SecureStore.SESSION_LOCK) {
            String current = accounts.activeCacheKey();
            if (expected == null || "guest".equals(expected) || !expected.equals(current))
                throw new IllegalStateException("GitHub-аккаунт был переключён");
            if (vault.get("github_token").isEmpty())
                throw new IllegalStateException("GitHub-аккаунт не авторизован");
            try {
                return new GitHubApiClient(vault).bindCurrentSession();
            } catch (GitHubApiClient.SessionChangedException changed) {
                throw new IllegalStateException("GitHub-аккаунт был переключён", changed);
            }
        }
    }

    public static void validateRepository(String repo, String branch) {
        if (repo == null || !repo.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
            throw new IllegalArgumentException("Некорректное имя GitHub-репозитория");
        if (branch == null || !branch.matches("[A-Za-z0-9._/-]{1,160}") ||
                branch.startsWith("/") || branch.endsWith("/") || branch.contains("..")
                || branch.contains("//") || branch.startsWith("-") || branch.endsWith("."))
            throw new IllegalArgumentException("Некорректное имя GitHub-ветки");
    }

    private static String base(String repository) {
        return "https://api.github.com/repos/" + repository;
    }

    private Snapshot inspect(GitHubApiClient api, String repo, String branch, boolean includeNames) throws Exception {
        String endpoint = base(repo);
        JSONObject ref = api.getJson(endpoint + "/git/ref/heads/" + branch);
        JSONObject target = ref.optJSONObject("object");
        String head = target == null ? "" : target.optString("sha");
        if (!head.matches("[a-fA-F0-9]{40}"))
            throw new IllegalStateException("GitHub не вернул SHA ветки");
        JSONObject commit = api.getJson(endpoint + "/git/commits/" + head);
        JSONObject tree = commit.optJSONObject("tree");
        String treeSha = tree == null ? "" : tree.optString("sha");
        if (!treeSha.matches("[a-fA-F0-9]{40}"))
            throw new IllegalStateException("GitHub не вернул SHA дерева");
        StringBuilder names = new StringBuilder("Ветка: " + branch + "\nФайлы:\n");
        if (includeNames) {
            JSONObject listing = api.getJson(endpoint + "/git/trees/" + treeSha + "?recursive=1");
            JSONArray entries = listing.optJSONArray("tree");
            int shown = 0;
            for (int i = 0; entries != null && i < entries.length() && shown < 85; i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null && "blob".equals(entry.optString("type"))) {
                    names.append("- ").append(entry.optString("path")).append('\n');
                    shown++;
                }
            }
            if (listing.optBoolean("truncated")) names.append("(частичный список)\n");
        }
        return new Snapshot(head, treeSha, names.toString());
    }

    public Snapshot inspect(String account, String repository, String branch) throws Exception {
        validateRepository(repository, branch);
        return inspect(client(account), repository, branch, true);
    }

    /**
     * A short content sample is sent to a third-party model ONLY after explicit UI consent.
     * Skips likely secrets, binary files and hidden dotfiles.
     */
    public String shortSourceContext(String account, String repository, String branch) throws Exception {
        validateRepository(repository, branch);
        GitHubApiClient api = client(account);
        JSONObject ref = api.getJson(base(repository) + "/git/ref/heads/" + branch);
        JSONObject target = ref.optJSONObject("object");
        String head = target == null ? "" : target.optString("sha");
        JSONObject commit = api.getJson(base(repository) + "/git/commits/" + head);
        String treeSha = commit.optJSONObject("tree").optString("sha");
        JSONObject listing = api.getJson(base(repository) + "/git/trees/" + treeSha + "?recursive=1");
        JSONArray entries = listing.optJSONArray("tree");
        StringBuilder context = new StringBuilder();
        int count = 0;
        for (int i = 0; entries != null && i < entries.length() && count < 5; i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item == null || !"blob".equals(item.optString("type"))) continue;
            String path = item.optString("path");
            if (!safeContextPath(path) || item.optLong("size", 100000) > 5500) continue;
            JSONObject blob = api.getJson(base(repository) + "/git/blobs/" + item.optString("sha"));
            if (!"base64".equals(blob.optString("encoding"))) continue;
            byte[] decoded = Base64.decode(blob.optString("content").replace("\n", ""),
                    Base64.DEFAULT);
            if (decoded.length > 5500) continue;
            context.append("\nFILE ").append(path).append("\n")
                    .append(new String(decoded, StandardCharsets.UTF_8)).append("\nEND FILE\n");
            count++;
        }
        return context.toString();
    }

    private static boolean safeContextPath(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (path.startsWith(".") || path.contains("/.") || lower.contains("secret") || lower.contains("token")
                || lower.contains("password") || lower.contains("private") || lower.contains("credential")
                || lower.contains("key") || lower.contains("env") || lower.contains("config.json")
                || lower.contains("keystore") || lower.contains("gradle.properties")) return false;
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".md")
                || lower.endsWith(".xml") || lower.endsWith(".gradle") || lower.endsWith(".kts");
    }

}
