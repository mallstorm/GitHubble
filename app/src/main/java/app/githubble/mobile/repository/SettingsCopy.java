package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/** Copies repository settings from another repository: everything GitHub lets us read back (secrets are write-only and never copied). */
public final class SettingsCopy {
    private SettingsCopy() {}

    public enum Part {
        GENERAL("Общие: описание, темы, возможности, способы слияния", "Administration"), PROTECTION("Защита веток (для веток с тем же именем)", "Administration"),
        RULESETS("Rulesets (без списка обхода)", "Administration"), VARIABLES("Переменные Actions вместе со значениями", "Variables"),
        ACTIONS("Actions: права токена, разрешённые actions", "Actions"), WEBHOOKS("Webhooks (без секретов подписи)", "Webhooks");
        public final String label, permission;
        Part(String label, String permission) { this.label = label; this.permission = permission; }
    }

    public static String checkSource(String raw, String target) {
        String source = RepoSecrets.checkRepo(raw == null ? "" : raw.trim());
        if (source.equalsIgnoreCase(target)) throw new IllegalArgumentException("Источник и цель — один и тот же репозиторий");
        return source;
    }

    private static final String[] DROP = {"id", "node_id", "source", "source_type", "_links", "created_at", "updated_at", "current_user_can_bypass", "bypass_actors"};

    /** A ruleset as GitHub returns it, reduced to the fields a create request accepts. */
    static JSONObject rulesetBody(JSONObject full) throws Exception {
        JSONObject out = new JSONObject(full.toString());
        for (String key : DROP) out.remove(key);
        return out;
    }

    public static List<String> missingSecrets(GitHubApiClient api, String source, String target) throws Exception {
        Set<String> have = new HashSet<>();
        for (RepoSecrets.Item i : RepoSecrets.list(api, target, RepoSecrets.Kind.ACTIONS_SECRET)) have.add(i.name);
        List<String> missing = new ArrayList<>();
        for (RepoSecrets.Item i : RepoSecrets.list(api, source, RepoSecrets.Kind.ACTIONS_SECRET)) if (!have.contains(i.name)) missing.add(i.name);
        return missing;
    }

    /** Runs the chosen parts one by one; a failing part does not stop the others. Returns report lines. */
    public static List<String> run(GitHubApiClient api, String source, String target, Set<Part> parts) {
        List<String> report = new ArrayList<>();
        for (Part part : Part.values()) {
            if (!parts.contains(part)) continue;
            try { report.add("✓ " + copy(api, source, target, part)); }
            catch (Exception error) {
                String text = error instanceof IllegalArgumentException ? String.valueOf(error.getMessage()) : Gh.explain(error, part.permission);
                report.add("✗ " + part.label + ": " + text);
            }
        }
        return report;
    }

    static String copy(GitHubApiClient api, String source, String target, Part part) throws Exception {
        switch (part) {
            case GENERAL: {
                RepoGeneral.State s = RepoGeneral.load(api, source);
                RepoGeneral.save(api, target, s);
                return "Общие настройки и темы (" + s.topics.size() + ")";
            }
            case PROTECTION: {
                Set<String> here = new HashSet<>();
                for (BranchRules.Branch b : BranchRules.branches(api, target)) here.add(b.name);
                List<String> done = new ArrayList<>(), skipped = new ArrayList<>();
                for (BranchRules.Branch b : BranchRules.branches(api, source)) {
                    if (!b.protectedBranch) continue;
                    if (!here.contains(b.name)) { skipped.add(b.name); continue; }
                    BranchRules.save(api, target, b.name, BranchRules.load(api, source, b.name));
                    done.add(b.name);
                }
                return "Защита веток: " + (done.isEmpty() ? "нечего копировать" : String.join(", ", done)) + (skipped.isEmpty() ? "" : "; нет такой ветки в целевом: " + String.join(", ", skipped));
            }
            case RULESETS: {
                Set<String> here = new HashSet<>();
                for (BranchRules.Ruleset r : BranchRules.rulesets(api, target)) here.add(r.name);
                int created = 0, skipped = 0;
                for (BranchRules.Ruleset r : BranchRules.rulesets(api, source)) {
                    if (here.contains(r.name)) { skipped++; continue; }
                    api.postJson(Gh.repo(target) + "/rulesets", rulesetBody(Gh.object(api, Gh.repo(source) + "/rulesets/" + r.id)), true);
                    created++;
                }
                return "Rulesets: создано " + created + (skipped > 0 ? ", уже были " + skipped : "");
            }
            case VARIABLES: {
                Set<String> here = new HashSet<>();
                for (RepoSecrets.Item i : RepoSecrets.list(api, target, RepoSecrets.Kind.ACTIONS_VARIABLE)) here.add(i.name);
                int count = 0;
                for (RepoSecrets.Item i : RepoSecrets.list(api, source, RepoSecrets.Kind.ACTIONS_VARIABLE)) { RepoSecrets.saveVariable(api, target, i.name, i.value, here.contains(i.name)); count++; }
                return "Переменные Actions: " + count;
            }
            case ACTIONS: {
                ActionsPermissions.State s = ActionsPermissions.load(api, source);
                ActionsPermissions.saveGeneral(api, target, s.enabled, s.allowed);
                if (s.enabled && "selected".equals(s.allowed)) ActionsPermissions.saveSelected(api, target, s.githubOwned, s.verified, s.patterns);
                ActionsPermissions.saveToken(api, target, s.token, s.canApprove);
                if (!s.fork.isEmpty()) ActionsPermissions.saveFork(api, target, s.fork);
                return "Настройки Actions";
            }
            default: {
                Set<String> here = new HashSet<>();
                for (RepoWebhooks.Hook h : RepoWebhooks.list(api, target)) here.add(h.url);
                int created = 0;
                for (RepoWebhooks.Hook h : RepoWebhooks.list(api, source)) {
                    if (here.contains(h.url)) continue;
                    api.postJson(Gh.repo(target) + "/hooks", RepoWebhooks.body(h.url, h.contentType, "", h.active, h.events), true);
                    created++;
                }
                return "Webhooks: создано " + created + " (секреты подписи нужно задать заново)";
            }
        }
    }
}
