package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        public String tree, baseTree; public int count; public boolean single;
        public List<JSONObject> entries; public final List<String> conflicts = new ArrayList<>();
        public int files = -1, additions, deletions;
        Plan(String branch, String headSha, Commit target, List<Commit> undone) { this.branch = branch; this.headSha = headSha; this.target = target; this.undone = undone; this.tree = target.tree; this.count = undone.size(); }
    }

    public static final class Entry {
        public final String mode, type, sha;
        Entry(String mode, String type, String sha) { this.mode = mode; this.type = type; this.sha = sha; }
    }

    public static final class Merge {
        public final List<JSONObject> entries = new ArrayList<>(); public final List<String> conflicts = new ArrayList<>();
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

    public static String defaultSingleMessage(Commit c) {
        return "Revert \"" + c.title + "\"\n\nThis reverts commit " + c.sha + ".";
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

    static boolean same(Entry a, Entry b) { return a == null ? b == null : b != null && a.sha.equals(b.sha) && a.mode.equals(b.mode); }

    /**
     * Undoes one commit C (parent P) on top of HEAD file by file: a file still equal to its state in C is restored to its state in P;
     * a file already equal to P is left alone; a file changed after C is reported as a conflict.
     */
    static Merge threeWay(Map<String, Entry> c, Map<String, Entry> p, Map<String, Entry> h) throws Exception {
        Merge out = new Merge();
        Set<String> paths = new LinkedHashSet<>(c.keySet()); paths.addAll(p.keySet());
        for (String path : paths) {
            Entry ec = c.get(path), ep = p.get(path);
            if (same(ec, ep)) continue;
            Entry eh = h.get(path);
            if (same(eh, ec)) {
                Entry basis = ep != null ? ep : ec;
                out.entries.add(new JSONObject().put("path", path).put("mode", basis.mode).put("type", basis.type).put("sha", ep == null ? JSONObject.NULL : ep.sha));
            } else if (!same(eh, ep)) out.conflicts.add(path);
        }
        return out;
    }

    static Map<String, Entry> readTree(GitHubApiClient api, String repo, String treeSha) throws Exception {
        JSONObject json = Gh.object(api, Gh.repo(repo) + "/git/trees/" + treeSha + "?recursive=1");
        if (json.optBoolean("truncated")) throw new IllegalArgumentException("Репозиторий слишком большой: GitHub усёк список файлов, отдельный коммит отменить нельзя");
        Map<String, Entry> out = new HashMap<>();
        JSONArray list = json.optJSONArray("tree");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x != null && !"tree".equals(x.optString("type"))) out.put(x.optString("path"), new Entry(x.optString("mode"), x.optString("type"), x.optString("sha")));
        }
        return out;
    }

    /** First-parent commits of the branch for a picker (newest first). */
    public static List<Commit> recent(GitHubApiClient api, String repo, String branch, int count) throws Exception {
        final String head = headSha(api, repo, branch);
        Map<String, JSONObject> known = new HashMap<>();
        JSONArray page = Gh.array(api, Gh.repo(repo) + "/commits?sha=" + head + "&per_page=100");
        for (int i = 0; i < page.length(); i++) { JSONObject x = page.optJSONObject(i); if (x != null) known.put(x.optString("sha"), x); }
        return firstParentChain(head, count, known, sha -> Gh.object(api, Gh.repo(repo) + "/git/commits/" + sha));
    }

    /** Undo exactly one commit and keep everything that was committed after it. */
    public static Plan planSingle(GitHubApiClient api, String repo, String branch, String commitSha) throws Exception {
        final String head = headSha(api, repo, branch);
        JSONObject cj = Gh.object(api, Gh.repo(repo) + "/git/commits/" + Gh.segment(commitSha));
        String parentSha = firstParent(cj);
        if (parentSha == null) throw new IllegalArgumentException("Это первый коммит ветки: отменять нечего");
        Commit c = parse(cj), p = parse(Gh.object(api, Gh.repo(repo) + "/git/commits/" + parentSha)), h = parse(Gh.object(api, Gh.repo(repo) + "/git/commits/" + head));
        List<Commit> undone = new ArrayList<>(); undone.add(c);
        Plan plan = new Plan(branch, head, c, undone);
        plan.single = true;
        if (c.sha.equals(head)) { plan.tree = p.tree; plan.files = -1; return plan; }
        Merge m = threeWay(readTree(api, repo, c.tree), readTree(api, repo, p.tree), readTree(api, repo, h.tree));
        plan.baseTree = h.tree; plan.entries = m.entries; plan.conflicts.addAll(m.conflicts); plan.files = m.entries.size();
        if (m.entries.isEmpty() && m.conflicts.isEmpty()) throw new IllegalArgumentException("Изменения этого коммита уже отменены или перекрыты позднее: откатывать нечего");
        return plan;
    }

    /** Roll back to any revision that lies in the history of the branch: a commit sha, a tag or another branch name. */
    public static Plan planToRevision(GitHubApiClient api, String repo, String branch, String revision) throws Exception {
        String rev = revision == null ? "" : revision.trim();
        if (rev.isEmpty()) throw new IllegalArgumentException("Укажите тег или sha коммита");
        final String head = headSha(api, repo, branch);
        Commit target = parse(Gh.object(api, Gh.repo(repo) + "/commits/" + Gh.segment(rev)));
        if (target.sha.equals(head)) throw new IllegalArgumentException("Это текущий конец ветки: откатывать нечего");
        JSONObject compare = Gh.object(api, Gh.repo(repo) + "/compare/" + target.sha + "..." + head);
        if (!"ahead".equals(compare.optString("status"))) throw new IllegalArgumentException("Ревизия «" + rev + "» не лежит в истории ветки «" + branch + "»: откат невозможен");
        List<Commit> undone = new ArrayList<>();
        JSONArray commits = compare.optJSONArray("commits");
        if (commits != null) for (int i = commits.length() - 1; i >= 0; i--) { JSONObject x = commits.optJSONObject(i); if (x != null) undone.add(parse(x)); }
        if (target.tree.isEmpty()) throw new IllegalArgumentException("Не удалось прочитать состояние выбранной ревизии");
        Plan plan = new Plan(branch, head, target, undone);
        plan.count = Math.max(undone.size(), compare.optInt("total_commits", undone.size()));
        JSONArray files = compare.optJSONArray("files");
        if (files != null) {
            plan.files = files.length();
            for (int i = 0; i < files.length(); i++) { JSONObject f = files.optJSONObject(i); if (f != null) { plan.additions += f.optInt("additions"); plan.deletions += f.optInt("deletions"); } }
        }
        return plan;
    }

    /** The last commit of the branch made on or before the date (YYYY-MM-DD, end of that day, UTC). */
    public static Plan planToDate(GitHubApiClient api, String repo, String branch, String date) throws Exception {
        String day = date == null ? "" : date.trim();
        if (!day.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Дата в формате ГГГГ-ММ-ДД, например 2026-11-20");
        String until = day + "T23:59:59Z";
        JSONArray list = Gh.array(api, Gh.repo(repo) + "/commits?sha=" + java.net.URLEncoder.encode(branch, "UTF-8") + "&until=" + until + "&per_page=1");
        if (list.length() == 0) throw new IllegalArgumentException("До " + day + " в ветке нет коммитов");
        return planToRevision(api, repo, branch, list.getJSONObject(0).getString("sha"));
    }

    /** Writes the revert commit; into {@code newBranch} (optionally with a pull request) or straight into the branch. */
    public static Result apply(GitHubApiClient api, String repo, Plan plan, String message, String newBranch, boolean pullRequest) throws Exception {
        return apply(api, repo, plan, message, newBranch, pullRequest, false);
    }

    public static Result apply(GitHubApiClient api, String repo, Plan plan, String message, String newBranch, boolean pullRequest, boolean partial) throws Exception {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Введите сообщение коммита");
        String branchName = newBranch == null ? null : checkBranchName(newBranch);
        if (!plan.conflicts.isEmpty() && !partial) throw new IllegalArgumentException("Есть файлы, изменённые после этого коммита (" + plan.conflicts.size() + "). Отметьте «Применить без конфликтующих файлов» или выберите другой способ.");
        if (!plan.headSha.equals(headSha(api, repo, plan.branch))) throw new IllegalArgumentException("Ветка изменилась после предпросмотра. Нажмите «Показать, что будет откачено» ещё раз.");
        String tree = plan.tree;
        if (plan.entries != null) {
            if (plan.entries.isEmpty()) throw new IllegalArgumentException("Нечего применять: все файлы этого коммита конфликтуют с поздними изменениями");
            tree = api.postJson(Gh.repo(repo) + "/git/trees", new JSONObject().put("base_tree", plan.baseTree).put("tree", new JSONArray(plan.entries)), true).getString("sha");
        }
        JSONObject created = api.postJson(Gh.repo(repo) + "/git/commits", new JSONObject().put("message", text).put("tree", tree).put("parents", new JSONArray().put(plan.headSha)), true);
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
                .put("body", plan.single ? "Отмена коммита " + plan.target.shortSha() + " в ветке `" + plan.branch + "`." : "Откат ветки `" + plan.branch + "` к состоянию " + plan.target.shortSha() + " (отменено коммитов: " + plan.count + ")."), true);
            url = pr.optString("html_url", "");
        }
        return new Result(sha, branchName, url);
    }
}
