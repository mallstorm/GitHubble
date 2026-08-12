package app.githubble.mobile.controller;

import org.json.JSONArray;
import org.json.JSONObject;

/** Non-destructive capability matrix for Copilot operations in one repository. */
public final class CopilotPermissionReport {
    public static final class Check {
        public final String id;
        public final String title;
        public final boolean passed;
        public final boolean required;
        public final String detail;

        public Check(String id, String title, boolean passed, boolean required, String detail) {
            this.id = id;
            this.title = title;
            this.passed = passed;
            this.required = required;
            this.detail = detail == null ? "" : detail;
        }

        public JSONObject toJson() {
            try {
                return new JSONObject()
                        .put("id", id)
                        .put("title", title)
                        .put("passed", passed)
                        .put("required", required)
                        .put("detail", detail);
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private final JSONArray checks = new JSONArray();

    public void add(Check check) { if (check != null) checks.put(check.toJson()); }
    public JSONArray checks() { return checks; }

    public boolean canReadTasks() { return passed("agent_tasks_read"); }
    public boolean canReadRepository() { return passed("metadata_read"); }
    public boolean canReadContents() { return passed("contents_read"); }
    public boolean canReadPullRequests() { return passed("pulls_read"); }
    public boolean canReadIssues() { return passed("issues_read"); }
    public boolean canReadActions() { return passed("actions_read"); }
    public boolean copilotAssignable() { return passed("copilot_assignable"); }

    /** Start/assignment write access cannot be proven without performing a mutation. */
    public boolean readyForSafeAttempt() {
        return canReadRepository() && canReadTasks() && canReadContents();
    }

    public int passedCount() {
        int count = 0;
        for (int i = 0; i < checks.length(); i++) if (checks.optJSONObject(i).optBoolean("passed")) count++;
        return count;
    }

    public int totalCount() { return checks.length(); }

    private boolean passed(String id) {
        for (int i = 0; i < checks.length(); i++) {
            JSONObject item = checks.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item.optBoolean("passed");
        }
        return false;
    }
}
