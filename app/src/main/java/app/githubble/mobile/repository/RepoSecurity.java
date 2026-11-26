package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Settings → Security: feature toggles and the three alert lists (Dependabot, code scanning, secret scanning). */
public final class RepoSecurity {
    private RepoSecurity() {}

    public enum Kind {
        DEPENDABOT("dependabot/alerts", "Dependabot alerts"), CODE("code-scanning/alerts", "Code scanning alerts"), SECRET("secret-scanning/alerts", "Secret scanning alerts");
        final String path; public final String permission;
        Kind(String path, String permission) { this.path = path; this.permission = permission; }
    }

    public static final class Alert {
        public final int number; public final String title, severity, state, url, detail;
        Alert(int number, String title, String severity, String state, String url, String detail) { this.number = number; this.title = title; this.severity = severity; this.state = state; this.url = url; this.detail = detail; }
    }

    public static final class Features {
        public boolean vulnerabilityAlerts, securityFixes, secretScanning, pushProtection, availableSecrets = true;
    }

    public static final String[] DEPENDABOT_REASONS = {"fix_started", "inaccurate", "no_bandwidth", "not_used", "tolerable_risk"};
    public static final String[] CODE_REASONS = {"false positive", "won't fix", "used in tests"};
    public static final String[] SECRET_RESOLUTIONS = {"false_positive", "wont_fix", "revoked", "used_in_tests"};

    static Alert parse(Kind kind, JSONObject x) {
        int n = x.optInt("number");
        String url = x.optString("html_url", "");
        switch (kind) {
            case DEPENDABOT: {
                JSONObject adv = x.optJSONObject("security_advisory"), dep = x.optJSONObject("dependency"), pkg = dep == null ? null : dep.optJSONObject("package");
                String name = pkg == null ? "" : pkg.optString("name", "");
                String sum = adv == null ? "" : adv.optString("summary", "");
                return new Alert(n, (name.isEmpty() ? "" : name + ": ") + sum, adv == null ? "" : adv.optString("severity", ""), x.optString("state"), url, dep == null ? "" : dep.optString("manifest_path", ""));
            }
            case CODE: {
                JSONObject rule = x.optJSONObject("rule"), loc = x.optJSONObject("most_recent_instance") == null ? null : x.optJSONObject("most_recent_instance").optJSONObject("location");
                String where = loc == null ? "" : loc.optString("path", "") + (loc.has("start_line") ? ":" + loc.optInt("start_line") : "");
                String sev = rule == null ? "" : rule.optString("security_severity_level", rule.optString("severity", ""));
                return new Alert(n, rule == null ? "" : rule.optString("description", rule.optString("id", "")), sev, x.optString("state"), url, where);
            }
            default:
                return new Alert(n, x.optString("secret_type_display_name", x.optString("secret_type", "Secret")), "secret", x.optString("state"), url, x.optString("resolution", ""));
        }
    }

    public static List<Alert> alerts(GitHubApiClient api, String repo, Kind kind, boolean open) throws Exception {
        String q = kind == Kind.SECRET ? (open ? "state=open" : "state=resolved") : (open ? "state=open" : (kind == Kind.DEPENDABOT ? "state=dismissed,fixed,auto_dismissed" : "state=closed"));
        JSONArray a = Gh.array(api, Gh.repo(repo) + "/" + kind.path + "?per_page=50&" + q);
        List<Alert> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null) out.add(parse(kind, x)); }
        return out;
    }

    static JSONObject dismissBody(Kind kind, String reason, String comment) throws Exception {
        String r = reason == null ? "" : reason;
        String note = comment == null ? "" : comment.trim();
        if (note.length() > 280) throw new IllegalArgumentException("Комментарий — не длиннее 280 символов");
        switch (kind) {
            case DEPENDABOT:
                if (!contains(DEPENDABOT_REASONS, r)) throw new IllegalArgumentException("Недопустимая причина");
                return new JSONObject().put("state", "dismissed").put("dismissed_reason", r).put("dismissed_comment", note);
            case CODE:
                if (!contains(CODE_REASONS, r)) throw new IllegalArgumentException("Недопустимая причина");
                return new JSONObject().put("state", "dismissed").put("dismissed_reason", r).put("dismissed_comment", note);
            default:
                if (!contains(SECRET_RESOLUTIONS, r)) throw new IllegalArgumentException("Недопустимое решение");
                JSONObject o = new JSONObject().put("state", "resolved").put("resolution", r);
                if (!note.isEmpty()) o.put("resolution_comment", note);
                return o;
        }
    }

    static boolean contains(String[] list, String v) { for (String s : list) if (s.equals(v)) return true; return false; }

    public static void dismiss(GitHubApiClient api, String repo, Kind kind, int number, String reason, String comment) throws Exception {
        api.patchJson(Gh.repo(repo) + "/" + kind.path + "/" + number, dismissBody(kind, reason, comment));
    }

    public static void reopen(GitHubApiClient api, String repo, Kind kind, int number) throws Exception {
        api.patchJson(Gh.repo(repo) + "/" + kind.path + "/" + number, new JSONObject().put("state", "open"));
    }

    // ---- toggles ---------------------------------------------------------------------------------------------------

    static String status(JSONObject root, String key) { JSONObject o = root == null ? null : root.optJSONObject(key); return o == null ? "" : o.optString("status", ""); }

    public static Features features(GitHubApiClient api, String repo) throws Exception {
        Features f = new Features();
        try { api.executeText("GET", Gh.repo(repo) + "/vulnerability-alerts", null, true); f.vulnerabilityAlerts = true; }
        catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
        try { f.securityFixes = Gh.object(api, Gh.repo(repo) + "/automated-security-fixes").optBoolean("enabled"); } catch (GitHubApiClient.ApiException ignored) { }
        JSONObject r = Gh.object(api, Gh.repo(repo));
        JSONObject sa = r.optJSONObject("security_and_analysis");
        f.secretScanning = "enabled".equals(status(sa, "secret_scanning"));
        f.pushProtection = "enabled".equals(status(sa, "secret_scanning_push_protection"));
        f.availableSecrets = sa != null;
        return f;
    }

    public static void setVulnerabilityAlerts(GitHubApiClient api, String repo, boolean on) throws Exception {
        if (on) api.executeText("PUT", Gh.repo(repo) + "/vulnerability-alerts", "", true); else api.delete(Gh.repo(repo) + "/vulnerability-alerts");
    }

    public static void setSecurityFixes(GitHubApiClient api, String repo, boolean on) throws Exception {
        if (on) api.executeText("PUT", Gh.repo(repo) + "/automated-security-fixes", "", true); else api.delete(Gh.repo(repo) + "/automated-security-fixes");
    }

    static JSONObject analysisBody(String key, boolean on) throws Exception {
        if (!key.matches("secret_scanning|secret_scanning_push_protection")) throw new IllegalArgumentException("Недопустимая настройка");
        return new JSONObject().put("security_and_analysis", new JSONObject().put(key, new JSONObject().put("status", on ? "enabled" : "disabled")));
    }

    public static void setAnalysis(GitHubApiClient api, String repo, String key, boolean on) throws Exception { api.patchJson(Gh.repo(repo), analysisBody(key, on)); }

    // ---- advisories ------------------------------------------------------------------------------------------------

    public static List<Alert> advisories(GitHubApiClient api, String repo) throws Exception {
        JSONArray a = Gh.array(api, Gh.repo(repo) + "/security-advisories?per_page=30");
        List<Alert> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null) out.add(new Alert(i + 1, x.optString("summary"), x.optString("severity"), x.optString("state"), x.optString("html_url"), x.optString("ghsa_id")));
        }
        return out;
    }
}
