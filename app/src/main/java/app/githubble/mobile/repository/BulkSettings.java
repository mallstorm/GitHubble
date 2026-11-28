package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** The same safe setting applied to many repositories, plus copying a template repository onto all of them. */
public final class BulkSettings {
    private BulkSettings() {}

    public enum Action {
        DEPENDABOT_ALERTS("Включить Dependabot alerts", "Dependabot alerts"), SECURITY_UPDATES("Включить Dependabot security updates", "Administration"),
        SECRET_SCANNING("Включить secret scanning", "Administration"), PUSH_PROTECTION("Включить push protection", "Administration"),
        DELETE_BRANCH("Удалять ветку после слияния pull request", "Administration"), SQUASH_ONLY("Оставить только squash-слияние", "Administration"),
        TOKEN_READ_ONLY("Токен Actions по умолчанию — только чтение", "Actions");
        public final String label, permission;
        Action(String label, String permission) { this.label = label; this.permission = permission; }
    }

    static void apply(GitHubApiClient api, String repo, Action action) throws Exception {
        switch (action) {
            case DEPENDABOT_ALERTS: RepoSecurity.setVulnerabilityAlerts(api, repo, true); break;
            case SECURITY_UPDATES: RepoSecurity.setSecurityFixes(api, repo, true); break;
            case SECRET_SCANNING: RepoSecurity.setAnalysis(api, repo, "secret_scanning", true); break;
            case PUSH_PROTECTION: RepoSecurity.setAnalysis(api, repo, "secret_scanning_push_protection", true); break;
            case DELETE_BRANCH: api.patchJson(Gh.repo(repo), new JSONObject().put("delete_branch_on_merge", true)); break;
            case SQUASH_ONLY: api.patchJson(Gh.repo(repo), new JSONObject().put("allow_squash_merge", true).put("allow_merge_commit", false).put("allow_rebase_merge", false)); break;
            default: ActionsPermissions.saveToken(api, repo, "read", false); break;
        }
    }

    /** Outcome per repository: empty list means everything went through. */
    public static Map<String, List<String>> run(GitHubApiClient api, List<String> repos, Set<Action> actions, String template, Set<SettingsCopy.Part> parts) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String repo : repos) {
            List<String> problems = new ArrayList<>();
            for (Action action : Action.values()) {
                if (!actions.contains(action)) continue;
                try { apply(api, repo, action); }
                catch (Exception error) { problems.add(action.label + ": " + (error instanceof IllegalArgumentException ? error.getMessage() : Gh.explain(error, action.permission))); }
            }
            if (template != null && !template.isEmpty() && !parts.isEmpty() && !template.equalsIgnoreCase(repo))
                for (String line : SettingsCopy.run(api, template, repo, parts)) if (line.startsWith("✗")) problems.add(line.substring(2));
            result.put(repo, problems);
        }
        return result;
    }

    /** Short report: how many repositories were done and the first problems. */
    public static String report(Map<String, List<String>> result) {
        int ok = 0;
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, List<String>> e : result.entrySet()) {
            if (e.getValue().isEmpty()) { ok++; continue; }
            if (shown++ >= 15) continue;
            b.append("✗ ").append(e.getKey()).append('\n');
            for (String p : e.getValue()) b.append("   ").append(p.length() > 160 ? p.substring(0, 160) + "…" : p).append('\n');
        }
        int bad = result.size() - ok;
        return "Готово: " + ok + " из " + result.size() + (bad > 0 ? ", с ошибками: " + bad : "") + (b.length() > 0 ? "\n\n" + b.toString().trim() : "") + (bad > 15 ? "\n… и ещё " + (bad - 15) + " репозиторий(ев)" : "");
    }
}
