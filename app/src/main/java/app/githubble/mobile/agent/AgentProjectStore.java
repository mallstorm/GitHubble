package app.githubble.mobile.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Phase-one account-scoped project/chat ledger. Never stores credentials or provider tokens.
 * Follow-up: migrate larger chat transcripts to Room with versioned schema and retention.
 */
public final class AgentProjectStore {
    private static final int MAX_MESSAGES = 80;
    private static final int MAX_CONTENT_CHARS = 6000;
    private final SharedPreferences prefs;

    public AgentProjectStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("githubble_ai_workspace_v1", Context.MODE_PRIVATE);
    }

    public static final class Project {
        public final String accountId;
        public final String name;
        public final String repository;
        public final String branch;

        public Project(String accountId, String name, String repository, String branch) {
            this.accountId = safe(accountId);
            this.name = safe(name);
            this.repository = safe(repository);
            this.branch = safe(branch).isEmpty() ? "main" : branch;
        }
    }

    public static final class Message {
        public final String role;
        public final String body;
        public final long timestamp;
        public Message(String role, String body, long timestamp) {
            this.role = role;
            this.body = body;
            this.timestamp = timestamp;
        }
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private String key(String accountId, String suffix) {
        if (safe(accountId).isEmpty()) throw new IllegalArgumentException("Не выбран GitHub-аккаунт");
        String id = Base64.encodeToString(accountId.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        return "account_" + id + "_" + suffix;
    }

    public synchronized Project project(String accountId) {
        String raw = prefs.getString(key(accountId, "project"), "");
        try {
            JSONObject value = new JSONObject(raw);
            if (!accountId.equals(value.optString("account_id"))) return new Project(accountId, "", "", "main");
            return new Project(accountId, value.optString("name"), value.optString("repository"),
                    value.optString("branch", "main"));
        } catch (Exception ignored) {
            return new Project(accountId, "", "", "main");
        }
    }

    public synchronized void save(Project project) {
        if (project == null || project.accountId.isEmpty()) throw new IllegalArgumentException("Проект не выбран");
        try {
            JSONObject value = new JSONObject()
                    .put("account_id", project.accountId)
                    .put("name", project.name)
                    .put("repository", project.repository)
                    .put("branch", project.branch);
            prefs.edit().putString(key(project.accountId, "project"), value.toString()).apply();
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось сохранить проект", error);
        }
    }

    public synchronized JSONArray messages(String accountId) {
        try {
            JSONArray result = new JSONArray(prefs.getString(key(accountId, "messages"), "[]"));
            return result;
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    public synchronized void clearConversation(String accountId) {
        prefs.edit().remove(key(accountId, "messages")).apply();
    }

    public synchronized void append(String accountId, String role, String body) {
        String text = safe(body);
        if (text.isEmpty()) return;
        if (!"user".equals(role) && !"agent".equals(role) && !"system".equals(role))
            throw new IllegalArgumentException("Недопустимая роль сообщения");
        try {
            JSONArray prior = messages(accountId);
            JSONArray next = new JSONArray();
            int start = Math.max(0, prior.length() - MAX_MESSAGES + 1);
            for (int index = start; index < prior.length(); index++) {
                JSONObject entry = prior.optJSONObject(index);
                if (entry != null) next.put(entry);
            }
            next.put(new JSONObject().put("role", role)
                    .put("body", text.substring(0, Math.min(MAX_CONTENT_CHARS, text.length())))
                    .put("at", System.currentTimeMillis()));
            prefs.edit().putString(key(accountId, "messages"), next.toString()).apply();
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось сохранить сообщение", error);
        }
    }
}
