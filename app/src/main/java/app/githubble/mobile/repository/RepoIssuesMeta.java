package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Labels and milestones of a repository. */
public final class RepoIssuesMeta {
    private RepoIssuesMeta() {}

    public static final class Label {
        public final String name, color, description;
        Label(String name, String color, String description) { this.name = name; this.color = color; this.description = description; }
    }

    public static final class Milestone {
        public final int number, open, closed; public final String title, description, state, due;
        Milestone(int number, String title, String description, String state, String due, int open, int closed) { this.number = number; this.title = title; this.description = description; this.state = state; this.due = due; this.open = open; this.closed = closed; }
    }

    static String text(JSONObject o, String key) { return o.isNull(key) ? "" : o.optString(key, ""); }

    public static String checkColor(String raw) {
        String c = raw == null ? "" : raw.trim();
        if (c.startsWith("#")) c = c.substring(1);
        if (!c.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Цвет — шесть шестнадцатеричных цифр, например d73a4a");
        return c.toLowerCase(java.util.Locale.ROOT);
    }

    public static String checkLabelName(String raw) {
        String n = raw == null ? "" : raw.trim();
        if (n.isEmpty() || n.length() > 50) throw new IllegalArgumentException("Название метки — от 1 до 50 символов");
        return n;
    }

    static List<Label> parseLabels(JSONArray a) {
        List<Label> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null) out.add(new Label(x.optString("name"), x.optString("color"), text(x, "description"))); }
        return out;
    }

    public static List<Label> labels(GitHubApiClient api, String repo) throws Exception {
        List<Label> out = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            JSONArray a = Gh.array(api, Gh.repo(repo) + "/labels?per_page=100&page=" + page);
            out.addAll(parseLabels(a));
            if (a.length() < 100) break;
        }
        return out;
    }

    static JSONObject labelBody(String name, String color, String description) throws Exception {
        String d = description == null ? "" : description.trim();
        if (d.length() > 100) throw new IllegalArgumentException("Описание метки — не длиннее 100 символов");
        return new JSONObject().put("name", checkLabelName(name)).put("color", checkColor(color)).put("description", d);
    }

    public static void createLabel(GitHubApiClient api, String repo, String name, String color, String description) throws Exception {
        api.postJson(Gh.repo(repo) + "/labels", labelBody(name, color, description), true);
    }

    public static void updateLabel(GitHubApiClient api, String repo, String oldName, String name, String color, String description) throws Exception {
        JSONObject body = labelBody(name, color, description);
        body.put("new_name", body.remove("name"));
        api.patchJson(Gh.repo(repo) + "/labels/" + Gh.component(oldName), body);
    }

    public static void deleteLabel(GitHubApiClient api, String repo, String name) throws Exception {
        try { api.delete(Gh.repo(repo) + "/labels/" + Gh.component(name)); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }

    static List<Milestone> parseMilestones(JSONArray a) {
        List<Milestone> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null) out.add(new Milestone(x.optInt("number"), x.optString("title"), text(x, "description"), x.optString("state"), text(x, "due_on"), x.optInt("open_issues"), x.optInt("closed_issues")));
        }
        return out;
    }

    public static List<Milestone> milestones(GitHubApiClient api, String repo) throws Exception { return parseMilestones(Gh.array(api, Gh.repo(repo) + "/milestones?state=all&per_page=100")); }

    /** «ГГГГ-ММ-ДД» or empty to ISO time for GitHub (JSON null for no due date). */
    static Object due(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return JSONObject.NULL;
        if (!v.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Срок в формате ГГГГ-ММ-ДД или пустой");
        return v + "T00:00:00Z";
    }

    static JSONObject milestoneBody(String title, String description, String due, boolean open) throws Exception {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || t.length() > 255) throw new IllegalArgumentException("Название этапа — от 1 до 255 символов");
        return new JSONObject().put("title", t).put("description", description == null ? "" : description.trim()).put("due_on", due(due)).put("state", open ? "open" : "closed");
    }

    public static void createMilestone(GitHubApiClient api, String repo, String title, String description, String due) throws Exception {
        api.postJson(Gh.repo(repo) + "/milestones", milestoneBody(title, description, due, true), true);
    }

    public static void updateMilestone(GitHubApiClient api, String repo, int number, String title, String description, String due, boolean open) throws Exception {
        api.patchJson(Gh.repo(repo) + "/milestones/" + number, milestoneBody(title, description, due, open));
    }

    public static void deleteMilestone(GitHubApiClient api, String repo, int number) throws Exception {
        try { api.delete(Gh.repo(repo) + "/milestones/" + number); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }
}
