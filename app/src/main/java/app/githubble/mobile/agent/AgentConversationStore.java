package app.githubble.mobile.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native OmniChat-style conversation repository, account isolated. Phase 1 chat is migrated
 * once into the first thread. All later messages are written to their pinned conversation ID.
 * Credentials and third-party tokens must never enter this store.
 */
public final class AgentConversationStore {
    private static final int MAX_THREADS = 60;
    private static final int MAX_MESSAGES = 80;
    private static final int MAX_BODY = 6000;
    private final SharedPreferences prefs;
    private final AgentConversationArchive archive;

    public AgentConversationStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(
                "githubble_ai_workspace_v1", Context.MODE_PRIVATE);
        archive = new AgentConversationArchive(context);
    }

    private static String account(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Нет GitHub-аккаунта");
        return "account_" + Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING) + "_";
    }

    private static String safeId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{8,64}"))
            throw new IllegalArgumentException("Некорректный ID диалога");
        return id;
    }

    private String key(String owner, String suffix) { return account(owner) + suffix; }
    private String transcriptKey(String owner, String id) { return key(owner, "thread_" + safeId(id)); }

    private JSONArray readIndex(String owner) {
        try { return new JSONArray(prefs.getString(key(owner, "thread_index"), "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static String freshId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Materializes an empty first thread, migrating legacy conversation without dropping text. */
    private synchronized void ensure(String owner) {
        String active = prefs.getString(key(owner, "active_thread"), "");
        JSONArray known = readIndex(owner);
        if (!active.isEmpty() && (contains(known, active) || prefs.getBoolean(key(owner,"pending_"+active),false))) return;
        if (known.length() > 0) {
            JSONObject newest = known.optJSONObject(0);
            if (newest != null) {
                prefs.edit().putString(key(owner, "active_thread"), newest.optString("id")).commit();
                return;
            }
        }
        String id = freshId();
        long now = System.currentTimeMillis();
        JSONArray index = new JSONArray();
        String legacy = prefs.getString(key(owner, "messages"), "[]");
        boolean hasLegacy=false;
        try{hasLegacy=new JSONArray(legacy).length()>0;}catch(Exception ignored){}
        if(hasLegacy)index.put(new JSONObjectBuilder(id,"Новый диалог",now).toJson());
        prefs.edit().putString(key(owner, "thread_index"), index.toString())
                .putString(key(owner, "active_thread"), id)
                .putString(transcriptKey(owner, id), legacy)
                .putBoolean(key(owner,"pending_"+id),!hasLegacy)
                .putString(key(owner,"draft_thread"),hasLegacy?"":id)
                .remove(key(owner, "messages")).commit();
    }

    private static boolean contains(JSONArray arr, String id) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return true;
        }
        return false;
    }

    private static final class JSONObjectBuilder {
        final String id, title; final long at;
        JSONObjectBuilder(String id, String title, long at) { this.id=id; this.title=title; this.at=at; }
        JSONObject toJson() {
            try { return new JSONObject().put("id",id).put("title",title).put("updated",at); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
    }

    public synchronized String activeId(String owner) {
        ensure(owner);
        return prefs.getString(key(owner, "active_thread"), "");
    }

    public synchronized JSONArray list(String owner) {
        ensure(owner);
        JSONArray visible=new JSONArray(),index=readIndex(owner);
        for(int i=0;i<index.length();i++){
            JSONObject entry=index.optJSONObject(i);if(entry==null)continue;
            // Hide historical auto-created placeholders without deleting their stored data.
            if("Новый диалог".equals(entry.optString("title")) && messages(owner,entry.optString("id")).length()==0)continue;
            visible.put(entry);
        }
        return visible;
    }

    public synchronized String newConversation(String owner) {
        ensure(owner);
        String current=activeId(owner);
        if(messages(owner,current).length()==0)return current;
        String draft=prefs.getString(key(owner,"draft_thread"),"");
        if(!draft.isEmpty()&&prefs.getBoolean(key(owner,"pending_"+draft),false)){
            prefs.edit().putString(key(owner,"active_thread"),draft).commit();return draft;
        }
        if(list(owner).length()>=MAX_THREADS)
            throw new IllegalStateException("Достигнут лимит диалогов ("+MAX_THREADS+")");
        String id=freshId();
        prefs.edit().putString(key(owner,"active_thread"),id)
                .putBoolean(key(owner,"pending_"+id),true)
                .putString(key(owner,"draft_thread"),id)
                .putString(transcriptKey(owner,id),"[]").commit();
        return id;
    }

    public synchronized void open(String owner, String id) {
        ensure(owner);
        safeId(id);
        if (!contains(readIndex(owner), id)) throw new IllegalArgumentException("Диалог не найден");
        prefs.edit().putString(key(owner, "active_thread"), id).commit();
    }

    public synchronized void rename(String owner, String id, String title) {
        ensure(owner);
        safeId(id);
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Введите название диалога");
        if (value.length() > 96) value = value.substring(0, 96);
        JSONArray existing = readIndex(owner), next = new JSONArray();
        boolean found = false;
        for (int i=0;i<existing.length();i++) {
            JSONObject entry = existing.optJSONObject(i);
            if (entry == null) continue;
            if (id.equals(entry.optString("id"))) {
                try { entry.put("title", value); } catch (Exception error) { throw new IllegalStateException(error); }
                found = true;
            }
            next.put(entry);
        }
        if (!found) throw new IllegalArgumentException("Диалог не найден");
        prefs.edit().putString(key(owner, "thread_index"), next.toString()).commit();
    }

    public synchronized void delete(String owner, String id) {
        ensure(owner);
        safeId(id);
        JSONArray existing = readIndex(owner), next = new JSONArray();
        for (int i=0;i<existing.length();i++) {
            JSONObject entry=existing.optJSONObject(i);
            if (entry != null && !id.equals(entry.optString("id"))) next.put(entry);
        }
        String selected=prefs.getString(key(owner,"active_thread"),"");
        SharedPreferences.Editor edit=prefs.edit();
        if(next.length()==0){
            String draft=prefs.getString(key(owner,"draft_thread"),"");
            if(!selected.equals(id)&&prefs.getBoolean(key(owner,"pending_"+selected),false)){
                // Keep the active unsent draft when deleting the last saved conversation.
            }else if(!draft.isEmpty()&&!draft.equals(id)&&prefs.getBoolean(key(owner,"pending_"+draft),false))selected=draft;
            else selected=freshId();
            edit.putBoolean(key(owner,"pending_"+selected),true)
                .putString(key(owner,"draft_thread"),selected).putString(transcriptKey(owner,selected),"[]");
        }else if(selected.equals(id))selected=next.optJSONObject(0).optString("id");
        edit.putString(key(owner,"thread_index"),next.toString()).putString(key(owner,"active_thread"),selected)
                .remove(transcriptKey(owner,id)).remove(key(owner,"pending_"+id)).commit();
        archive.delete(owner,id);
    }

    public synchronized JSONArray messages(String owner) { return messages(owner, activeId(owner)); }

    public synchronized JSONArray messages(String owner, String id) {
        ensure(owner);
        safeId(id);
        if (!contains(readIndex(owner),id) && !prefs.getBoolean(key(owner,"pending_"+id),false)) return new JSONArray();
        try { return new JSONArray(prefs.getString(transcriptKey(owner,id), "[]")); }
        catch (Exception error) { return new JSONArray(); }
    }

    /** Full local history includes archived older messages and the active fast transcript. */
    public synchronized JSONArray fullHistory(String owner,String id,int max) {
        ensure(owner);safeId(id);
        int limit=Math.max(1,Math.min(10000,max));
        JSONArray result=new JSONArray();
        try{
            JSONArray older=archive.read(owner,id,limit);
            JSONArray recent=messages(owner,id);
            int start=Math.max(0,older.length()+recent.length()-limit);
            for(int i=start;i<older.length();i++)result.put(older.opt(i));
            for(int i=Math.max(0,start-older.length());i<recent.length();i++)result.put(recent.opt(i));
            return result;
        }catch(Exception e){throw new IllegalStateException("Не удалось прочитать историю",e);}
    }

    public synchronized JSONArray searchHistory(String owner,String query,int limit){
        ensure(owner);
        JSONArray result=new JSONArray();
        if(query==null||query.trim().length()<2)return result;
        try{
            JSONArray archived=archive.search(owner,query,limit);
            for(int i=0;i<archived.length();i++)result.put(archived.getJSONObject(i));
            JSONArray threads=list(owner);
            for(int i=0;i<threads.length();i++){
                String id=threads.getJSONObject(i).optString("id");
                JSONArray live=messages(owner,id);
                for(int j=live.length()-1;j>=0&&result.length()<Math.max(1,Math.min(100,limit));j--){
                    JSONObject message=live.optJSONObject(j);
                    if(message!=null&&message.optString("body").toLowerCase(java.util.Locale.ROOT)
                        .contains(query.trim().toLowerCase(java.util.Locale.ROOT)))
                        result.put(new JSONObject(message.toString()).put("thread",id));
                }
            }
            return result;
        }catch(Exception e){throw new IllegalStateException("Поиск истории недоступен",e);}
    }

    public synchronized long archivedCount(String owner){return archive.count(owner);}

    public synchronized void append(String owner, String role, String content) {
        append(owner, activeId(owner), role, content);
    }

    public synchronized void append(String owner, String id, String role, String content) {
        appendEvent(owner,id,role,content,null);
    }

    public synchronized void appendEvent(String owner,String id,String role,String content,JSONArray operations) {
        ensure(owner);
        safeId(id);
        if (!contains(readIndex(owner), id) && !prefs.getBoolean(key(owner,"pending_"+id),false)) return; // deleted while a request was running
        String body = content == null ? "" : content.trim();
        if (body.isEmpty()) return;
        if (!"user".equals(role) && !"agent".equals(role) && !"system".equals(role))
            throw new IllegalArgumentException("Недопустимая роль");
        try {
            JSONArray old = messages(owner,id), next = new JSONArray();
            int discarded=Math.max(0,old.length()-MAX_MESSAGES+1);
            // Archive evicted messages first. Never silently discard an old conversation.
            for(int i=0;i<discarded;i++){
                JSONObject archived=old.optJSONObject(i);
                if(archived!=null)archive.archive(owner,id,archived);
            }
            for (int i=discarded;i<old.length();i++) {
                JSONObject item=old.optJSONObject(i);
                if(item!=null)next.put(item);
            }
            next.put(new JSONObject().put("role",role)
                    .put("body",body.substring(0,Math.min(body.length(),MAX_BODY)))
                    .put("at",System.currentTimeMillis())
                    .put("operations","system".equals(role)&&operations!=null?new JSONArray(operations.toString()):new JSONArray()));
            JSONArray index=readIndex(owner), order=new JSONArray();
            JSONObject active=null;
            for(int i=0;i<index.length();i++) {
                JSONObject entry=index.optJSONObject(i);
                if(entry==null)continue;
                if(id.equals(entry.optString("id"))) active=entry;
                else order.put(entry);
            }
            if(active==null)active=new JSONObjectBuilder(id,"Новый диалог",System.currentTimeMillis()).toJson();
            if ("user".equals(role) && "Новый диалог".equals(active.optString("title"))) {
                String title=body.replace('\n',' ').replace('\r',' ').trim();
                active.put("title", title.substring(0,Math.min(title.length(),58)));
            }
            active.put("updated",System.currentTimeMillis());
            JSONArray reordered=new JSONArray().put(active);
            for(int i=0;i<order.length();i++)reordered.put(order.opt(i));
            prefs.edit().putString(transcriptKey(owner,id),next.toString())
                    .putString(key(owner,"thread_index"),reordered.toString())
                    .remove(key(owner,"pending_"+id)).commit();
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось сохранить сообщение",error);
        }
    }
}


