package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Settings → Collaborators: people with access and pending invitations. */
public final class Collaborators {
    private Collaborators() {}

    public static final String[] ROLES = {"pull", "triage", "push", "maintain", "admin"};
    public static final String[] ROLE_LABELS = {"Read", "Triage", "Write", "Maintain", "Admin"};

    public static final class Person {
        public final String login, role; Person(String login, String role) { this.login = login; this.role = role; }
    }

    public static final class Invite {
        public final long id; public final String login, role;
        Invite(long id, String login, String role) { this.id = id; this.login = login; this.role = role; }
    }

    public static String checkUser(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.startsWith("@")) u = u.substring(1);
        if (!u.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")) throw new IllegalArgumentException("Недопустимое имя пользователя GitHub");
        return u;
    }

    public static String checkRole(String role) {
        for (String r : ROLES) if (r.equals(role)) return role;
        throw new IllegalArgumentException("Недопустимая роль");
    }

    public static String label(String role) {
        for (int i = 0; i < ROLES.length; i++) if (ROLES[i].equals(role)) return ROLE_LABELS[i];
        return role == null ? "" : role;
    }

    static String roleOf(JSONObject x) {
        String name = x.optString("role_name", "");
        if (!name.isEmpty()) return name;
        JSONObject p = x.optJSONObject("permissions");
        if (p == null) return "";
        if (p.optBoolean("admin")) return "admin";
        if (p.optBoolean("maintain")) return "maintain";
        if (p.optBoolean("push")) return "push";
        if (p.optBoolean("triage")) return "triage";
        return "pull";
    }

    static List<Person> parsePeople(JSONArray a) {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null && !x.optString("login").isEmpty()) out.add(new Person(x.optString("login"), roleOf(x))); }
        return out;
    }

    static List<Invite> parseInvites(JSONArray a) {
        List<Invite> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            JSONObject invitee = x.optJSONObject("invitee");
            out.add(new Invite(x.optLong("id"), invitee == null ? "" : invitee.optString("login"), x.optString("permissions", "")));
        }
        return out;
    }

    public static List<Person> people(GitHubApiClient api, String repo) throws Exception { return parsePeople(Gh.array(api, Gh.repo(repo) + "/collaborators?per_page=100&affiliation=all")); }

    public static List<Invite> invites(GitHubApiClient api, String repo) throws Exception { return parseInvites(Gh.array(api, Gh.repo(repo) + "/invitations?per_page=100")); }

    public static void invite(GitHubApiClient api, String repo, String user, String role) throws Exception {
        api.executeText("PUT", Gh.repo(repo) + "/collaborators/" + checkUser(user), new JSONObject().put("permission", checkRole(role)).toString(), true);
    }

    public static void remove(GitHubApiClient api, String repo, String user) throws Exception {
        try { api.delete(Gh.repo(repo) + "/collaborators/" + checkUser(user)); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }

    public static void cancelInvite(GitHubApiClient api, String repo, long id) throws Exception {
        try { api.delete(Gh.repo(repo) + "/invitations/" + id); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }
}
