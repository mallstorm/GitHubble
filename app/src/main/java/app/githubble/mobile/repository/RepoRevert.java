package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Rolls a branch back by N commits without rewriting history: one new commit whose tree equals the tree N first-parent steps
 * behind the branch head (the state of {@code git revert HEAD..HEAD~N} applied at once). Nothing is force-pushed and nothing is lost.
 */
public final class RepoRevert {
    private RepoRevert() {}

    public static final int MAX_STEPS = 50;

    public interface Fetch { JSONObject get(String sha) throws Exception; }

    public static final class Commit {
        public final String sha, tree, title, author, date;
        Commit(String sha, String tree, String title, String author, String date) { this.sha = sha; this.tree = tree; this.title = title; this.author = author; this.date = date; }
        public String shortSha() { return sha.length() > 7 ? sha.substring(0, 7) : sha; }
    }

    public static final class Plan {
        public final String branch, headSha; public final Commit target; public final List<Commit> undone;
        public int files = -1, additions, deletions;
        Plan(String branch, String headSha, Commit target, List<Commit> undone) { this.branch = branch; this.headSha = headSha; this.target = target; this.undone = undone; }
    }

    public static final class Result {
        public final String commit, branch, pullRequestUrl;
        Result(String commit, String branch, String pullRequestUrl) { this.commit = commit; this.branch = branch; this.pullRequestUrl = pullRequestUrl; }
    }

    public static int checkSteps(String raw) {
        int steps;
        try { steps = Integer.parseInt(raw == null ? "" : raw.trim()); } catch (NumberFormatException bad) { throw new IllegalArgumentException("Введите число шагов, например 3"); }
        if (steps < 1 || steps > MAX_STEPS) throw new IllegalArgumentException("Число шагов — от 1 до " + MAX_STEPS);
        return steps;
    }

    /** The git rules for a branch name, in the strict form that GitHub accepts. */
    public static String checkBranchName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 200) throw new IllegalArgumentException("Введите имя новой ветки");
        if (name.startsWith("/") || name.endsWith("/") || name.startsWith(".") || name.endsWith(".") || name.endsWith(".lock") || name.startsWith("-")
            || name.contains("..") || name.contains("//") || name.contains("@{") || name.equals("@") || name.contains("/."))
            throw new IllegalArgumentException("Недопустимое имя ветки");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= ' ' || c == 127 || "~^:?*[\\".indexOf(c) >= 0) throw new IllegalArgumentException("В имени ветки нельзя пробелы и символы ~ ^ : ? * [ \\");
        }
        return name;
    }

    public static String defaultBranchName(String branch, int steps) {
        String clean = (branch == null ? "branch" : branch).replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[.-]+|[.-]+$", "");
        if (clean.isEmpty()) clean = "branch";
        return "revert/" + clean + "-" + steps + "-steps";
    }

    public static String defaultMessage(int steps, Commit target) {
        return "Revert last " + steps + " commit" + (steps == 1 ? "" : "s") + ": restore " + target.shortSha() + (target.title.isEmpty() ? "" : " (" + target.title + ")");
    }

    /** Reads a commit from either the list shape ({commit:{…}}) or the git-data shape. */
    static Commit parse(JSONObject entry) {
        JSONObject c = entry.optJSONObject("commit");
        if (c == null) c = entry;
        JSONObject tree = c.optJSONObject("tree"), author = c.optJSONObject("author");
        String message = c.optString("message", "");
        int nl = message.indexOf('\n');
        String title = (nl >= 0 ? message.substring(0, nl) : message).trim();
        return new Commit(entry.optString("sha"), tree == null ? "" : tree.optString("sha"), title, author == null ? "" : author.optString("name"), author == null ? "" : author.optString("date"));
    }

    static String firstParent(JSONObject entry) {
        JSONArray parents = entry.optJSONArray("parents");
        if (parents == null || parents.length() == 0) return null;
        JSONObject p = parents.optJSONObject(0);
        return p == null ? null : p.optString("sha", null);
    }

    /** Follows first parents from the head: head, parent, grandparent… up to {@code count} commits or the root. */
    static List<Commit> firstParentChain(String head, int count, Map<String, JSONObject> known, Fetch fetch) throws Exception {
        List<Commit> out = new ArrayList<>();
        String sha = head;
        while (sha != null && out.size() < count) {
            JSONObject entry = known.get(sha);
            if (entry == null) { entry = fetch.get(sha); if (entry != null) known.put(sha, entry); }
            if (entry == null) break;
            out.add(parse(entry));
            sha = firstParent(entry);
        }
        return out;
    }

    static String headSha(GitHubApiClient api, String repo, String branch) throws Exception {
        return Gh.object(api, Gh.repo(repo) + "/git/ref/heads/" + Gh.segment(branch)).getJSONObject("object").getString("sha");
    }

    public static Plan plan(GitHubApiClient api, String repo, String branch, int steps) throws Exception {
        checkSteps(String.valueOf(steps));
        final String head = headSha(api, repo, branch);
        Map<String, JSONObject> known = new HashMap<>();
        JSONArray page = Gh.array(api, Gh.repo(repo) + "/commits?sha=" + head + "&per_page=100");
        for (int i = 0; i < page.length(); i++) { JSONObject x = page.optJSONObject(i); if (x != null) known.put(x.optString("sha"), x); }
        List<Commit> chain = firstParentChain(head, steps + 1, known, sha -> Gh.object(api, Gh.repo(repo) + "/git/commits/" + sha));
        if (chain.size() < steps + 1) throw new IllegalArgumentException("В ветке «" + branch + "» всего " + chain.size() + " коммит(ов) в основной линии: откатить на " + steps + " шаг(ов) нельзя");
        Commit target = chain.get(steps);
        if (target.tree.isEmpty()) throw new IllegalArgumentException("Не удалось прочитать состояние выбранного коммита");
        if (target.tree.equals(chain.get(0).tree)) throw new IllegalArgumentException("Состояние ветки уже совпадает с выбранным коммитом: откатывать нечего");
        Plan plan = new Plan(branch, head, target, new ArrayList<>(chain.subList(0, steps)));
        try {
            JSONObject compare = Gh.object(api, Gh.repo(repo) + "/compare/" + target.sha + "..." + head);
            JSONArray files = compare.optJSONArray("files");
            if (files != null) {
                plan.files = files.length();
                for (int i = 0; i < files.length(); i++) { JSONObject f = files.optJSONObject(i); if (f != null) { plan.additions += f.optInt("additions"); plan.deletions += f.optInt("deletions"); } }
            }
        } catch (GitHubApiClient.ApiException ignored) { /* the summary is optional */ }
        return plan;
    }

    /** Writes the revert commit; into {@code newBranch} (optionally with a pull request) or straight into the branch. */
    public static Result apply(GitHubApiClient api, String repo, Plan plan, String message, String newBranch, boolean pullRequest) throws Exception {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Введите сообщение коммита");
        String branchName = newBranch == null ? null : checkBranchName(newBranch);
        if (!plan.headSha.equals(headSha(api, repo, plan.branch))) throw new IllegalArgumentException("Ветка изменилась после предпросмотра. Нажмите «Показать, что будет откачено» ещё раз.");
        JSONObject created = api.postJson(Gh.repo(repo) + "/git/commits", new JSONObject().put("message", text).put("tree", plan.target.tree).put("parents", new JSONArray().put(plan.headSha)), true);
        String sha = created.getString("sha");
        try {
            if (branchName != null) api.postJson(Gh.repo(repo) + "/git/refs", new JSONObject().put("ref", "refs/heads/" + branchName).put("sha", sha), true);
            else api.patchJson(Gh.repo(repo) + "/git/refs/heads/" + Gh.segment(plan.branch), new JSONObject().put("sha", sha).put("force", false));
        } catch (GitHubApiClient.ApiException e) {
            String raw = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (branchName == null && (raw.contains("protected") || raw.contains("required") || raw.contains("rule"))) throw new IllegalArgumentException("Ветка защищена: прямую запись GitHub не принял. Выберите «В новую ветку» и создайте pull request.");
            if (branchName != null && raw.contains("already exists")) throw new IllegalArgumentException("Ветка «" + branchName + "» уже есть. Укажите другое имя.");
            throw e;
        }
        String url = "";
        if (branchName != null && pullRequest) {
            String title = text.contains("\n") ? text.substring(0, text.indexOf('\n')).trim() : text;
            JSONObject pr = api.postJson(Gh.repo(repo) + "/pulls", new JSONObject().put("title", title).put("head", branchName).put("base", plan.branch)
                .put("body", "Откат ветки `" + plan.branch + "` на " + plan.undone.size() + " шаг(ов) к состоянию " + plan.target.shortSha() + "."), true);
            url = pr.optString("html_url", "");
        }
        return new Result(sha, branchName, url);
    }
}
