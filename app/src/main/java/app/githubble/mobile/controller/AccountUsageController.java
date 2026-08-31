package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Account diagnostics for GitHub API limits, GitHub App repository access,
 * Actions billing usage and workflow health.
 *
 * Billing endpoints require the GitHub App account permission "Plan: read".
 * Repository visibility is intentionally reported exactly as GitHub returns it:
 * a GitHub App user access token cannot see a private repository unless the app
 * installation itself has access to that repository.
 */
public final class AccountUsageController {
    private static final int MAX_WORKFLOW_REPOSITORIES = 10;

    public static final class Snapshot {
        public final String login;
        public final String plan;
        public final int repositoryCount;
        public final int privateRepositoryCount;
        public final int installationCount;
        public final int apiRemaining;
        public final int apiLimit;
        public final long apiResetEpochSeconds;
        public final double actionsMinutesUsed;
        public final int actionsMinutesIncluded;
        public final int actionsStorageIncludedMb;
        public final String actionsOtherUsage;
        public final boolean billingAvailable;
        public final String billingMessage;
        public final int workflowRepositoriesScanned;
        public final int workflowCount;
        public final int activeWorkflowCount;
        public final int latestRunFailures;
        public final int latestRunsInProgress;
        public final boolean workflowScanTruncated;

        Snapshot(String login, String plan, int repositoryCount, int privateRepositoryCount,
                 int installationCount, int apiRemaining, int apiLimit, long apiResetEpochSeconds,
                 double actionsMinutesUsed, int actionsMinutesIncluded, int actionsStorageIncludedMb,
                 String actionsOtherUsage, boolean billingAvailable, String billingMessage,
                 int workflowRepositoriesScanned, int workflowCount, int activeWorkflowCount,
                 int latestRunFailures, int latestRunsInProgress, boolean workflowScanTruncated) {
            this.login = login;
            this.plan = plan;
            this.repositoryCount = repositoryCount;
            this.privateRepositoryCount = privateRepositoryCount;
            this.installationCount = installationCount;
            this.apiRemaining = apiRemaining;
            this.apiLimit = apiLimit;
            this.apiResetEpochSeconds = apiResetEpochSeconds;
            this.actionsMinutesUsed = actionsMinutesUsed;
            this.actionsMinutesIncluded = actionsMinutesIncluded;
            this.actionsStorageIncludedMb = actionsStorageIncludedMb;
            this.actionsOtherUsage = actionsOtherUsage == null ? "" : actionsOtherUsage;
            this.billingAvailable = billingAvailable;
            this.billingMessage = billingMessage == null ? "" : billingMessage;
            this.workflowRepositoriesScanned = workflowRepositoriesScanned;
            this.workflowCount = workflowCount;
            this.activeWorkflowCount = activeWorkflowCount;
            this.latestRunFailures = latestRunFailures;
            this.latestRunsInProgress = latestRunsInProgress;
            this.workflowScanTruncated = workflowScanTruncated;
        }
    }

    private static final class BillingResult {
        double minutes;
        boolean available;
        String message = "";
        final Map<String, Double> other = new LinkedHashMap<>();
    }

    private final GitHubApiClient api;

    public AccountUsageController(GitHubApiClient api) {
        this.api = api;
    }

    public Snapshot load() throws Exception {
        JSONObject user = api.getJson("https://api.github.com/user");
        String login = user.optString("login", "");
        JSONObject planObject = user.optJSONObject("plan");
        String plan = planObject == null ? "unknown" : planObject.optString("name", "unknown").toLowerCase(Locale.ROOT);

        JSONArray repositories = api.getArray(
                "https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100");
        int privateCount = 0;
        for (int i = 0; i < repositories.length(); i++) {
            JSONObject repo = repositories.optJSONObject(i);
            if (repo != null && repo.optBoolean("private", false)) privateCount++;
        }

        int installations = -1;
        try {
            JSONObject installationResult = api.getJson("https://api.github.com/user/installations?per_page=100");
            installations = installationResult.optInt("total_count", -1);
        } catch (Exception ignored) {
            // Some GitHub App configurations do not expose installation enumeration.
        }

        int apiRemaining = api.getRemaining();
        int apiLimit = -1;
        long apiReset = api.getResetEpochSeconds();
        try {
            JSONObject rate = api.getJson("https://api.github.com/rate_limit");
            JSONObject resources = rate.optJSONObject("resources");
            JSONObject core = resources == null ? null : resources.optJSONObject("core");
            if (core != null) {
                apiRemaining = core.optInt("remaining", apiRemaining);
                apiLimit = core.optInt("limit", -1);
                apiReset = core.optLong("reset", apiReset);
            }
        } catch (Exception ignored) {
            // Header-derived values remain useful if /rate_limit itself fails.
        }

        BillingResult billing = loadActionsBilling(login);
        int includedMinutes = includedActionsMinutes(plan);
        int includedStorageMb = includedActionsStorageMb(plan);

        int scan = Math.min(MAX_WORKFLOW_REPOSITORIES, repositories.length());
        int workflows = 0;
        int active = 0;
        int failed = 0;
        int running = 0;
        for (int i = 0; i < scan; i++) {
            JSONObject repo = repositories.optJSONObject(i);
            if (repo == null) continue;
            String full = repo.optString("full_name", "");
            if (full.isEmpty()) continue;
            try {
                JSONObject workflowObject = api.getJson("https://api.github.com/repos/" + full + "/actions/workflows?per_page=100");
                JSONArray workflowArray = workflowObject.optJSONArray("workflows");
                if (workflowArray != null) {
                    workflows += workflowObject.optInt("total_count", workflowArray.length());
                    for (int w = 0; w < workflowArray.length(); w++) {
                        JSONObject workflow = workflowArray.optJSONObject(w);
                        if (workflow != null && "active".equalsIgnoreCase(workflow.optString("state", ""))) active++;
                    }
                }
                if (workflowObject.optInt("total_count", 0) > 0) {
                    JSONObject runObject = api.getJson("https://api.github.com/repos/" + full + "/actions/runs?per_page=1");
                    JSONArray runs = runObject.optJSONArray("workflow_runs");
                    JSONObject latest = runs == null ? null : runs.optJSONObject(0);
                    if (latest != null) {
                        String status = latest.optString("status", "");
                        String conclusion = latest.optString("conclusion", "");
                        if (!"completed".equalsIgnoreCase(status)) running++;
                        else if ("failure".equalsIgnoreCase(conclusion)
                                || "timed_out".equalsIgnoreCase(conclusion)
                                || "startup_failure".equalsIgnoreCase(conclusion)) failed++;
                    }
                }
            } catch (Exception ignored) {
                // One repository with missing Actions permission must not break the account dashboard.
            }
        }

        return new Snapshot(login, plan, repositories.length(), privateCount, installations,
                apiRemaining, apiLimit, apiReset, billing.minutes, includedMinutes, includedStorageMb,
                formatOtherUsage(billing.other), billing.available, billing.message,
                scan, workflows, active, failed, running, repositories.length() > scan);
    }

    private BillingResult loadActionsBilling(String login) {
        BillingResult result = new BillingResult();
        if (login == null || login.isEmpty()) {
            result.message = "GitHub не вернул login аккаунта";
            return result;
        }
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        int month = now.get(Calendar.MONTH) + 1;
        String encoded = URLEncoder.encode(login, StandardCharsets.UTF_8);
        String base = "https://api.github.com/users/" + encoded + "/settings/billing/usage";
        try {
            JSONObject summary = api.getJson(base + "/summary?year=" + year + "&month=" + month + "&product=actions");
            JSONArray items = summary.optJSONArray("usageItems");
            consumeSummary(items, result);
            result.available = true;
            return result;
        } catch (Exception summaryError) {
            try {
                JSONObject report = api.getJson(base + "?year=" + year + "&month=" + month + "&product=actions");
                JSONArray items = report.optJSONArray("usageItems");
                consumeReport(items, result);
                result.available = true;
                return result;
            } catch (Exception reportError) {
                result.message = shortMessage(reportError);
                return result;
            }
        }
    }

    private static void consumeSummary(JSONArray items, BillingResult result) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !"actions".equalsIgnoreCase(item.optString("product", ""))) continue;
            String unit = item.optString("unitType", "unit");
            double quantity = item.has("grossQuantity") ? item.optDouble("grossQuantity", 0d)
                    : item.optDouble("netQuantity", 0d);
            addUsage(unit, quantity, result);
        }
    }

    private static void consumeReport(JSONArray items, BillingResult result) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !"actions".equalsIgnoreCase(item.optString("product", ""))) continue;
            addUsage(item.optString("unitType", "unit"), item.optDouble("quantity", 0d), result);
        }
    }

    private static void addUsage(String unit, double quantity, BillingResult result) {
        String normalized = unit == null ? "unit" : unit.trim();
        if (normalized.toLowerCase(Locale.ROOT).contains("minute")) result.minutes += quantity;
        else result.other.put(normalized, result.other.getOrDefault(normalized, 0d) + quantity);
    }

    private static int includedActionsMinutes(String plan) {
        if ("pro".equals(plan)) return 3000;
        if ("free".equals(plan)) return 2000;
        return -1;
    }

    private static int includedActionsStorageMb(String plan) {
        if ("pro".equals(plan)) return 1024;
        if ("free".equals(plan)) return 500;
        return -1;
    }

    private static String formatOtherUsage(Map<String, Double> values) {
        if (values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            if (out.length() > 0) out.append(" · ");
            out.append(trimNumber(entry.getValue())).append(' ').append(entry.getKey());
        }
        return out.toString();
    }

    private static String trimNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001d) return String.valueOf((long) Math.rint(value));
        return String.format(Locale.US, "%.2f", value);
    }

    private static String shortMessage(Exception error) {
        String value = error == null || error.getMessage() == null ? "Billing API недоступен" : error.getMessage();
        if (value.length() > 180) value = value.substring(0, 180) + "…";
        return value;
    }
}
