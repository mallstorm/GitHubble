package app.githubble.mobile.mirror;

import android.content.Context;
import android.content.SharedPreferences;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Imports an arbitrary PUBLIC GitHub repository into a NEW, non-fork repository
 * on one of the connected accounts. Only GitHub Actions accesses the Git objects.
 *
 * State is durable: imports keep running after the Activity is closed; refresh()
 * verifies the Actions result and finishes default-branch/bootstrap cleanup.
 */
public final class ExternalRepositoryImport {
    private static final String API = "https://api.github.com/repos/";
    private static final String KEY = "external_repository_import_jobs_v1";
    public static final String PREPARING = "preparing";
    public static final String RUNNING = "running";
    public static final String VERIFYING = "verifying";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    private final GitHubAccountStore accounts;
    private final SharedPreferences prefs;

    public ExternalRepositoryImport(Context context, GitHubAccountStore accounts) {
        this.accounts = accounts;
        this.prefs = context.getApplicationContext().getSharedPreferences("githubble_external_import", Context.MODE_PRIVATE);
    }

    public static final class Job {
        public String id = "";
        public String source = "";
        public String destination = "";
        public String accountId = "";
        public String sourceDefault = "";
        public String sourceSha = "";
        public String bootstrapDefault = "";
        public String bootstrapSha = "";
        public String state = PREPARING;
        public String error = "";
        public String runUrl = "";
        public long runId = 0L;
        public long createdAt = 0L;

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("source", source); o.put("destination", destination);
            o.put("accountId", accountId); o.put("sourceDefault", sourceDefault);
            o.put("sourceSha", sourceSha); o.put("bootstrapDefault", bootstrapDefault);
            o.put("bootstrapSha", bootstrapSha); o.put("state", state);
            o.put("error", error); o.put("runUrl", runUrl);
            o.put("runId", runId); o.put("createdAt", createdAt);
            return o;
        }

        static Job from(JSONObject o) {
            Job j = new Job();
            j.id = o.optString("id"); j.source = o.optString("source");
            j.destination = o.optString("destination"); j.accountId = o.optString("accountId");
            j.sourceDefault = o.optString("sourceDefault"); j.sourceSha = o.optString("sourceSha");
            j.bootstrapDefault = o.optString("bootstrapDefault");
            j.bootstrapSha = o.optString("bootstrapSha");
            j.state = o.optString("state", PREPARING); j.error = o.optString("error");
            j.runUrl = o.optString("runUrl"); j.runId = o.optLong("runId");
            j.createdAt = o.optLong("createdAt");
            return j;
        }
    }

    public synchronized List<Job> list() {
        List<Job> result = new ArrayList<>();
        try {
            JSONArray jobs = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject o = jobs.optJSONObject(i);
                if (o != null) result.add(Job.from(o));
            }
        } catch (Exception ignored) { }
        return result;
    }

    public synchronized Job findByDestination(String destination) {
        List<Job> jobs = list();
        for (int i = jobs.size() - 1; i >= 0; i--)
            if (jobs.get(i).destination.equalsIgnoreCase(destination)) return jobs.get(i);
        return null;
    }

    public synchronized Job find(String id) {
        for (Job job : list()) if (job.id.equals(id)) return job;
        return null;
    }

    private synchronized void save(Job job) throws Exception {
        List<Job> jobs = list();
        jobs.removeIf(old -> old.id.equals(job.id));
        jobs.add(job);
        while (jobs.size() > 30) jobs.remove(0);
        JSONArray data = new JSONArray();
        for (Job item : jobs) data.put(item.json());
        if (!prefs.edit().putString(KEY, data.toString()).commit())
            throw new IllegalStateException("Не удалось сохранить состояние импорта");
    }

    public static void validateSource(String full) {
        MirrorPolicy.requireRepository(full);
    }

    public static void validateName(String name) {
        MirrorPolicy.requireName(name);
    }

    private static void requireBranch(String branch) {
        if (branch == null || !branch.matches("[A-Za-z0-9_./-]{1,250}")
                || branch.contains("..") || branch.startsWith("/") || branch.endsWith("/"))
            throw new IllegalArgumentException("Неподдерживаемое имя основной ветки");
    }

    private static String encode(String part) throws Exception {
        return URLEncoder.encode(part, "UTF-8").replace("+", "%20");
    }

    private GitHubAccountStore.Account account(String id) {
        for (GitHubAccountStore.Account item : accounts.listAccounts())
            if (item.id.equals(id)) return item;
        throw new IllegalStateException("Аккаунт назначения не подключён");
    }

    private GitHubApiClient api(String accountId) throws Exception {
        GitHubAccountStore.Account account = account(accountId);
        String token = accounts.tokenForAccount(account.id);
        if (token == null || token.isEmpty())
            throw new IllegalStateException("Повторно авторизуйте @" + account.login);
        GitHubSession session = new GitHubSession(token, account.login, 0L);
        GitHubApiClient api = new GitHubApiClient(new GitHubSession.Source() {
            @Override public GitHubSession capture() { return session; }
            @Override public boolean isCurrent(GitHubSession current) { return current == session; }
            @Override public void reauthorization(GitHubSession current, boolean required) { }
        }, null);
        JSONObject identity = api.getJsonFresh("https://api.github.com/user");
        if (!account.login.equalsIgnoreCase(identity.optString("login", "")))
            throw new SecurityException("Токен принадлежит другому аккаунту");
        return api;
    }

    private static String head(GitHubApiClient client, String full, String branch) throws Exception {
        JSONObject ref = client.getJsonFresh(API + full + "/git/ref/heads/" + encode(branch));
        JSONObject object = ref.optJSONObject("object");
        String sha = object == null ? "" : object.optString("sha");
        if (!sha.matches("[0-9a-f]{40}"))
            throw new SecurityException("Невозможно проверить Git SHA");
        return sha;
    }

    /**
     * Creates a brand new destination repository and dispatches a temporary
     * workflow on its default branch. Never modifies the public source.
     */
    public Job start(String sourceFull, String destinationAccountId, String destinationName,
                     boolean privateDestination) throws Exception {
        validateSource(sourceFull);
        validateName(destinationName);
        GitHubAccountStore.Account account = account(destinationAccountId);
        String destination = account.login + "/" + destinationName;
        if (sourceFull.equalsIgnoreCase(destination))
            throw new IllegalArgumentException("Источник и назначение должны отличаться");

        GitHubApiClient client = api(account.id);
        JSONObject source = client.getJsonFresh(API + sourceFull);
        if (source.optBoolean("private", true))
            throw new IllegalArgumentException("Импорт поддерживает только публичные репозитории");
        String defaultBranch = source.optString("default_branch", "");
        requireBranch(defaultBranch);
        String expectedSha = head(client, sourceFull, defaultBranch);
        try {
            client.getJsonFresh(API + destination);
            throw new IllegalStateException("Репозиторий назначения уже существует");
        } catch (GitHubApiClient.ApiException missing) {
            if (missing.statusCode != 404) throw missing;
        }

        Job job = new Job();
        job.id = UUID.randomUUID().toString().replace("-", "");
        job.source = sourceFull;
        job.destination = destination;
        job.accountId = account.id;
        job.sourceDefault = defaultBranch;
        job.sourceSha = expectedSha;
        job.runUrl = "https://github.com/" + destination + "/actions";
        job.createdAt = System.currentTimeMillis();
        save(job);

        try {
            JSONObject created = client.postJson("https://api.github.com/user/repos",
                    new JSONObject().put("name", destinationName)
                            .put("private", privateDestination).put("auto_init", true), true);
            if (!destination.equalsIgnoreCase(created.optString("full_name", ""))
                    || created.optBoolean("fork", true)
                    || created.optBoolean("private") != privateDestination)
                throw new SecurityException("GitHub создал репозиторий с неожиданными параметрами");
            job.bootstrapDefault = created.optString("default_branch", "");
            requireBranch(job.bootstrapDefault);
            // Refuse to overwrite an unrelated repository even after creation.
            head(client, destination, job.bootstrapDefault);
            JSONObject workflow = new JSONObject()
                    .put("message", "chore(import): temporary GitHubble import runner")
                    .put("branch", job.bootstrapDefault)
                    .put("content", Base64.getEncoder().encodeToString(
                            ExternalImportWorkflow.yaml().getBytes(StandardCharsets.UTF_8)));
            client.putJson(API + destination + "/contents/" + ExternalImportWorkflow.PATH, workflow);
            job.bootstrapSha = head(client, destination, job.bootstrapDefault);
            save(job);
            JSONObject inputs = new JSONObject()
                    .put("request_id", job.id)
                    .put("source", job.source)
                    .put("source_default", job.sourceDefault)
                    .put("source_sha", job.sourceSha)
                    .put("bootstrap_branch", job.bootstrapDefault)
                    .put("bootstrap_sha", job.bootstrapSha);
            JSONObject dispatch = new JSONObject().put("ref", job.bootstrapDefault).put("inputs", inputs);
            client.executeText("POST", API + destination + "/actions/workflows/"
                    + ExternalImportWorkflow.PATH.substring(ExternalImportWorkflow.PATH.lastIndexOf('/') + 1)
                    + "/dispatches", dispatch.toString(), true);
            job.state = RUNNING;
            save(job);
            return job;
        } catch (Exception error) {
            job.state = FAILED;
            job.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            save(job);
            throw new IllegalStateException("Импорт " + destination + " не запущен: " + job.error
                    + ". Созданный репозиторий не удалён автоматически.", error);
        }
    }

    /**
     * Safe repeatable completion. The Actions job copies refs and LFS first;
     * only this authenticated client changes the default branch afterward.
     */
    public Job refresh(String jobId) throws Exception {
        Job job = find(jobId);
        if (job == null) throw new IllegalArgumentException("Импорт не найден");
        if (DONE.equals(job.state)) return job;
        if (FAILED.equals(job.state)) return job;
        GitHubApiClient client = api(job.accountId);
        JSONObject result = client.getJsonFresh(API + job.destination
                + "/actions/runs?event=workflow_dispatch&per_page=100");
        JSONArray runs = result.optJSONArray("workflow_runs");
        JSONObject matching = null;
        if (runs != null) {
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.optJSONObject(i);
                if (run != null && ("GitHubble Import " + job.id)
                        .equals(run.optString("display_title", ""))) {
                    matching = run;
                    break;
                }
            }
        }
        if (matching == null) return job;
        job.runId = matching.optLong("id");
        job.runUrl = matching.optString("html_url", job.runUrl);
        if (!"completed".equals(matching.optString("status"))) {
            job.state = RUNNING;
            save(job);
            return job;
        }
        if (!"success".equals(matching.optString("conclusion"))) {
            job.state = FAILED;
            job.error = "GitHub Actions: " + matching.optString("conclusion", "failure")
                    + ". Подробности в журнале выполнения.";
            save(job);
            return job;
        }

        job.state = VERIFYING;
        save(job);
        try {
            if (!job.sourceSha.equals(head(client, job.destination, job.sourceDefault)))
                throw new SecurityException("Git SHA основной ветки не совпадает с исходным");

            if (!job.sourceDefault.equals(job.bootstrapDefault)) {
                JSONObject dest = client.getJsonFresh(API + job.destination);
                if (!job.sourceDefault.equals(dest.optString("default_branch")))
                    client.patchJson(API + job.destination,
                            new JSONObject().put("default_branch", job.sourceDefault));
                // Delete the temporary default branch only if the original had no such branch.
                boolean originalHasBootstrap = true;
                try {
                    head(client, job.source, job.bootstrapDefault);
                } catch (GitHubApiClient.ApiException missing) {
                    if (missing.statusCode != 404) throw missing;
                    originalHasBootstrap = false;
                }
                if (!originalHasBootstrap) {
                    try {
                        client.delete(API + job.destination + "/git/refs/heads/"
                                + encode(job.bootstrapDefault));
                    } catch (GitHubApiClient.ApiException missing) {
                        if (missing.statusCode != 404) throw missing;
                    }
                }
            }
            job.state = DONE;
            job.error = "";
        } catch (Exception error) {
            job.state = VERIFYING; // Retry finishing after permissions/network recovery.
            job.error = "Требуется завершить проверку/настройку ветки: " + error.getMessage();
        }
        save(job);
        return job;
    }
}
