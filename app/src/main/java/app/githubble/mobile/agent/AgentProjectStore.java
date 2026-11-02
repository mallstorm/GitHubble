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
    private final AgentConversationStore conversations;

    public AgentProjectStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("githubble_ai_workspace_v1", Context.MODE_PRIVATE);
        conversations = new AgentConversationStore(context);
    }

    public static final class Project {
        public final String accountId;
        public final String name;
        public final String repository;
        public final String branch;
        public final String workspaceId;

        public Project(String accountId, String name, String repository, String branch) {
            this(accountId,name,repository,branch,null);
        }
        public Project(String accountId,String name,String repository,String branch,String workspaceId){
            this.workspaceId=workspaceId;
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
        String scoped=key(accountId,"project_"+conversations.activeId(accountId));
        String raw = prefs.getString(scoped, "");
        if(raw.isEmpty()&&prefs.contains(key(accountId,"project"))){
            raw=prefs.getString(key(accountId,"project"),"");
            prefs.edit().putString(scoped,raw).remove(key(accountId,"project")).apply();
        }
        try {
            JSONObject value = new JSONObject(raw);
            if (!accountId.equals(value.optString("account_id"))) return new Project(accountId, "", "", "main", "");
            return new Project(accountId, value.optString("name"), value.optString("repository"),
                    value.optString("branch", "main"),value.optString("workspace_id"));
        } catch (Exception ignored) {
            return new Project(accountId, "", "", "main", "");
        }
    }

    public synchronized Project projectForChat(String accountId,String chatId){
        try{JSONObject value=new JSONObject(prefs.getString(key(accountId,"project_"+chatId),"{}"));
            if(!accountId.equals(value.optString("account_id")))return new Project(accountId,"","","main","");
            return new Project(accountId,value.optString("name"),value.optString("repository"),value.optString("branch","main"),value.optString("workspace_id"));
        }catch(Exception e){return new Project(accountId,"","","main","");}
    }
    public synchronized void save(Project project) {
        if (project == null || project.accountId.isEmpty()) throw new IllegalArgumentException("Проект не выбран");
        try {
            JSONObject value = new JSONObject()
                    .put("account_id", project.accountId)
                    .put("name", project.name)
                    .put("repository", project.repository)
                    .put("branch", project.branch)
                    .put("workspace_id",project.workspaceId==null?project(project.accountId).workspaceId:project.workspaceId);
            prefs.edit().putString(key(project.accountId, "project_"+conversations.activeId(project.accountId)), value.toString()).apply();
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось сохранить проект", error);
        }
    }

    /** Multi-conversation repository (legacy messages are migrated once). */
    public synchronized JSONArray messages(String accountId) { return conversations.messages(accountId); }
    public synchronized JSONArray messages(String accountId, String chatId) { return conversations.messages(accountId, chatId); }
    public synchronized JSONArray listConversations(String accountId) { return conversations.list(accountId); }
    public synchronized JSONArray fullHistory(String accountId,String chatId,int max) {
        return conversations.fullHistory(accountId,chatId,max);
    }
    public synchronized JSONArray searchHistory(String accountId,String phrase,int limit) {
        return conversations.searchHistory(accountId,phrase,limit);
    }
    public synchronized long archivedCount(String accountId){return conversations.archivedCount(accountId);}
    public synchronized JSONObject exportHistory(String accountId)throws org.json.JSONException {
        JSONArray threads=listConversations(accountId);
        JSONArray records=new JSONArray();
        for(int i=0;i<threads.length();i++){
            JSONObject metadata=threads.getJSONObject(i);
            String id=metadata.getString("id");
            records.put(new JSONObject().put("id",id).put("title",metadata.optString("title"))
                .put("messages",fullHistory(accountId,id,10000)));
        }
        return new JSONObject().put("schema_version",1).put("exported_at",System.currentTimeMillis())
            .put("threads",records);
    }
    public synchronized String activeConversation(String accountId) { return conversations.activeId(accountId); }
    public synchronized String newConversation(String accountId) { project(accountId); return conversations.newConversation(accountId); }
    public synchronized void switchConversation(String accountId, String id) { conversations.open(accountId, id); }
    public synchronized void renameConversation(String accountId, String id, String title) { conversations.rename(accountId, id, title); }
    public synchronized void deleteConversation(String accountId, String id) { conversations.delete(accountId, id); }
    public synchronized void clearConversation(String accountId) { conversations.newConversation(accountId); }
    public synchronized void append(String accountId, String role, String body) { conversations.append(accountId, role, body); }
    public synchronized void appendToConversation(String accountId, String id, String role, String body) {
        conversations.append(accountId, id, role, body);
    }
}
