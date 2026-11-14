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
  private final SharedPreferences prefs;
  private final AgentConversationStore conversations;

  public AgentProjectStore(Context context) {
    prefs =
        context
            .getApplicationContext()
            .getSharedPreferences("githubble_ai_workspace_v1", Context.MODE_PRIVATE);
    conversations = new AgentConversationStore(context);
  }

  public static final class Project {
    public final String accountId;
    public final String name;
    public final String repository;
    public final String branch;
    public final String workspaceId;

    public Project(String accountId, String name, String repository, String branch) {
      this(accountId, name, repository, branch, null);
    }

    public Project(
        String accountId, String name, String repository, String branch, String workspaceId) {
      this.workspaceId = workspaceId;
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

  private static String safe(String value) {
    return value == null ? "" : value.trim();
  }

  private String key(String accountId, String suffix) {
    if (safe(accountId).isEmpty()) throw new IllegalArgumentException("Не выбран GitHub-аккаунт");
    String id =
        Base64.encodeToString(
            accountId.getBytes(StandardCharsets.UTF_8),
            Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    return "account_" + id + "_" + suffix;
  }

  public synchronized Project project(String accountId) {
    String scoped = key(accountId, "project_" + conversations.activeId(accountId));
    String raw = prefs.getString(scoped, "");
    if (raw.isEmpty() && prefs.contains(key(accountId, "project"))) {
      raw = prefs.getString(key(accountId, "project"), "");
      prefs.edit().putString(scoped, raw).remove(key(accountId, "project")).apply();
    }
    try {
      JSONObject value = new JSONObject(raw);
      if (!accountId.equals(value.optString("account_id")))
        return new Project(accountId, "", "", "main", "");
      return new Project(
          accountId,
          value.optString("name"),
          value.optString("repository"),
          value.optString("branch", "main"),
          value.optString("workspace_id"));
    } catch (Exception ignored) {
      return new Project(accountId, "", "", "main", "");
    }
  }

  public synchronized Project projectForChat(String accountId, String chatId) {
    try {
      JSONObject value = new JSONObject(prefs.getString(key(accountId, "project_" + chatId), "{}"));
      if (!accountId.equals(value.optString("account_id")))
        return new Project(accountId, "", "", "main", "");
      return new Project(
          accountId,
          value.optString("name"),
          value.optString("repository"),
          value.optString("branch", "main"),
          value.optString("workspace_id"));
    } catch (Exception e) {
      return new Project(accountId, "", "", "main", "");
    }
  }

  public synchronized void save(Project project) {
    if (project == null || project.accountId.isEmpty())
      throw new IllegalArgumentException("Проект не выбран");
    try {
      JSONObject value =
          new JSONObject()
              .put("account_id", project.accountId)
              .put("name", project.name)
              .put("repository", project.repository)
              .put("branch", project.branch)
              .put(
                  "workspace_id",
                  project.workspaceId == null
                      ? project(project.accountId).workspaceId
                      : project.workspaceId);
      prefs
          .edit()
          .putString(
              key(project.accountId, "project_" + conversations.activeId(project.accountId)),
              value.toString())
          .apply();
    } catch (Exception error) {
      throw new IllegalStateException("Не удалось сохранить проект", error);
    }
  }

  /** Multi-conversation repository (legacy messages are migrated once). */
  public synchronized JSONArray messages(String accountId) {
    return conversations.messages(accountId);
  }

  public synchronized JSONArray messages(String accountId, String chatId) {
    return conversations.messages(accountId, chatId);
  }

  public synchronized JSONArray listConversations(String accountId) {
    return conversations.list(accountId);
  }

  public synchronized JSONArray fullHistory(String accountId, String chatId, int max) {
    return conversations.fullHistory(accountId, chatId, max);
  }

  public synchronized JSONArray searchHistory(String accountId, String phrase, int limit) {
    return conversations.searchHistory(accountId, phrase, limit);
  }

  public synchronized long archivedCount(String accountId) {
    return conversations.archivedCount(accountId);
  }

  public synchronized JSONObject exportHistory(String accountId) throws org.json.JSONException {
    JSONArray threads = listConversations(accountId);
    JSONArray records = new JSONArray();
    for (int i = 0; i < threads.length(); i++) {
      JSONObject metadata = threads.getJSONObject(i);
      String id = metadata.getString("id");
      records.put(
          new JSONObject()
              .put("id", id)
              .put("title", metadata.optString("title"))
              .put("messages", fullHistory(accountId, id, 10000)));
    }
    return new JSONObject()
        .put("schema_version", 1)
        .put("exported_at", System.currentTimeMillis())
        .put("threads", records);
  }

  public synchronized String activeConversation(String accountId) {
    return conversations.activeId(accountId);
  }

  /** A new chat must never inherit the Workspace of an empty draft or legacy project. */
  public synchronized String newConversation(String accountId) {
    project(accountId); // Perform legacy migration before switching threads.
    String id = conversations.newConversation(accountId);
    save(new Project(accountId, "", "", "main", ""));
    return id;
  }

  /**
   * Remove an unavailable local Workspace from every conversation in this account,
   * including hidden drafts and the legacy project record. Preserve GitHub links
   * and conversation history: deleting phone files must never delete a repository.
   */
  public synchronized int detachWorkspaceEverywhere(String accountId, String workspaceId) {
    if (workspaceId == null || !workspaceId.matches("[a-f0-9-]{36}"))
      throw new IllegalArgumentException("Некорректный Workspace ID");
    String scoped = key(accountId, "project_");
    String legacy = key(accountId, "project");
    SharedPreferences.Editor edit = prefs.edit();
    int changed = 0;
    for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
      String entryKey = entry.getKey();
      if (!entryKey.startsWith(scoped) && !entryKey.equals(legacy)) continue;
      if (!(entry.getValue() instanceof String)) continue;
      try {
        JSONObject project = new JSONObject((String) entry.getValue());
        if (!accountId.equals(project.optString("account_id"))
            || !workspaceId.equals(project.optString("workspace_id"))) continue;
        String repository = project.optString("repository", "");
        project.put("workspace_id", "");
        project.put("name", repository.isEmpty() ? ""
            : repository.substring(repository.lastIndexOf('/') + 1));
        edit.putString(entryKey, project.toString());
        changed++;
      } catch (org.json.JSONException ignored) {
        // A corrupt foreign/legacy entry must not change another project.
      }
    }
    if (!edit.commit()) throw new IllegalStateException("Не удалось отвязать Workspace");
    return changed;
  }

  public synchronized void switchConversation(String accountId, String id) {
    conversations.open(accountId, id);
  }

  public synchronized void renameConversation(String accountId, String id, String title) {
    conversations.rename(accountId, id, title);
  }

  public synchronized void deleteConversation(String accountId, String id) {
    conversations.delete(accountId, id);
  }

  public synchronized void clearConversation(String accountId) {
    conversations.newConversation(accountId);
  }

  public synchronized void append(String accountId, String role, String body) {
    conversations.append(accountId, role, body);
  }

  public synchronized void appendToConversation(
      String accountId, String id, String role, String body) {
    conversations.append(accountId, id, role, body);
  }

  public synchronized void appendOperations(
      String account, String thread, String body, JSONArray operations) {
    conversations.appendEvent(account, thread, "system", body, operations);
  }

  public synchronized void upsertRun(String account, String thread, JSONObject run) {
    conversations.upsertRun(account, thread, run);
  }

  public synchronized void appendAttachments(
      String owner, String thread, String role, String body, JSONArray attachments) {
    conversations.appendRich(owner, thread, role, body, null, attachments);
  }
}
