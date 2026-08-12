package app.githubble.mobile.tracking;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.githubble.mobile.network.GitHubApiClient;

/** Compares GitHub API snapshots and records only real changes after an initial baseline. */
public final class TrackingEngine {
    public static final String TYPE_NEW_REPOSITORY = "NEW_REPOSITORY";
    public static final String TYPE_RELEASE = "RELEASE";
    public static final String TYPE_PUSH = "PUSH";
    public static final String TYPE_POPULARITY = "POPULARITY";
    public static final String TYPE_STATUS = "STATUS";

    public static final class CheckResult {
        public final List<TrackingDatabase.Event> newEvents = new ArrayList<>();
        public int checkedDevelopers;
        public int failedDevelopers;
        public int checkedRepositories;
        public boolean baselineCreated;
    }

    private final Context context;
    private final TrackingDatabase db;
    private final GitHubApiClient api;
    private final SharedPreferences prefs;

    public TrackingEngine(Context context, TrackingDatabase db, GitHubApiClient api) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.api = api;
        this.prefs = this.context.getSharedPreferences("githubble", Context.MODE_PRIVATE);
    }

    /** Adds a developer and records a baseline without generating historical false-positive events. */
    public CheckResult addDeveloper(String rawLogin) throws Exception {
        String login = normalizeLogin(rawLogin);
        if (login.isEmpty()) throw new IllegalArgumentException("Введите GitHub login разработчика");
        JSONObject user = api.getJson("https://api.github.com/users/" + encode(login));
        String canonical = user.optString("login", login);
        TrackingDatabase.Developer existing = db.getDeveloper(canonical);
        TrackingDatabase.Developer developer = existing == null ? new TrackingDatabase.Developer() : existing;
        developer.login = canonical;
        developer.displayName = clean(user.optString("name", ""));
        developer.avatarUrl = user.optString("avatar_url", "");
        developer.htmlUrl = user.optString("html_url", "https://github.com/" + canonical);
        developer.enabled = true;
        if (developer.addedAt <= 0) developer.addedAt = System.currentTimeMillis();
        developer.repositoryCount = user.optInt("public_repos", developer.repositoryCount);
        db.upsertDeveloper(developer);
        CheckResult result = checkDeveloper(canonical, true);
        TrackingJobService.schedule(context, prefs.getInt("tracking_interval_hours", 6));
        return result;
    }

    public CheckResult checkAll() {
        CheckResult total = new CheckResult();
        List<TrackingDatabase.Developer> developers = db.getDevelopers(true);
        for (TrackingDatabase.Developer developer : developers) {
            if (api.getRemaining() >= 0 && api.getRemaining() < 8) break;
            try {
                CheckResult one = checkDeveloper(developer.login, false);
                total.checkedDevelopers += one.checkedDevelopers;
                total.checkedRepositories += one.checkedRepositories;
                total.baselineCreated |= one.baselineCreated;
                total.newEvents.addAll(one.newEvents);
            } catch (Exception e) {
                total.failedDevelopers++;
                TrackingDatabase.Developer current = db.getDeveloper(developer.login);
                if (current != null) {
                    current.lastCheckedAt = System.currentTimeMillis();
                    current.lastError = compactError(e);
                    db.upsertDeveloper(current);
                }
            }
        }
        prefs.edit().putLong("tracking_last_global_check", System.currentTimeMillis()).apply();
        return total;
    }

    public CheckResult checkDeveloper(String rawLogin, boolean forceBaseline) throws Exception {
        String login = normalizeLogin(rawLogin);
        TrackingDatabase.Developer developer = db.getDeveloper(login);
        if (developer == null) throw new IllegalStateException("Разработчик не добавлен в наблюдение");
        boolean baseline = forceBaseline || !developer.baselineComplete;

        JSONObject user = api.getJson("https://api.github.com/users/" + encode(login));
        String canonical = user.optString("login", login);
        JSONArray repos = loadOwnedRepositories(canonical);
        long now = System.currentTimeMillis();
        CheckResult result = new CheckResult();
        result.checkedDevelopers = 1;
        result.checkedRepositories = repos.length();
        result.baselineCreated = baseline;

        List<TrackingDatabase.RepoSnapshot> current = new ArrayList<>();
        Map<Long, TrackingDatabase.RepoSnapshot> oldById = new HashMap<>();
        long totalStars = 0;
        long totalForks = 0;

        for (int i = 0; i < repos.length(); i++) {
            JSONObject repo = repos.optJSONObject(i);
            if (repo == null) continue;
            TrackingDatabase.RepoSnapshot snapshot = fromJson(canonical, repo, now);
            TrackingDatabase.RepoSnapshot old = db.getRepository(snapshot.repoId);
            if (old != null) oldById.put(snapshot.repoId, old);
            current.add(snapshot);
            totalStars += snapshot.stars;
            totalForks += snapshot.forks;

            if (!baseline && old == null && enabled("tracking_notify_new_repositories", true)) {
                TrackingDatabase.Event event = event(
                        TYPE_NEW_REPOSITORY,
                        canonical,
                        snapshot,
                        "Новый репозиторий: " + snapshot.name,
                        clean(snapshot.description).isEmpty() ? "Разработчик @" + canonical + " создал новый репозиторий" : snapshot.description,
                        snapshot.createdAt,
                        "new:" + snapshot.repoId);
                persist(result, event);
            }

            if (!baseline && old != null) compareRepository(old, snapshot, result);
        }

        // Release checks are intentionally limited to the most recently pushed repositories to protect API quota.
        current.sort(Comparator.comparing((TrackingDatabase.RepoSnapshot r) -> safe(r.pushedAt)).reversed());
        int releaseChecks = Math.min(prefs.getInt("tracking_release_check_limit", 8), current.size());
        for (int i = 0; i < releaseChecks; i++) {
            if (api.getRemaining() >= 0 && api.getRemaining() < 5) break;
            TrackingDatabase.RepoSnapshot snapshot = current.get(i);
            TrackingDatabase.RepoSnapshot old = oldById.get(snapshot.repoId);
            loadLatestRelease(snapshot);
            if (!baseline && old != null && enabled("tracking_notify_releases", true)) {
                String oldTag = safe(old.latestReleaseTag);
                String newTag = safe(snapshot.latestReleaseTag);
                if (!oldTag.isEmpty() && !newTag.isEmpty() && !"__none__".equals(newTag) && !oldTag.equals(newTag)) {
                    TrackingDatabase.Event event = event(
                            TYPE_RELEASE,
                            canonical,
                            snapshot,
                            "Новый релиз " + newTag,
                            snapshot.fullName + " выпустил новую версию",
                            snapshot.latestReleasePublishedAt,
                            "release:" + snapshot.repoId + ":" + newTag.toLowerCase(Locale.ROOT));
                    persist(result, event);
                }
            }
        }

        for (TrackingDatabase.RepoSnapshot snapshot : current) db.upsertRepository(snapshot);

        developer.login = canonical;
        developer.displayName = clean(user.optString("name", developer.displayName));
        developer.avatarUrl = user.optString("avatar_url", developer.avatarUrl);
        developer.htmlUrl = user.optString("html_url", developer.htmlUrl);
        developer.lastCheckedAt = now;
        developer.lastError = "";
        developer.enabled = true;
        developer.baselineComplete = true;
        developer.repositoryCount = user.optInt("public_repos", repos.length());
        developer.totalStars = totalStars;
        developer.totalForks = totalForks;
        db.upsertDeveloper(developer);
        return result;
    }

    private void compareRepository(TrackingDatabase.RepoSnapshot old, TrackingDatabase.RepoSnapshot current,
                                   CheckResult result) {
        int starDelta = current.stars - old.stars;
        int forkDelta = current.forks - old.forks;
        int starThreshold = Math.max(1, prefs.getInt("tracking_star_threshold", 10));
        int forkThreshold = Math.max(1, prefs.getInt("tracking_fork_threshold", 3));
        if (enabled("tracking_notify_popularity", true) && (starDelta >= starThreshold || forkDelta >= forkThreshold)) {
            StringBuilder summary = new StringBuilder();
            if (starDelta > 0) summary.append("+").append(starDelta).append(" звёзд");
            if (forkDelta > 0) {
                if (summary.length() > 0) summary.append(" · ");
                summary.append("+").append(forkDelta).append(" forks");
            }
            TrackingDatabase.Event event = event(
                    TYPE_POPULARITY,
                    current.developerLogin,
                    current,
                    "Рост популярности: " + current.name,
                    summary.toString(),
                    Instant.now().toString(),
                    "pop:" + current.repoId + ":" + current.stars + ":" + current.forks);
            persist(result, event);
        }

        if (enabled("tracking_notify_pushes", false)
                && !safe(old.pushedAt).isEmpty()
                && !safe(current.pushedAt).isEmpty()
                && !old.pushedAt.equals(current.pushedAt)) {
            TrackingDatabase.Event event = event(
                    TYPE_PUSH,
                    current.developerLogin,
                    current,
                    "Обновлён репозиторий: " + current.name,
                    "Новые изменения в " + current.fullName,
                    current.pushedAt,
                    "push:" + current.repoId + ":" + current.pushedAt);
            persist(result, event);
        }

        if ((old.archived != current.archived || !safe(old.visibility).equals(safe(current.visibility)))
                && enabled("tracking_notify_status", true)) {
            String status = current.archived ? "Репозиторий архивирован" : "Статус изменён: " + current.visibility;
            TrackingDatabase.Event event = event(
                    TYPE_STATUS,
                    current.developerLogin,
                    current,
                    status + " — " + current.name,
                    current.fullName,
                    Instant.now().toString(),
                    "status:" + current.repoId + ":" + current.archived + ":" + current.visibility);
            persist(result, event);
        }
    }

    private JSONArray loadOwnedRepositories(String login) throws Exception {
        JSONArray all = new JSONArray();
        int maxPages = Math.max(1, Math.min(10, prefs.getInt("tracking_max_pages", 5)));
        for (int page = 1; page <= maxPages; page++) {
            JSONArray chunk = api.getArray("https://api.github.com/users/" + encode(login)
                    + "/repos?type=owner&sort=created&direction=desc&per_page=100&page=" + page);
            for (int i = 0; i < chunk.length(); i++) all.put(chunk.opt(i));
            if (chunk.length() < 100) break;
            if (api.getRemaining() >= 0 && api.getRemaining() < 10) break;
        }
        return all;
    }

    private void loadLatestRelease(TrackingDatabase.RepoSnapshot snapshot) {
        try {
            JSONObject release = api.getJson("https://api.github.com/repos/" + snapshot.fullName + "/releases/latest");
            snapshot.latestReleaseTag = release.optString("tag_name", "");
            snapshot.latestReleasePublishedAt = release.optString("published_at", release.optString("created_at", ""));
        } catch (Exception ignored) {
            // Sentinel distinguishes a known absence of releases from a legacy/unchecked snapshot.
            if (snapshot.latestReleaseTag.isEmpty()) snapshot.latestReleaseTag = "__none__";
        }
    }

    private TrackingDatabase.RepoSnapshot fromJson(String login, JSONObject repo, long seenAt) {
        TrackingDatabase.RepoSnapshot s = new TrackingDatabase.RepoSnapshot();
        s.repoId = repo.optLong("id", 0L);
        s.developerLogin = login;
        s.fullName = repo.optString("full_name", login + "/" + repo.optString("name", "repository"));
        s.name = repo.optString("name", s.fullName);
        s.description = clean(repo.optString("description", ""));
        s.htmlUrl = repo.optString("html_url", "https://github.com/" + s.fullName);
        s.createdAt = repo.optString("created_at", "");
        s.updatedAt = repo.optString("updated_at", "");
        s.pushedAt = repo.optString("pushed_at", "");
        s.stars = repo.optInt("stargazers_count", 0);
        s.forks = repo.optInt("forks_count", 0);
        s.openIssues = repo.optInt("open_issues_count", 0);
        s.archived = repo.optBoolean("archived", false);
        s.visibility = repo.optString("visibility", repo.optBoolean("private", false) ? "private" : "public");
        s.seenAt = seenAt;
        TrackingDatabase.RepoSnapshot old = db.getRepository(s.repoId);
        if (old != null) {
            s.latestReleaseTag = old.latestReleaseTag;
            s.latestReleasePublishedAt = old.latestReleasePublishedAt;
        }
        return s;
    }

    private TrackingDatabase.Event event(String type, String login, TrackingDatabase.RepoSnapshot repo,
                                         String title, String summary, String eventTime, String fingerprint) {
        TrackingDatabase.Event e = new TrackingDatabase.Event();
        e.fingerprint = fingerprint;
        e.developerLogin = login;
        e.repoId = repo.repoId;
        e.eventType = type;
        e.title = title;
        e.summary = summary;
        e.htmlUrl = repo.htmlUrl;
        e.eventTime = eventTime;
        e.createdAt = eventEpoch(eventTime);
        JSONObject payload = new JSONObject();
        try {
            payload.put("full_name", repo.fullName);
            payload.put("stars", repo.stars);
            payload.put("forks", repo.forks);
            payload.put("visibility", repo.visibility);
        } catch (Exception ignored) {}
        e.payload = payload.toString();
        return e;
    }

    private void persist(CheckResult result, TrackingDatabase.Event event) {
        if (db.insertEvent(event)) result.newEvents.add(event);
    }

    private boolean enabled(String key, boolean defaultValue) { return prefs.getBoolean(key, defaultValue); }
    private static long eventEpoch(String iso) {
        if (iso == null || iso.isEmpty()) return System.currentTimeMillis();
        try { return Instant.parse(iso).toEpochMilli(); } catch (Exception ignored) { return System.currentTimeMillis(); }
    }
    private static String encode(String value) { try { return URLEncoder.encode(value, "UTF-8"); } catch (Exception e) { return value; } }
    private static String normalizeLogin(String value) {
        if (value == null) return "";
        String login = value.trim();
        if (login.startsWith("@")) login = login.substring(1);
        if (login.contains("github.com/")) {
            login = login.substring(login.indexOf("github.com/") + "github.com/".length());
            int slash = login.indexOf('/'); if (slash >= 0) login = login.substring(0, slash);
        }
        return login.replaceAll("[^A-Za-z0-9-]", "");
    }
    private static String clean(String value) { return value == null || "null".equals(value) ? "" : value.trim(); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String compactError(Exception e) {
        String message = e == null ? "Неизвестная ошибка" : e.getMessage();
        if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
        return message.length() > 240 ? message.substring(0, 240) + "…" : message;
    }
}
