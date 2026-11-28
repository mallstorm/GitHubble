package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actions for one repository: workflows (on/off, manual dispatch with inputs), runs (re-run, cancel, delete), caches. */
public final class ActionsOps {
    private ActionsOps() {}

    public static final class Workflow {
        public final long id; public final String name, path, state;
        Workflow(long id, String name, String path, String state) { this.id = id; this.name = name; this.path = path; this.state = state; }
        public boolean active() { return "active".equals(state); }
    }

    public static final class Run {
        public final long id; public final int attempt; public final String title, workflow, branch, status, conclusion, event, date, url;
        Run(long id, int attempt, String title, String workflow, String branch, String status, String conclusion, String event, String date, String url) {
            this.id = id; this.attempt = attempt; this.title = title; this.workflow = workflow; this.branch = branch; this.status = status; this.conclusion = conclusion; this.event = event; this.date = date; this.url = url;
        }
        public boolean running() { return "in_progress".equals(status) || "queued".equals(status) || "waiting".equals(status) || "pending".equals(status); }
        public boolean failed() { return "failure".equals(conclusion) || "timed_out".equals(conclusion) || "cancelled".equals(conclusion) || "startup_failure".equals(conclusion); }
    }

    public static final class Cache {
        public final long id, size; public final String key, ref, created;
        Cache(long id, long size, String key, String ref, String created) { this.id = id; this.size = size; this.key = key; this.ref = ref; this.created = created; }
    }

    public static final class Input {
        public final String name; public String value = "", description = "", type = "";
        Input(String name) { this.name = name; }
    }

    static String base(String repo) { return Gh.repo(repo) + "/actions"; }

    static String str(JSONObject o, String key) { return o.isNull(key) ? "" : o.optString(key, ""); }

    static List<Workflow> parseWorkflows(JSONObject json) {
        List<Workflow> out = new ArrayList<>();
        JSONArray list = json.optJSONArray("workflows");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x != null) out.add(new Workflow(x.optLong("id"), x.optString("name"), x.optString("path"), x.optString("state")));
        }
        return out;
    }

    public static List<Workflow> workflows(GitHubApiClient api, String repo) throws Exception { return parseWorkflows(Gh.object(api, base(repo) + "/workflows?per_page=100")); }

    public static void setEnabled(GitHubApiClient api, String repo, long id, boolean on) throws Exception {
        api.executeText("PUT", base(repo) + "/workflows/" + id + (on ? "/enable" : "/disable"), "", true);
    }

    static List<Run> parseRuns(JSONObject json) {
        List<Run> out = new ArrayList<>();
        JSONArray list = json.optJSONArray("workflow_runs");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x == null) continue;
            String title = str(x, "display_title");
            if (title.isEmpty()) title = str(x, "name");
            out.add(new Run(x.optLong("id"), x.optInt("run_attempt", 1), title, str(x, "name"), str(x, "head_branch"), str(x, "status"), str(x, "conclusion"), str(x, "event"), str(x, "created_at"), str(x, "html_url")));
        }
        return out;
    }

    public static List<Run> runs(GitHubApiClient api, String repo) throws Exception { return parseRuns(Gh.object(api, base(repo) + "/runs?per_page=25")); }

    public static void rerun(GitHubApiClient api, String repo, long run, boolean failedOnly) throws Exception {
        api.executeText("POST", base(repo) + "/runs/" + run + (failedOnly ? "/rerun-failed-jobs" : "/rerun"), "", true);
    }

    public static void cancel(GitHubApiClient api, String repo, long run) throws Exception { api.executeText("POST", base(repo) + "/runs/" + run + "/cancel", "", true); }

    public static void deleteRun(GitHubApiClient api, String repo, long run) throws Exception {
        try { api.delete(base(repo) + "/runs/" + run); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }

    // ---- manual dispatch -------------------------------------------------------------------------------------------

    static int indent(String line) { int n = 0; while (n < line.length() && line.charAt(n) == ' ') n++; return n; }

    static String scalar(String raw) {
        String v = raw.trim();
        int hash = v.indexOf(" #");
        if (hash >= 0 && !(v.startsWith("\"") || v.startsWith("'"))) v = v.substring(0, hash).trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) v = v.substring(1, v.length() - 1);
        return v;
    }

    /** Best-effort read of {@code on.workflow_dispatch.inputs} from the workflow file (names, defaults, descriptions, types). */
    public static List<Input> parseInputs(String yaml) {
        List<Input> out = new ArrayList<>();
        if (yaml == null) return out;
        String[] lines = yaml.replace("\r", "").split("\n");
        int i = 0;
        while (i < lines.length && !lines[i].trim().matches("workflow_dispatch\\s*:\\s*(#.*)?")) i++;
        if (i >= lines.length) return out;
        int wd = indent(lines[i]);
        i++;
        while (i < lines.length && (lines[i].trim().isEmpty() || lines[i].trim().startsWith("#"))) i++;
        if (i >= lines.length || indent(lines[i]) <= wd || !lines[i].trim().matches("inputs\\s*:\\s*(#.*)?")) return out;
        int inputsIndent = indent(lines[i]);
        i++;
        int keyIndent = -1;
        Input current = null;
        for (; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int ind = indent(line);
            if (ind <= inputsIndent) break;
            if (keyIndent < 0) keyIndent = ind;
            if (ind == keyIndent && t.endsWith(":") || ind == keyIndent && t.matches("[^:]+:\\s*(#.*)?")) {
                current = new Input(t.substring(0, t.indexOf(':')).trim().replace("\"", "").replace("'", ""));
                out.add(current);
            } else if (current != null && ind > keyIndent) {
                int colon = t.indexOf(':');
                if (colon <= 0) continue;
                String key = t.substring(0, colon).trim(), value = scalar(t.substring(colon + 1));
                if (key.equals("default")) current.value = value; else if (key.equals("description")) current.description = value; else if (key.equals("type")) current.type = value;
            }
        }
        return out;
    }

    /** «name=value» per line into an ordered map; names follow the workflow input rules. */
    public static Map<String, String> inputLines(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.replace("\r", "").split("\n")) {
            if (line.trim().isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("Строка «" + line.trim() + "»: нужен вид имя=значение");
            String name = line.substring(0, eq).trim();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Недопустимое имя параметра «" + name + "»");
            out.put(name, line.substring(eq + 1).trim());
        }
        if (out.size() > 25) throw new IllegalArgumentException("GitHub принимает не больше 25 параметров");
        return out;
    }

    public static void dispatch(GitHubApiClient api, String repo, long workflow, String ref, Map<String, String> inputs) throws Exception {
        if (ref == null || ref.trim().isEmpty()) throw new IllegalArgumentException("Выберите ветку или тег");
        JSONObject body = new JSONObject().put("ref", ref.trim());
        if (!inputs.isEmpty()) { JSONObject in = new JSONObject(); for (Map.Entry<String, String> e : inputs.entrySet()) in.put(e.getKey(), e.getValue()); body.put("inputs", in); }
        api.executeText("POST", base(repo) + "/workflows/" + workflow + "/dispatches", body.toString(), true);
    }

    /** Text of the workflow file on the branch, empty when it cannot be read. */
    public static String workflowText(GitHubApiClient api, String repo, String path, String ref) throws Exception {
        RepoFiles.File f = RepoFiles.get(api, repo, path, ref);
        return f.exists ? f.text : "";
    }

    // ---- caches ----------------------------------------------------------------------------------------------------

    static List<Cache> parseCaches(JSONObject json) {
        List<Cache> out = new ArrayList<>();
        JSONArray list = json.optJSONArray("actions_caches");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x != null) out.add(new Cache(x.optLong("id"), x.optLong("size_in_bytes"), x.optString("key"), x.optString("ref"), x.optString("created_at")));
        }
        return out;
    }

    public static List<Cache> caches(GitHubApiClient api, String repo) throws Exception { return parseCaches(Gh.object(api, base(repo) + "/caches?per_page=50&sort=size_in_bytes&direction=desc")); }

    public static void deleteCache(GitHubApiClient api, String repo, long id) throws Exception {
        try { api.delete(base(repo) + "/caches/" + id); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }
}
