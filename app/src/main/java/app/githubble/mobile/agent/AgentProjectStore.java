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
  private final UnifiedAgentStore unified;
  private final java.io.File taskChangesRoot;
  public final ProjectRegistry registry;
  public final TaskRepository tasks;
  private final java.util.Set<String> migrated = new java.util.HashSet<>();

  public AgentProjectStore(Context context) {
    prefs =
        context
            .getApplicationContext()
            .getSharedPreferences("githubble_ai_workspace_v1", Context.MODE_PRIVATE);
    conversations = new AgentConversationStore(context);
    taskChangesRoot=new java.io.File(context.getFilesDir(),"task-changes");
    unified = new UnifiedAgentStore(context);
    registry = new ProjectRegistry(unified);
    tasks = new TaskRepository(unified);
  }

  private void ensureUnified(String owner) {
    synchronized(UnifiedAgentStore.LOCK) { ensureUnifiedLocked(owner); }
  }

  private void ensureUnifiedLocked(String owner) {
    if (!migrated.contains(owner)) {
      unified.backup(owner, prefs.getAll());
      String active = conversations.activeId(owner);
      String legacy = key(owner, "project");
      if (prefs.contains(legacy) && !prefs.contains(key(owner, "project_" + active))) {
        if (!prefs.edit().putString(key(owner, "project_" + active), prefs.getString(legacy, "")).commit())
          throw new IllegalStateException("Не удалось подготовить миграцию");
      }
      registry.migrate(owner, prefs.getAll());
      AgentRun live=AgentRunRegistry.current(owner);
      tasks.recoverInterrupted(owner,live!=null&&live.active()?live.conversation:"");
      migrated.add(owner);
    }
    String id = conversations.activeId(owner);
    try { tasks.get(owner, id); }
    catch (IllegalArgumentException missing) { tasks.createWithId(owner, id, "", "Задача", ""); }
  }

  public synchronized String projectId(String owner, String thread) {
    ensureUnified(owner);
    try { return tasks.get(owner, thread).optString("projectId"); }
    catch (IllegalArgumentException missing) { return ""; }
  }

  public synchronized String memoryScope(String owner,String thread) {
    String id=projectId(owner,thread);
    return id.isEmpty()?"":registry.get(owner,id).optString("memoryScope",id);
  }

  public synchronized String newTask(String owner, String projectId, String title, String goal) {
    ensureUnified(owner);
    registry.get(owner, projectId);
    String id = tasks.create(owner, projectId, title, goal).optString("taskId");
    try { conversations.createTaskThread(owner, title, id); }
    catch (RuntimeException failed) { tasks.delete(owner,id); throw failed; }
    return id;
  }

  private Project unifiedProject(String owner, String thread) {
    String id = projectId(owner, thread);
    if (id.isEmpty()) return new Project(owner, "", "", "main", "");
    JSONObject p = registry.get(owner, id), t = tasks.get(owner, thread);
    String branch = t.optString("workingBranch");
    return new Project(owner, p.optString("name"), p.optString("repository"),
        branch.isEmpty() ? p.optString("baseBranch", "main") : branch, p.optString("workspaceId"));
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
    ensureUnified(accountId);
    return unifiedProject(accountId, conversations.activeId(accountId));
  }

  public synchronized Project projectForChat(String accountId, String chatId) {
    ensureUnified(accountId);
    return unifiedProject(accountId, chatId);
  }

  public synchronized void save(Project project) {
    if (project == null || project.accountId.isEmpty())
      throw new IllegalArgumentException("Проект не выбран");
    ensureUnified(project.accountId);
    String workspace = project.workspaceId == null ? project(project.accountId).workspaceId : project.workspaceId;
    registry.bindTask(project.accountId, conversations.activeId(project.accountId), project.name,
        project.repository, project.branch, workspace);
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
    ensureUnified(accountId);
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
    ensureUnified(accountId);
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
    ensureUnified(accountId);
    registry.detachWorkspace(accountId, workspaceId);
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
    ensureUnified(accountId);
    JSONObject task=tasks.get(accountId,id);
    conversations.openTaskThread(accountId,id,task.optString("title","Задача"));
  }

  public synchronized void renameConversation(String accountId, String id, String title) {
    ensureUnified(accountId);
    conversations.rename(accountId, id, title);
    tasks.update(accountId, id, task -> { try { task.put("title", title); }
      catch (org.json.JSONException bad) { throw new IllegalStateException(bad); } });
  }

  public synchronized void deleteConversation(String accountId, String id) {
    ensureUnified(accountId);
    String project=projectId(accountId,id);
    conversations.delete(accountId, id);
    if(!project.isEmpty())try{new ChangeStore(taskChangesRoot,accountId,project,id).deleteTaskData();}
      catch(Exception error){throw new IllegalStateException("Не удалось удалить изменения задачи",error);}
    tasks.delete(accountId, id);
    prefs.edit().remove(key(accountId, "project_" + id)).commit();
  }

  public synchronized void clearConversation(String accountId) {
    newConversation(accountId);
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
