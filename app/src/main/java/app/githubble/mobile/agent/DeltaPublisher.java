package app.githubble.mobile.agent;

import java.io.IOException;
import org.json.*;

/** One delta transaction. The journal is durable before any mutation; uncertain requests are never replayed. */
public final class DeltaPublisher {
    public static final class UnknownOutcome extends IOException {public UnknownOutcome(String message,Throwable cause){super(message,cause);}}
    public interface Journal {JSONObject read()throws Exception;void write(JSONObject value)throws Exception;}
    private final GitDataApi api;
    private final Runnable guard;
    private final Journal journal;
    public DeltaPublisher(GitDataApi api,Runnable guard,Journal journal){this.api=api;this.guard=guard;this.journal=journal;}
    public JSONObject publish(String repo,String branch,String expectedHead,JSONArray delta,String message)throws Exception {
        AgentGitWorkspace.validateRepository(repo,branch);GitObjectId.requireCommit(expectedHead);
        if(!branch.startsWith("hubble/")||branch.equals("main")||branch.equals("master"))throw new SecurityException("Публикация только в ветку задачи hubble/");
        if(delta.length()<1||delta.length()>1000)throw new IllegalArgumentException("Нет дельты или больше 1000 путей");
        JSONObject old=journal.read();
        if(old!=null&&old.length()>0&&!java.util.Arrays.asList("CONFIRMED","ABORTED_SAFE").contains(old.optString("state")))
            throw new UnknownOutcome("Предыдущая публикация не сверена; повтор запрещён",null);
        guard.run();JSONObject branchInfo=api.get(GitDataApi.base(repo)+"/branches/"+GitDataApi.encode(branch));
        if(branchInfo.optBoolean("protected"))throw new SecurityException("Защищённая ветка");
        RemoteSync.Plan plan=RemoteSync.plan(api,repo,branch,expectedHead,delta);
        JSONObject record=new JSONObject().put("state","PREPARING").put("repository",repo).put("branch",branch)
            .put("expectedHead",plan.parent).put("paths",new JSONArray()).put("createdAt",System.currentTimeMillis());
        for(int i=0;i<delta.length();i++)record.getJSONArray("paths").put(delta.getJSONObject(i).getString("path"));journal.write(record);
        try{
            JSONArray entries=new JSONArray();
            for(int i=0;i<plan.entries.length();i++){
                guard.run();JSONObject item=plan.entries.getJSONObject(i);String path=item.getString("path");
                JSONObject treeEntry=new JSONObject().put("path",path).put("mode",item.optString("mode","100644")).put("type","blob");
                if(item.isNull("after"))treeEntry.put("sha",JSONObject.NULL);
                else {
                    String content=item.getString("after");ChangeStore.text(content);
                    JSONObject blob=api.post(GitDataApi.base(repo)+"/git/blobs",new JSONObject().put("content",content).put("encoding","utf-8"));
                    String sha=blob.getString("sha");if(!sha.equals(GitObjectId.blob(content)))throw new IOException("GitHub вернул неверный blob SHA");treeEntry.put("sha",sha);
                }
                entries.put(treeEntry);
            }
            guard.run();String tree=api.post(GitDataApi.base(repo)+"/git/trees",new JSONObject().put("base_tree",plan.tree).put("tree",entries)).getString("sha");GitObjectId.requireCommit(tree);
            String clean=message==null?"feat: task changes":message.replace('\n',' ').replace('\r',' ').trim();if(clean.isEmpty())clean="feat: task changes";
            if(clean.length()>200)clean=clean.substring(0,200);
            guard.run();String commit=api.post(GitDataApi.base(repo)+"/git/commits",new JSONObject().put("message",clean).put("tree",tree).put("parents",new JSONArray().put(plan.parent))).getString("sha");GitObjectId.requireCommit(commit);
            record.put("commitSha",commit).put("treeSha",tree).put("state","REF_UPDATE_PENDING");journal.write(record);
            guard.run();if(!plan.parent.equals(GitHubWorkspaceSource.head(api,repo,branch)))throw new ChangeStore.Conflict("HEAD изменился перед обновлением ref");
            api.patch(GitDataApi.base(repo)+"/git/refs/heads/"+GitDataApi.encode(branch),new JSONObject().put("sha",commit).put("force",false));
            // A success response alone isn't evidence that this ref points at the commit.
            String ref=GitHubWorkspaceSource.head(api,repo,branch);if(!commit.equals(ref))throw new IOException("Ref не подтвердил опубликованный commit");
            record.put("state","CONFIRMED").put("confirmedRef",ref).put("rebased",plan.rebased);journal.write(record);
            return new JSONObject().put("sha",commit).put("ref",branch).put("files",delta.length()).put("rebased",plan.rebased)
                .put("url","https://github.com/"+repo+"/commit/"+commit);
        }catch(Exception failure){
            record.put("unknownAfter",record.optString("state")).put("state","UNKNOWN").put("error",AgentLogRedactor.redact(failure.getMessage()));journal.write(record);
            throw new UnknownOutcome("Публикация остановлена. Проверьте ref/commit; повтор записи не выполняется",failure);
        }
    }
    /** Read-only reconciliation of a known candidate; never sends another mutation. */
    public JSONObject reconcile()throws Exception {
        JSONObject record=journal.read();if(record==null||record.length()==0)throw new UnknownOutcome("Нет публикации для сверки",null);
        if(record.optString("unknownAfter").equals("PREPARING")){
            guard.run();String ref=GitHubWorkspaceSource.head(api,record.getString("repository"),record.getString("branch"));
            if(!ref.equals(record.optString("expectedHead")))throw new UnknownOutcome("Ветка изменилась; нужна ручная сверка",null);
            record.put("state","ABORTED_SAFE");journal.write(record);return record;
        }
        if(record.optString("commitSha").isEmpty())throw new UnknownOutcome("Нет commit SHA для автоматической сверки",null);
        guard.run();String head=GitHubWorkspaceSource.head(api,record.getString("repository"),record.getString("branch"));
        if(!head.equals(record.getString("commitSha")))throw new UnknownOutcome("Ref не совпадает с ожидаемым commit; нужна ручная сверка",null);
        record.put("state","CONFIRMED").put("confirmedRef",head);journal.write(record);return record;
    }
}
