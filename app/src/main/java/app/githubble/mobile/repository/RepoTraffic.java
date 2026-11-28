package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Repository traffic for the last 14 days: views, clones, popular pages and referrers. */
public final class RepoTraffic {
    private RepoTraffic() {}

    public static final class Series {
        public int count, uniques; public final List<Integer> perDay = new ArrayList<>();
    }

    public static final class Item {
        public final String label; public final int count, uniques;
        Item(String label, int count, int uniques) { this.label = label; this.count = count; this.uniques = uniques; }
    }

    static Series parse(JSONObject json, String listKey) {
        Series s = new Series();
        s.count = json.optInt("count"); s.uniques = json.optInt("uniques");
        JSONArray days = json.optJSONArray(listKey);
        if (days != null) for (int i = 0; i < days.length(); i++) { JSONObject d = days.optJSONObject(i); s.perDay.add(d == null ? 0 : d.optInt("count")); }
        return s;
    }

    public static Series views(GitHubApiClient api, String repo) throws Exception { return parse(Gh.object(api, Gh.repo(repo) + "/traffic/views"), "views"); }

    public static Series clones(GitHubApiClient api, String repo) throws Exception { return parse(Gh.object(api, Gh.repo(repo) + "/traffic/clones"), "clones"); }

    static List<Item> items(JSONArray a, String labelKey) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null) out.add(new Item(x.optString(labelKey), x.optInt("count"), x.optInt("uniques"))); }
        return out;
    }

    public static List<Item> paths(GitHubApiClient api, String repo) throws Exception { return items(Gh.array(api, Gh.repo(repo) + "/traffic/popular/paths"), "path"); }

    public static List<Item> referrers(GitHubApiClient api, String repo) throws Exception { return items(Gh.array(api, Gh.repo(repo) + "/traffic/popular/referrers"), "referrer"); }

    /** One block character per day, scaled to the busiest day. */
    public static String spark(List<Integer> values) {
        final String bars = "▁▂▃▄▅▆▇█";
        int max = 0;
        for (int v : values) max = Math.max(max, v);
        StringBuilder b = new StringBuilder();
        for (int v : values) b.append(max == 0 || v == 0 ? bars.charAt(0) : bars.charAt(Math.min(bars.length() - 1, (int) Math.ceil(v * (bars.length() - 1.0) / max))));
        return b.toString();
    }
}
