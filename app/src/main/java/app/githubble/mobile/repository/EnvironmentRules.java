package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Protection rules of a GitHub environment: wait timer, required reviewers, self-review ban and deployment branches. */
public final class EnvironmentRules {
    private EnvironmentRules() {}

    public static final String ALL = "all", PROTECTED = "protected", CUSTOM = "custom";
    public static final int MAX_WAIT_MINUTES = 43200, MAX_REVIEWERS = 6;

    public static final class Reviewer {
        public final String type; public final long id; public final String label;
        public Reviewer(String type, long id, String label) { this.type = type; this.id = id; this.label = label; }
    }

    public static final class Policy {
        public int waitMinutes; public boolean preventSelfReview; public String branches = ALL;
        public final List<Reviewer> reviewers = new ArrayList<>();
    }

    public static final class BranchRule {
        public final long id; public final String name, type;
        BranchRule(long id, String name, String type) { this.id = id; this.name = name; this.type = type; }
    }

    static Policy parse(JSONObject env) {
        Policy policy = new Policy();
        JSONArray rules = env.optJSONArray("protection_rules");
        if (rules != null) for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            String type = rule.optString("type");
            if ("wait_timer".equals(type)) policy.waitMinutes = rule.optInt("wait_timer", 0);
            else if ("required_reviewers".equals(type)) {
                policy.preventSelfReview = rule.optBoolean("prevent_self_review", false);
                JSONArray list = rule.optJSONArray("reviewers");
                if (list != null) for (int j = 0; j < list.length(); j++) {
                    JSONObject item = list.optJSONObject(j), who = item == null ? null : item.optJSONObject("reviewer");
                    if (who == null) continue;
                    String label = who.optString("login", who.optString("slug", who.optString("name", "")));
                    policy.reviewers.add(new Reviewer(item.optString("type", "User"), who.optLong("id"), label));
                }
            }
        }
        JSONObject branch = env.optJSONObject("deployment_branch_policy");
        if (branch != null) policy.branches = branch.optBoolean("custom_branch_policies", false) ? CUSTOM : PROTECTED;
        return policy;
    }

    static JSONObject body(Policy policy) throws Exception {
        if (policy.waitMinutes < 0 || policy.waitMinutes > MAX_WAIT_MINUTES) throw new IllegalArgumentException("Ожидание: от 0 до 43200 минут (30 суток)");
        if (policy.reviewers.size() > MAX_REVIEWERS) throw new IllegalArgumentException("Проверяющих не больше шести");
        JSONArray reviewers = new JSONArray();
        for (Reviewer r : policy.reviewers) reviewers.put(new JSONObject().put("type", r.type).put("id", r.id));
        JSONObject out = new JSONObject().put("wait_timer", policy.waitMinutes).put("prevent_self_review", policy.preventSelfReview).put("reviewers", reviewers);
        if (ALL.equals(policy.branches)) out.put("deployment_branch_policy", JSONObject.NULL);
        else out.put("deployment_branch_policy", new JSONObject().put("protected_branches", PROTECTED.equals(policy.branches)).put("custom_branch_policies", CUSTOM.equals(policy.branches)));
        return out;
    }

    public static Policy load(GitHubApiClient api, String repo, String env) throws Exception {
        return parse(api.getJsonFresh(RepoSecrets.environmentUrl(repo, env)));
    }

    public static void save(GitHubApiClient api, String repo, String env, Policy policy) throws Exception {
        api.putJson(RepoSecrets.environmentUrl(repo, env), body(policy));
    }

    /** "login" is a user, "organization/team-slug" is a team of that organization. */
    public static Reviewer resolve(GitHubApiClient api, String input) throws Exception {
        String value = input == null ? "" : input.trim();
        if (value.startsWith("@")) value = value.substring(1);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}(/[A-Za-z0-9._-]{1,100})?")) throw new IllegalArgumentException("Введите логин пользователя или организация/команда");
        int slash = value.indexOf('/');
        try {
            if (slash < 0) { JSONObject u = api.getJsonFresh("https://api.github.com/users/" + value); return new Reviewer("User", u.getLong("id"), u.optString("login", value)); }
            JSONObject t = api.getJsonFresh("https://api.github.com/orgs/" + value.substring(0, slash) + "/teams/" + value.substring(slash + 1));
            return new Reviewer("Team", t.getLong("id"), value);
        } catch (GitHubApiClient.ApiException e) {
            if (e.statusCode == 404) throw new IllegalArgumentException(slash < 0 ? "Пользователь не найден" : "Команда не найдена или нет доступа к организации");
            throw e;
        }
    }

    static List<BranchRule> parseBranchRules(JSONObject json) {
        List<BranchRule> out = new ArrayList<>();
        JSONArray array = json.optJSONArray("branch_policies");
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject x = array.optJSONObject(i);
            if (x != null && !x.optString("name").isEmpty()) out.add(new BranchRule(x.optLong("id"), x.optString("name"), x.optString("type", "branch")));
        }
        return out;
    }

    public static List<BranchRule> branchRules(GitHubApiClient api, String repo, String env) throws Exception {
        return parseBranchRules(api.getJsonFresh(RepoSecrets.environmentUrl(repo, env) + "/deployment-branch-policies?per_page=100"));
    }

    public static void addBranchRule(GitHubApiClient api, String repo, String env, String pattern, boolean tag) throws Exception {
        String name = pattern == null ? "" : pattern.trim();
        if (name.isEmpty() || name.length() > 255) throw new IllegalArgumentException("Введите шаблон ветки или тега, например main или release/*");
        api.postJson(RepoSecrets.environmentUrl(repo, env) + "/deployment-branch-policies", new JSONObject().put("name", name).put("type", tag ? "tag" : "branch"), true);
    }

    public static void deleteBranchRule(GitHubApiClient api, String repo, String env, long id) throws Exception {
        try { api.delete(RepoSecrets.environmentUrl(repo, env) + "/deployment-branch-policies/" + id); }
        catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }

    public static String explain(Exception error) {
        if (error instanceof GitHubApiClient.ApiException) {
            int code = ((GitHubApiClient.ApiException) error).statusCode;
            if (code == 403 || code == 404) return "GitHub не разрешил менять правила окружения. Нужны права администратора и Environments (Read and write); проверяющие, ожидание и ограничение веток в приватных репозиториях доступны на платных тарифах. HTTP " + code + ".";
            if (code == 422) return "GitHub отклонил правила (HTTP 422): " + error.getMessage();
        }
        return error.getMessage() == null ? "Ошибка сети" : error.getMessage();
    }
}
