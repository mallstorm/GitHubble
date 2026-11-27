package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Releases and tags: create, edit, delete, auto-generated notes, asset removal; lightweight and annotated tags. */
public final class RepoReleases {
    private RepoReleases() {}

    public static final class Asset {
        public final long id; public final String name; public final long size; public final int downloads;
        Asset(long id, String name, long size, int downloads) { this.id = id; this.name = name; this.size = size; this.downloads = downloads; }
    }

    public static final class Release {
        public final long id; public final String tag, name, body, url, date; public final boolean draft, prerelease; public final List<Asset> assets;
        Release(long id, String tag, String name, String body, String url, String date, boolean draft, boolean prerelease, List<Asset> assets) {
            this.id = id; this.tag = tag; this.name = name; this.body = body; this.url = url; this.date = date; this.draft = draft; this.prerelease = prerelease; this.assets = assets;
        }
        public String title() { return name.isEmpty() ? tag : name; }
    }

    public static final class Tag {
        public final String name, sha;
        Tag(String name, String sha) { this.name = name; this.sha = sha; }
    }

    public static String checkTag(String raw) {
        try { return RepoRevert.checkBranchName(raw); }
        catch (IllegalArgumentException bad) { throw new IllegalArgumentException(String.valueOf(bad.getMessage()).replace("ветки", "тега")); }
    }

    static String text(JSONObject o, String key) { return o.isNull(key) ? "" : o.optString(key, ""); }

    static List<Release> parse(JSONArray array) {
        List<Release> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject x = array.optJSONObject(i);
            if (x == null) continue;
            List<Asset> assets = new ArrayList<>();
            JSONArray list = x.optJSONArray("assets");
            if (list != null) for (int j = 0; j < list.length(); j++) {
                JSONObject f = list.optJSONObject(j);
                if (f != null) assets.add(new Asset(f.optLong("id"), f.optString("name"), f.optLong("size"), f.optInt("download_count")));
            }
            String date = text(x, "published_at");
            if (date.isEmpty()) date = text(x, "created_at");
            out.add(new Release(x.optLong("id"), x.optString("tag_name"), text(x, "name"), text(x, "body"), x.optString("html_url"), date, x.optBoolean("draft"), x.optBoolean("prerelease"), assets));
        }
        return out;
    }

    public static List<Release> list(GitHubApiClient api, String repo) throws Exception { return parse(Gh.array(api, Gh.repo(repo) + "/releases?per_page=30")); }

    static JSONObject createBody(String tag, String target, String name, String text, boolean draft, boolean prerelease, boolean generateNotes) throws Exception {
        JSONObject o = new JSONObject().put("tag_name", checkTag(tag)).put("draft", draft).put("prerelease", prerelease).put("generate_release_notes", generateNotes);
        if (target != null && !target.trim().isEmpty()) o.put("target_commitish", target.trim());
        if (name != null && !name.trim().isEmpty()) o.put("name", name.trim());
        if (text != null && !text.trim().isEmpty()) o.put("body", text);
        return o;
    }

    /** Returns the page of the new release. */
    public static String create(GitHubApiClient api, String repo, String tag, String target, String name, String text, boolean draft, boolean prerelease, boolean generateNotes) throws Exception {
        return api.postJson(Gh.repo(repo) + "/releases", createBody(tag, target, name, text, draft, prerelease, generateNotes), true).optString("html_url", "");
    }

    public static void update(GitHubApiClient api, String repo, long id, String name, String text, boolean draft, boolean prerelease) throws Exception {
        api.patchJson(Gh.repo(repo) + "/releases/" + id, new JSONObject().put("name", name == null ? "" : name.trim()).put("body", text == null ? "" : text).put("draft", draft).put("prerelease", prerelease));
    }

    public static void delete(GitHubApiClient api, String repo, Release release, boolean alsoTag) throws Exception {
        try { api.delete(Gh.repo(repo) + "/releases/" + release.id); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
        if (alsoTag && !release.tag.isEmpty()) deleteTag(api, repo, release.tag);
    }

    public static void deleteAsset(GitHubApiClient api, String repo, long id) throws Exception {
        try { api.delete(Gh.repo(repo) + "/releases/assets/" + id); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }

    /** Notes that GitHub would generate for the tag: {title, body}. */
    public static String[] generateNotes(GitHubApiClient api, String repo, String tag, String target) throws Exception {
        JSONObject body = new JSONObject().put("tag_name", checkTag(tag));
        if (target != null && !target.trim().isEmpty()) body.put("target_commitish", target.trim());
        JSONObject r = api.postJson(Gh.repo(repo) + "/releases/generate-notes", body, true);
        return new String[]{r.optString("name"), r.optString("body")};
    }

    static List<Tag> parseTags(JSONArray array) {
        List<Tag> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject x = array.optJSONObject(i);
            if (x == null) continue;
            JSONObject commit = x.optJSONObject("commit");
            out.add(new Tag(x.optString("name"), commit == null ? "" : commit.optString("sha")));
        }
        return out;
    }

    public static List<Tag> tags(GitHubApiClient api, String repo) throws Exception { return parseTags(Gh.array(api, Gh.repo(repo) + "/tags?per_page=100")); }

    static boolean isSha(String s) { return s != null && s.matches("[0-9a-fA-F]{40}"); }

    /** A tag on a branch head or on a full commit sha; with a message it is an annotated tag. */
    public static void createTag(GitHubApiClient api, String repo, String tag, String branchOrSha, String message) throws Exception {
        String name = checkTag(tag);
        String ref = branchOrSha == null ? "" : branchOrSha.trim();
        if (ref.isEmpty()) throw new IllegalArgumentException("Выберите ветку или укажите полный sha коммита");
        String sha = isSha(ref) ? ref.toLowerCase(java.util.Locale.ROOT) : RepoRevert.headSha(api, repo, ref);
        String target = sha;
        if (message != null && !message.trim().isEmpty())
            target = api.postJson(Gh.repo(repo) + "/git/tags", new JSONObject().put("tag", name).put("message", message.trim()).put("object", sha).put("type", "commit"), true).getString("sha");
        try { api.postJson(Gh.repo(repo) + "/git/refs", new JSONObject().put("ref", "refs/tags/" + name).put("sha", target), true); }
        catch (GitHubApiClient.ApiException e) {
            if (String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("already exists")) throw new IllegalArgumentException("Тег «" + name + "» уже есть");
            throw e;
        }
    }

    public static void deleteTag(GitHubApiClient api, String repo, String tag) throws Exception {
        try { api.delete(Gh.repo(repo) + "/git/refs/tags/" + Gh.segment(checkTag(tag))); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404 && e.statusCode != 422) throw e; }
    }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024L * 1024) return String.format(java.util.Locale.US, "%.1f КБ", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f МБ", bytes / 1048576.0);
    }
}
