package app.githubble.mobile.model;

import org.json.JSONObject;

public final class RepositorySummary {
    public final String fullName, description, language, defaultBranch, license;
    public final int stars, forks, openIssues;
    public RepositorySummary(String fullName, String description, String language, String defaultBranch,
                             String license, int stars, int forks, int openIssues) {
        this.fullName=fullName; this.description=description; this.language=language;
        this.defaultBranch=defaultBranch; this.license=license; this.stars=stars; this.forks=forks; this.openIssues=openIssues;
    }
    public static RepositorySummary from(JSONObject o) {
        JSONObject l=o.optJSONObject("license");
        return new RepositorySummary(o.optString("full_name"), o.optString("description"), o.optString("language","—"),
            o.optString("default_branch","main"), l==null?"—":l.optString("spdx_id","—"),
            o.optInt("stargazers_count"),o.optInt("forks_count"),o.optInt("open_issues_count"));
    }
}
