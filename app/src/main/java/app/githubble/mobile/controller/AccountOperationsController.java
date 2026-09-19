package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Account-level GitHub operations: billing usage, Actions storage cleanup and
 * guarded branch-head rollback. Sensitive payment data is never collected by
 * the app; payment management is delegated to GitHub's official billing page.
 */
public final class AccountOperationsController {
    public static final String BILLING_PAGE = "https://github.com/settings/billing";
    public static final String INSTALLATIONS_PAGE = "https://github.com/settings/installations";
    private static final int MAX_REPOSITORIES_TO_SCAN = 100;
    private static final int MAX_PAGES_PER_REPOSITORY = 20;

    public static final class RepositoryUsage {
        public final String fullName;
        public final boolean isPrivate;
        public final long artifactBytes;
        public final long cacheBytes;
        public final int artifacts;
        public final int caches;
        public final boolean actionsAccessible;

        RepositoryUsage(String fullName, boolean isPrivate, long artifactBytes, long cacheBytes,
                        int artifacts, int caches, boolean actionsAccessible) {
            this.fullName = fullName;
            this.isPrivate = isPrivate;
            this.artifactBytes = artifactBytes;
            this.cacheBytes = cacheBytes;
            this.artifacts = artifacts;
            this.caches = caches;
            this.actionsAccessible = actionsAccessible;
        }

        public long totalBytes() { return artifactBytes + cacheBytes; }
    }

    public static final class BillingSnapshot {
        public final String login;
        public final String planName;
        public final long minutesUsed;
        public final long minutesLimit;
        public final long actionsStorageBytes;
        public final long storageLimitBytes;
        public final int privateRepositories;
        public final int repositoriesScanned;
        public final boolean repositoryScanTruncated;
        public final boolean billingApiAvailable;
        public final String billingWarning;
        public final List<RepositoryUsage> repositories;

        BillingSnapshot(String login, String planName, long minutesUsed, long minutesLimit,
                        long actionsStorageBytes, long storageLimitBytes, int privateRepositories,
                        int repositoriesScanned, boolean repositoryScanTruncated,
                        boolean billingApiAvailable, String billingWarning,
                        List<RepositoryUsage> repositories) {
            this.login = login;
            this.planName = planName;
            this.minutesUsed = minutesUsed;
            this.minutesLimit = minutesLimit;
            this.actionsStorageBytes = actionsStorageBytes;
            this.storageLimitBytes = storageLimitBytes;
            this.privateRepositories = privateRepositories;
            this.repositoriesScanned = repositoriesScanned;
            this.repositoryScanTruncated = repositoryScanTruncated;
            this.billingApiAvailable = billingApiAvailable;
            this.billingWarning = billingWarning == null ? "" : billingWarning;
            this.repositories = Collections.unmodifiableList(repositories);
        }

        public long minutesRemaining() {
            return minutesLimit < 0 || minutesUsed < 0 ? -1 : Math.max(0, minutesLimit - minutesUsed);
        }

        public long storageRemainingBytes() {
            return storageLimitBytes < 0 ? -1 : Math.max(0, storageLimitBytes - actionsStorageBytes);
        }
    }

    public static final class CleanupItem {
        public static final String ARTIFACT = "artifact";
        public static final String CACHE = "cache";
        public final String kind;
        public final String repository;
        public final long id;
        public final String name;
        public final long sizeBytes;
        public final String createdAt;
        public final String extra;

        CleanupItem(String kind, String repository, long id, String name, long sizeBytes,
                    String createdAt, String extra) {
            this.kind = kind;
            this.repository = repository;
            this.id = id;
            this.name = name == null ? "" : name;
            this.sizeBytes = sizeBytes;
            this.createdAt = createdAt == null ? "" : createdAt;
            this.extra = extra == null ? "" : extra;
        }
    }

    public static final class DeleteResult {
        public final int deleted;
        public final int failed;
        public final long bytesFreed;
        DeleteResult(int deleted, int failed, long bytesFreed) {
            this.deleted = deleted; this.failed = failed; this.bytesFreed = bytesFreed;
        }
    }

    public static final class BranchHead {
        public final String branch;
        public final String sha;
        public final String parentSha;
        public final String message;
        public final String author;
        public final String date;

        BranchHead(String branch, String sha, String parentSha, String message, String author, String date) {
            this.branch = branch; this.sha = sha; this.parentSha = parentSha;
            this.message = message; this.author = author; this.date = date;
        }
    }

    private final GitHubApiClient api;

    public AccountOperationsController(GitHubApiClient api) {
        this.api = api;
    }

    public BillingSnapshot loadBillingSnapshot() throws Exception {
        JSONObject user = api.getJson("https://api.github.com/user");
        String login = user.optString("login", "");
        JSONObject plan = user.optJSONObject("plan");
        String planName = plan == null ? "" : plan.optString("name", "");
        long minutesLimit = includedMinutes(planName);
        long storageLimit = includedStorage(planName);

        long minutesUsed = -1;
        boolean billingAvailable = false;
        String billingWarning = "";
        if (!login.isEmpty()) {
            LocalDate now = LocalDate.now();
            String url = "https://api.github.com/users/" + enc(login)
                    + "/settings/billing/usage/summary?year=" + now.getYear()
                    + "&month=" + now.getMonthValue() + "&product=Actions";
            try {
                JSONObject billing = api.getJson(url);
                JSONArray items = billing.optJSONArray("usageItems");
                double minuteSum = 0d;
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) continue;
                        String unit = item.optString("unitType", "").toLowerCase(Locale.ROOT);
                        String product = item.optString("product", "");
                        if ("actions".equalsIgnoreCase(product) && unit.contains("minute")) {
                            minuteSum += item.has("grossQuantity")
                                    ? item.optDouble("grossQuantity", 0d)
                                    : item.optDouble("quantity", 0d);
                        }
                    }
                }
                minutesUsed = Math.round(minuteSum);
                billingAvailable = true;
            } catch (GitHubApiClient.ApiException e) {
                if (e.statusCode == 403 || e.statusCode == 404) {
                    billingWarning = "Billing API недоступен текущему токену. Для GiHubble включите Account permission: Plan — Read.";
                } else {
                    billingWarning = e.getMessage();
                }
            }
        }

        JSONArray owned = loadOwnedRepositories();
        List<RepositoryUsage> usages = new ArrayList<>();
        long storage = 0L;
        int privateCount = 0;
        int scanCount = Math.min(owned.length(), MAX_REPOSITORIES_TO_SCAN);
        for (int i = 0; i < scanCount; i++) {
            JSONObject repo = owned.optJSONObject(i);
            if (repo == null) continue;
            String full = repo.optString("full_name", "");
            boolean isPrivate = repo.optBoolean("private", false);
            if (isPrivate) privateCount++;
            if (full.isEmpty()) continue;
            RepositoryUsage usage = scanRepositoryUsage(full, isPrivate);
            usages.add(usage);
            storage += usage.totalBytes();
        }
        usages.sort(Comparator.comparingLong(RepositoryUsage::totalBytes).reversed());

        return new BillingSnapshot(login, planName, minutesUsed, minutesLimit, storage, storageLimit,
                privateCount, scanCount, owned.length() > scanCount, billingAvailable, billingWarning, usages);
    }

    public List<CleanupItem> loadCleanupItems(String repository) throws Exception {
        List<CleanupItem> items = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES_PER_REPOSITORY; page++) {
            JSONObject response = api.getJson("https://api.github.com/repos/" + repository
                    + "/actions/artifacts?per_page=100&page=" + page);
            JSONArray list = response.optJSONArray("artifacts");
            if (list == null || list.length() == 0) break;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                JSONObject run = item.optJSONObject("workflow_run");
                String extra = run == null ? "" : run.optString("head_branch", "");
                items.add(new CleanupItem(CleanupItem.ARTIFACT, repository, item.optLong("id"),
                        item.optString("name", "Artifact"), item.optLong("size_in_bytes"),
                        item.optString("created_at", ""), extra));
            }
            if (list.length() < 100) break;
        }
        for (int page = 1; page <= MAX_PAGES_PER_REPOSITORY; page++) {
            JSONObject response = api.getJson("https://api.github.com/repos/" + repository
                    + "/actions/caches?per_page=100&page=" + page + "&sort=size_in_bytes&direction=desc");
            JSONArray list = response.optJSONArray("actions_caches");
            if (list == null || list.length() == 0) break;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                items.add(new CleanupItem(CleanupItem.CACHE, repository, item.optLong("id"),
                        item.optString("key", "Actions cache"), item.optLong("size_in_bytes"),
                        item.optString("created_at", ""), item.optString("ref", "")));
            }
            if (list.length() < 100) break;
        }
        items.sort(Comparator.comparingLong((CleanupItem x) -> x.sizeBytes).reversed());
        return items;
    }

    public DeleteResult deleteCleanupItems(List<CleanupItem> items) {
        int deleted = 0;
        int failed = 0;
        long bytes = 0L;
        if (items == null) return new DeleteResult(0, 0, 0);
        for (CleanupItem item : items) {
            try {
                String endpoint = CleanupItem.CACHE.equals(item.kind)
                        ? "https://api.github.com/repos/" + item.repository + "/actions/caches/" + item.id
                        : "https://api.github.com/repos/" + item.repository + "/actions/artifacts/" + item.id;
                api.delete(endpoint);
                deleted++;
                bytes += Math.max(0L, item.sizeBytes);
            } catch (Exception ignored) {
                failed++;
            }
        }
        if (!items.isEmpty()) {
            api.invalidatePrefix("https://api.github.com/repos/" + items.get(0).repository + "/actions/");
        }
        return new DeleteResult(deleted, failed, bytes);
    }

    public BranchHead loadBranchHead(String repository, String branch) throws Exception {
        JSONObject branchJson = api.getJson("https://api.github.com/repos/" + repository + "/branches/" + enc(branch));
        JSONObject commitRef = branchJson.optJSONObject("commit");
        String sha = commitRef == null ? "" : commitRef.optString("sha", "");
        if (sha.isEmpty()) throw new IllegalStateException("GitHub не вернул HEAD ветки");
        JSONObject commit = api.getJson("https://api.github.com/repos/" + repository + "/git/commits/" + sha);
        JSONArray parents = commit.optJSONArray("parents");
        String parentSha = parents != null && parents.length() > 0
                ? parents.optJSONObject(0).optString("sha", "") : "";
        JSONObject author = commit.optJSONObject("author");
        return new BranchHead(branch, sha, parentSha, commit.optString("message", "Коммит"),
                author == null ? "" : author.optString("name", ""),
                author == null ? "" : author.optString("date", ""));
    }

    public void resetBranchToParent(String repository, BranchHead head) throws Exception {
        if (head == null || head.parentSha == null || head.parentSha.isEmpty()) {
            throw new IllegalStateException("У HEAD-коммита нет родителя — откат невозможен");
        }
        JSONObject body = new JSONObject();
        body.put("sha", head.parentSha);
        body.put("force", true);
        api.patchJson("https://api.github.com/repos/" + repository + "/git/refs/heads/" + enc(head.branch), body);
        api.invalidatePrefix("https://api.github.com/repos/" + repository);
    }

    private JSONArray loadOwnedRepositories() throws Exception {
        JSONArray result = new JSONArray();
        for (int page = 1; page <= 10 && result.length() < MAX_REPOSITORIES_TO_SCAN + 1; page++) {
            JSONArray batch = api.getArray("https://api.github.com/user/repos?visibility=all&affiliation=owner&sort=updated&per_page=100&page=" + page);
            for (int i = 0; i < batch.length(); i++) result.put(batch.opt(i));
            if (batch.length() < 100) break;
        }
        return result;
    }

    private RepositoryUsage scanRepositoryUsage(String repository, boolean isPrivate) {
        long artifactsBytes = 0L;
        long cacheBytes = 0L;
        int artifactCount = 0;
        int cacheCount = 0;
        boolean accessible = true;
        try {
            for (int page = 1; page <= MAX_PAGES_PER_REPOSITORY; page++) {
                JSONObject response = api.getJson("https://api.github.com/repos/" + repository
                        + "/actions/artifacts?per_page=100&page=" + page);
                JSONArray list = response.optJSONArray("artifacts");
                if (list == null || list.length() == 0) break;
                artifactCount += list.length();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item != null) artifactsBytes += Math.max(0L, item.optLong("size_in_bytes"));
                }
                if (list.length() < 100) break;
            }
            for (int page = 1; page <= MAX_PAGES_PER_REPOSITORY; page++) {
                JSONObject response = api.getJson("https://api.github.com/repos/" + repository
                        + "/actions/caches?per_page=100&page=" + page);
                JSONArray list = response.optJSONArray("actions_caches");
                if (list == null || list.length() == 0) break;
                cacheCount += list.length();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item != null) cacheBytes += Math.max(0L, item.optLong("size_in_bytes"));
                }
                if (list.length() < 100) break;
            }
        } catch (GitHubApiClient.ApiException e) {
            if (e.statusCode == 403 || e.statusCode == 404) accessible = false;
        } catch (Exception ignored) {
            accessible = false;
        }
        return new RepositoryUsage(repository, isPrivate, artifactsBytes, cacheBytes,
                artifactCount, cacheCount, accessible);
    }

    private static long includedMinutes(String plan) {
        if (plan == null) return -1;
        if ("free".equalsIgnoreCase(plan)) return 2000L;
        if ("pro".equalsIgnoreCase(plan)) return 3000L;
        return -1L;
    }

    private static long includedStorage(String plan) {
        if (plan == null) return -1;
        if ("free".equalsIgnoreCase(plan)) return 500L * 1024L * 1024L;
        if ("pro".equalsIgnoreCase(plan)) return 1024L * 1024L * 1024L;
        return -1L;
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }
}
