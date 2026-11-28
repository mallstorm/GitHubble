package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONArray;
import org.json.JSONObject;

/** Open security alerts of one repository, for a table across the whole account. */
public final class SecuritySummary {
    private SecuritySummary() {}

    public static final class Row {
        public final String full;
        public int critical, high, medium, low, code, secret;
        public boolean dependabot = true, codeScanning = true, secretScanning = true;
        Row(String full) { this.full = full; }
        public int dependabotTotal() { return critical + high + medium + low; }
        public int weight() { return critical * 1000 + high * 100 + medium * 10 + low + code * 20 + secret * 500; }
        public boolean clean() { return weight() == 0; }

        public String line() {
            StringBuilder b = new StringBuilder();
            b.append("Dependabot: ").append(!dependabot ? "нет доступа" : dependabotTotal() == 0 ? "0" : critical + " крит., " + high + " выс., " + medium + " сред., " + low + " низк.");
            b.append(" · Code: ").append(codeScanning ? String.valueOf(code) : "—");
            b.append(" · Секреты: ").append(secretScanning ? String.valueOf(secret) : "—");
            return b.toString();
        }
    }

    static void countDependabot(Row row, JSONArray alerts) {
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject a = alerts.optJSONObject(i), adv = a == null ? null : a.optJSONObject("security_advisory");
            String severity = adv == null ? "" : adv.optString("severity");
            if ("critical".equals(severity)) row.critical++; else if ("high".equals(severity)) row.high++; else if ("medium".equals(severity)) row.medium++; else row.low++;
        }
    }

    private static JSONArray alerts(GitHubApiClient api, String full, String path) throws Exception { return Gh.array(api, Gh.repo(full) + "/" + path + "?state=open&per_page=100"); }

    public static Row scan(GitHubApiClient api, String full) throws Exception {
        Row row = new Row(full);
        try { countDependabot(row, alerts(api, full, "dependabot/alerts")); } catch (GitHubApiClient.ApiException e) { if (e.statusCode == 403 || e.statusCode == 404) row.dependabot = false; else throw e; }
        try { row.code = alerts(api, full, "code-scanning/alerts").length(); } catch (GitHubApiClient.ApiException e) { if (e.statusCode == 403 || e.statusCode == 404) row.codeScanning = false; else throw e; }
        try { row.secret = alerts(api, full, "secret-scanning/alerts").length(); } catch (GitHubApiClient.ApiException e) { if (e.statusCode == 403 || e.statusCode == 404) row.secretScanning = false; else throw e; }
        return row;
    }
}
