package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Settings → Branches and Rules: classic branch protection and repository rulesets. */
public final class BranchRules {
    private BranchRules() {}

    public static final class Branch {
        public final String name; public final boolean protectedBranch;
        Branch(String name, boolean protectedBranch) { this.name = name; this.protectedBranch = protectedBranch; }
    }

    public static final class Protection {
        public boolean exists, requirePr, dismissStale, codeOwners, requireChecks, strict, enforceAdmins, linearHistory, allowForce, allowDeletions, conversations;
        public int approvals = 1;
        public final List<String> contexts = new ArrayList<>();
    }

    public static final class Ruleset {
        public final long id; public final String name, target, enforcement;
        Ruleset(long id, String name, String target, String enforcement) { this.id = id; this.name = name; this.target = target; this.enforcement = enforcement; }
    }

    public static List<Branch> branches(GitHubApiClient api, String repo) throws Exception {
        List<Branch> out = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JSONArray array = Gh.array(api, Gh.repo(repo) + "/branches?per_page=100&page=" + page);
            for (int i = 0; i < array.length(); i++) {
                JSONObject x = array.optJSONObject(i);
                if (x != null && !x.optString("name").isEmpty()) out.add(new Branch(x.optString("name"), x.optBoolean("protected", false)));
            }
            if (array.length() < 100) break;
        }
        return out;
    }

    static boolean enabled(JSONObject root, String key) { JSONObject o = root.optJSONObject(key); return o != null && o.optBoolean("enabled", false); }

    static Protection parse(JSONObject json) {
        Protection p = new Protection();
        p.exists = true;
        JSONObject reviews = json.optJSONObject("required_pull_request_reviews");
        if (reviews != null) {
            p.requirePr = true; p.approvals = reviews.optInt("required_approving_review_count", 1);
            p.dismissStale = reviews.optBoolean("dismiss_stale_reviews", false); p.codeOwners = reviews.optBoolean("require_code_owner_reviews", false);
        }
        JSONObject checks = json.optJSONObject("required_status_checks");
        if (checks != null) {
            p.requireChecks = true; p.strict = checks.optBoolean("strict", false);
            JSONArray contexts = checks.optJSONArray("contexts");
            if (contexts != null) for (int i = 0; i < contexts.length(); i++) p.contexts.add(contexts.optString(i));
        }
        p.enforceAdmins = enabled(json, "enforce_admins"); p.linearHistory = enabled(json, "required_linear_history");
        p.allowForce = enabled(json, "allow_force_pushes"); p.allowDeletions = enabled(json, "allow_deletions"); p.conversations = enabled(json, "required_conversation_resolution");
        return p;
    }

    static JSONObject body(Protection p) throws Exception {
        if (p.approvals < 0 || p.approvals > 6) throw new IllegalArgumentException("Число одобрений — от 0 до 6");
        JSONObject out = new JSONObject();
        if (p.requireChecks) {
            JSONArray contexts = new JSONArray();
            for (String c : p.contexts) if (!c.trim().isEmpty()) contexts.put(c.trim());
            out.put("required_status_checks", new JSONObject().put("strict", p.strict).put("contexts", contexts));
        } else out.put("required_status_checks", JSONObject.NULL);
        out.put("enforce_admins", p.enforceAdmins);
        if (p.requirePr) out.put("required_pull_request_reviews", new JSONObject().put("required_approving_review_count", p.approvals)
            .put("dismiss_stale_reviews", p.dismissStale).put("require_code_owner_reviews", p.codeOwners));
        else out.put("required_pull_request_reviews", JSONObject.NULL);
        out.put("restrictions", JSONObject.NULL).put("required_linear_history", p.linearHistory).put("allow_force_pushes", p.allowForce)
            .put("allow_deletions", p.allowDeletions).put("required_conversation_resolution", p.conversations);
        return out;
    }

    /** Parses «ci, build» into a clean list of required check names. */
    public static List<String> splitContexts(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) for (String part : raw.split("[,\\n]")) { String c = part.trim(); if (!c.isEmpty() && !out.contains(c)) out.add(c); }
        return out;
    }

    public static Protection load(GitHubApiClient api, String repo, String branch) throws Exception {
        try { return parse(Gh.object(api, Gh.repo(repo) + "/branches/" + Gh.segment(branch) + "/protection")); }
        catch (GitHubApiClient.ApiException e) {
            if (e.statusCode == 404 && String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("not protected")) return new Protection();
            throw e;
        }
    }

    public static void save(GitHubApiClient api, String repo, String branch, Protection p) throws Exception {
        api.putJson(Gh.repo(repo) + "/branches/" + Gh.segment(branch) + "/protection", body(p));
    }

    public static void remove(GitHubApiClient api, String repo, String branch) throws Exception {
        try { api.delete(Gh.repo(repo) + "/branches/" + Gh.segment(branch) + "/protection"); }
        catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }

    // ---- rulesets --------------------------------------------------------------------------------------------------

    static List<Ruleset> parseRulesets(JSONArray array) {
        List<Ruleset> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject x = array.optJSONObject(i);
            if (x != null) out.add(new Ruleset(x.optLong("id"), x.optString("name"), x.optString("target", "branch"), x.optString("enforcement", "disabled")));
        }
        return out;
    }

    public static List<Ruleset> rulesets(GitHubApiClient api, String repo) throws Exception {
        return parseRulesets(Gh.array(api, Gh.repo(repo) + "/rulesets?per_page=100&includes_parents=false"));
    }

    public static void setEnforcement(GitHubApiClient api, String repo, long id, String enforcement) throws Exception {
        if (!enforcement.matches("active|evaluate|disabled")) throw new IllegalArgumentException("Недопустимый режим");
        api.putJson(Gh.repo(repo) + "/rulesets/" + id, new JSONObject().put("enforcement", enforcement));
    }

    public static void deleteRuleset(GitHubApiClient api, String repo, long id) throws Exception {
        try { api.delete(Gh.repo(repo) + "/rulesets/" + id); }
        catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }

    /** A ready preset: which refs it covers and which common rules it enforces. */
    static JSONObject rulesetBody(String name, boolean tags, String patterns, boolean blockDeletion, boolean blockForcePush, boolean linear, int approvals) throws Exception {
        String title = name == null ? "" : name.trim();
        if (title.isEmpty()) throw new IllegalArgumentException("Введите название правила");
        JSONArray include = new JSONArray();
        for (String part : (patterns == null ? "" : patterns).split("[,\\n]")) { String p = part.trim(); if (!p.isEmpty()) include.put(p); }
        if (include.length() == 0) include.put("~DEFAULT_BRANCH");
        JSONArray rules = new JSONArray();
        if (blockDeletion) rules.put(new JSONObject().put("type", "deletion"));
        if (blockForcePush) rules.put(new JSONObject().put("type", "non_fast_forward"));
        if (linear && !tags) rules.put(new JSONObject().put("type", "required_linear_history"));
        if (approvals > 0 && !tags) rules.put(new JSONObject().put("type", "pull_request").put("parameters", new JSONObject()
            .put("required_approving_review_count", Math.min(approvals, 10)).put("dismiss_stale_reviews_on_push", false).put("require_code_owner_review", false)
            .put("require_last_push_approval", false).put("required_review_thread_resolution", false)));
        if (rules.length() == 0) throw new IllegalArgumentException("Выберите хотя бы одно правило");
        return new JSONObject().put("name", title).put("target", tags ? "tag" : "branch").put("enforcement", "active")
            .put("conditions", new JSONObject().put("ref_name", new JSONObject().put("include", include).put("exclude", new JSONArray()))).put("rules", rules);
    }

    public static void createRuleset(GitHubApiClient api, String repo, String name, boolean tags, String patterns, boolean blockDeletion, boolean blockForcePush, boolean linear, int approvals) throws Exception {
        api.postJson(Gh.repo(repo) + "/rulesets", rulesetBody(name, tags, patterns, blockDeletion, blockForcePush, linear, approvals), true);
    }
}
