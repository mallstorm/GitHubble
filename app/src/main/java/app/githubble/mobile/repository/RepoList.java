package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** The repositories of the signed-in account that the account-wide screens work on. */
public final class RepoList {
    private RepoList() {}

    public static final class Item {
        public final String full; public final boolean archived, fork, priv, admin;
        Item(String full, boolean archived, boolean fork, boolean priv, boolean admin) { this.full = full; this.archived = archived; this.fork = fork; this.priv = priv; this.admin = admin; }
        public String tags() { return (priv ? "private" : "public") + (fork ? " · fork" : "") + (archived ? " · архив" : "") + (admin ? "" : " · нет прав admin"); }
    }

    static List<Item> parse(JSONArray array) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject x = array.optJSONObject(i);
            if (x == null || !x.optString("full_name").matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) continue;
            JSONObject p = x.optJSONObject("permissions");
            out.add(new Item(x.optString("full_name"), x.optBoolean("archived"), x.optBoolean("fork"), x.optBoolean("private"), p != null && p.optBoolean("admin")));
        }
        return out;
    }

    /** Most recently pushed first; at most {@code max} repositories. */
    public static List<Item> load(GitHubApiClient api, int max) throws Exception {
        List<Item> out = new ArrayList<>();
        for (int page = 1; out.size() < max && page <= 6; page++) {
            JSONArray a = Gh.array(api, "https://api.github.com/user/repos?affiliation=owner,collaborator,organization_member&sort=pushed&per_page=100&page=" + page);
            out.addAll(parse(a));
            if (a.length() < 100) break;
        }
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }
}
