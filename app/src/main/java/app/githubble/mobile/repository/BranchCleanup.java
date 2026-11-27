package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Branch hygiene: find merged and stale branches, delete them with an undo, rename, sync a fork. */
public final class BranchCleanup {
    private BranchCleanup() {}

    public static final class Info {
        public final String name, sha; public final boolean protectedBranch;
        public int ahead = -1, behind; public String date = "";
        Info(String name, String sha, boolean protectedBranch) { this.name = name; this.sha = sha; this.protectedBranch = protectedBranch; }
        public boolean merged() { return ahead == 0; }
    }

    public static String defaultBranch(GitHubApiClient api, String repo) throws Exception { return Gh.object(api, Gh.repo(repo)).optString("default_branch", "main"); }

    public static boolean isFork(GitHubApiClient api, String repo) throws Exception { return Gh.object(api, Gh.repo(repo)).optBoolean("fork"); }

    public static List<Info> list(GitHubApiClient api, String repo, String base) throws Exception {
        List<Info> out = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            JSONArray a = Gh.array(api, Gh.repo(repo) + "/branches?per_page=100&page=" + page);
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null) continue;
                String name = x.optString("name");
                JSONObject commit = x.optJSONObject("commit");
                if (!name.isEmpty() && !name.equals(base)) out.add(new Info(name, commit == null ? "" : commit.optString("sha"), x.optBoolean("protected")));
            }
            if (a.length() < 100) break;
        }
        return out;
    }

    /** Fills ahead/behind/date of one branch relative to the base branch. */
    static void fill(Info info, JSONObject compare) {
        info.ahead = compare.optInt("ahead_by"); info.behind = compare.optInt("behind_by");
        JSONArray commits = compare.optJSONArray("commits");
        JSONObject source = null;
        if (info.ahead == 0) { JSONObject mb = compare.optJSONObject("merge_base_commit"); source = mb; }
        else if (commits != null && commits.length() > 0 && commits.length() == compare.optInt("total_commits", -1)) source = commits.optJSONObject(commits.length() - 1);
        JSONObject c = source == null ? null : source.optJSONObject("commit"), who = c == null ? null : c.optJSONObject("committer");
        info.date = who == null ? "" : who.optString("date", "");
    }

    public static void analyze(GitHubApiClient api, String repo, String base, Info info) throws Exception {
        fill(info, Gh.object(api, Gh.repo(repo) + "/compare/" + Gh.segment(base) + "..." + Gh.segment(info.name)));
    }

    /** Whole days since the ISO date, or -1 when the date is missing. */
    public static long ageDays(String iso, long nowMillis) {
        if (iso == null || iso.isEmpty()) return -1;
        try { return Math.max(0, (nowMillis - java.time.Instant.parse(iso).toEpochMilli()) / 86_400_000L); }
        catch (java.time.format.DateTimeParseException bad) { return -1; }
    }

    public static boolean stale(Info info, int days, long nowMillis) { long age = ageDays(info.date, nowMillis); return age >= days; }

    public static void delete(GitHubApiClient api, String repo, String base, Info info) throws Exception {
        if (info.name.equals(base)) throw new IllegalArgumentException("Ветку по умолчанию удалять нельзя");
        if (info.protectedBranch) throw new IllegalArgumentException("Ветка «" + info.name + "» защищена");
        try { api.delete(Gh.repo(repo) + "/git/refs/heads/" + Gh.segment(info.name)); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404 && e.statusCode != 422) throw e; }
    }

    public static void restore(GitHubApiClient api, String repo, Info info) throws Exception {
        if (info.sha.isEmpty()) throw new IllegalArgumentException("Нет sha для восстановления");
        api.postJson(Gh.repo(repo) + "/git/refs", new JSONObject().put("ref", "refs/heads/" + info.name).put("sha", info.sha), true);
    }

    public static void rename(GitHubApiClient api, String repo, String branch, String newName) throws Exception {
        api.postJson(Gh.repo(repo) + "/branches/" + Gh.segment(branch) + "/rename", new JSONObject().put("new_name", RepoRevert.checkBranchName(newName)), true);
    }

    /** Fast-forwards the fork branch from its parent; returns GitHub's message. */
    public static String syncFork(GitHubApiClient api, String repo, String branch) throws Exception {
        try { return api.postJson(Gh.repo(repo) + "/merge-upstream", new JSONObject().put("branch", branch), true).optString("message", "Синхронизировано"); }
        catch (GitHubApiClient.ApiException e) {
            if (e.statusCode == 409) throw new IllegalArgumentException("В форке есть свои коммиты, конфликт с родителем: синхронизация без слияния невозможна");
            throw e;
        }
    }
}
