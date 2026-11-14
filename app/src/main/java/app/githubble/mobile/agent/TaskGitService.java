package app.githubble.mobile.agent;

import java.io.File;
import org.json.*;

/** Task-owned overlay, base commit, branch and durable publication evidence. UI and ToolBroker share it. */
public final class TaskGitService {
    public final ChangeStore changes;
    private final AgentProjectStore projects;
    private final WorkspaceHub hub;
    private final String account,task,project;
    private final GitDataApi api;
    private final Runnable guard;
    private final boolean localEnabled;
    public TaskGitService(File root,AgentProjectStore projects,WorkspaceHub hub,String account,String task,
            GitDataApi api,boolean localEnabled,Runnable guard)throws Exception {
        this.projects=projects;this.hub=hub;this.account=account;this.task=task;this.api=api;this.guard=guard;this.localEnabled=localEnabled;
        project=projects.projectId(account,task);if(project.isEmpty())throw new IllegalArgumentException("Сначала выберите постоянный проект");
        changes=new ChangeStore(root,account,project,task);
    }
    public WorkspaceSource source()throws Exception {
        guard.run();if(!project.equals(projects.projectId(account,task)))throw new java.util.concurrent.CancellationException("Проект изменился");
        AgentProjectStore.Project binding=projects.projectForChat(account,task);
        if(!binding.repository.isEmpty()&&api!=null){
            JSONObject taskState=projects.tasks.get(account,task),projectState=projects.registry.get(account,project);
            String previousRepo=taskState.optString("sourceRepository");
            if(!previousRepo.isEmpty()&&!previousRepo.equals(binding.repository)&&changes.list().length()>0)
                throw new ChangeStore.Conflict("Задача с изменениями не переносится в другой репозиторий");
            String sha=previousRepo.equals(binding.repository)?taskState.optString("sourceCommitSha"):"";
            JSONObject publication=taskState.optJSONObject("publication");
            if(publication!=null&&"CONFIRMED".equals(publication.optString("state"))&&binding.repository.equals(publication.optString("repository")))
                sha=publication.getString("commitSha");
            if(sha.isEmpty()){
                sha=GitHubWorkspaceSource.head(api,binding.repository,projectState.optString("baseBranch","main"));final String pinned=sha;
                projects.tasks.update(account,task,t->{AgentController.put(t,"sourceCommitSha",pinned);AgentController.put(t,"sourceRepository",binding.repository);});
            }
            GitHubWorkspaceSource remote=new GitHubWorkspaceSource(api,binding.repository,sha);
            if(localEnabled&&!binding.workspaceId.isEmpty()){
                JSONObject meta=hub.info(account,binding.workspaceId);
                if(sha.equals(meta.optString("source_sha"))&&binding.repository.equals(meta.optString("source_repository")))
                    return new CachedWorkspaceSource(remote,new LocalWorkspaceSource(hub,account,binding.workspaceId));
            }
            return remote;
        }
        if(localEnabled&&!binding.workspaceId.isEmpty())return new LocalWorkspaceSource(hub,account,binding.workspaceId);
        throw new SecurityException("Нет разрешённого файлового источника проекта");
    }
    public WorkspaceToolset tools()throws Exception{return new WorkspaceToolset(source(),changes);}
    public void recordChanges()throws Exception {
        JSONArray sets=changes.list(),ids=new JSONArray();for(int i=0;i<sets.length();i++)ids.put(sets.getJSONObject(i).getString("id"));
        projects.tasks.update(account,task,t->AgentController.put(t,"changeSets",ids));
    }
    public JSONObject publicationStatus(){JSONObject t=projects.tasks.get(account,task);JSONObject value=t.optJSONObject("publication");return value==null?new JSONObject():value;}
    private DeltaPublisher.Journal journal(){return new DeltaPublisher.Journal(){
        public JSONObject read(){return publicationStatus();}
        public void write(JSONObject value)throws Exception{
            if(!value.has("changesFingerprint"))value.put("changesFingerprint",changes.publishingFingerprint());
            projects.tasks.update(account,task,t->AgentController.put(t,"publication",UnifiedAgentStore.copy(value)));
        }
    };}
    public JSONObject preview()throws Exception {
        WorkspaceSource source=source();JSONArray delta=changes.delta(source),files=new JSONArray();boolean sensitive=false;
        for(int i=0;i<delta.length();i++){JSONObject entry=delta.getJSONObject(i);String path=entry.getString("path");if(path.startsWith(".github/workflows/"))sensitive=true;
            files.put(new JSONObject().put("path",path).put("kind",entry.isNull("after")?"DELETE":entry.getString("beforeSha").equals("missing")?"ADD":"MODIFY"));}
        JSONObject state=projects.tasks.get(account,task);
        return new JSONObject().put("files",files).put("count",files.length()).put("sensitive",sensitive).put("fingerprint",changes.fingerprint(source))
            .put("branch",TaskBranch.name(state.optString("title","task"),task)).put("repository",projects.registry.get(account,project).optString("repository"));
    }
    public JSONObject publishApproved(JSONObject preview,String message)throws Exception {
        guard.run();if(api==null)throw new SecurityException("GitHub не разрешён");
        WorkspaceSource source=source();String fingerprint=preview.getString("fingerprint");
        if(preview.getInt("count")==0)throw new IllegalArgumentException("Нет подтверждённой дельты; весь Workspace не публикуется");
        JSONObject projectState=projects.registry.get(account,project),taskState=projects.tasks.get(account,task);
        String repo=projectState.getString("repository");JSONObject branchRecord=taskState.optJSONObject("taskBranch");if(branchRecord==null)branchRecord=new JSONObject();
        String branch=TaskBranch.ensure(api,repo,projectState.optString("baseBranch","main"),taskState.optString("title","task"),task,branchRecord,
            value->projects.tasks.update(account,task,t->AgentController.put(t,"taskBranch",UnifiedAgentStore.copy(value))));
        projects.tasks.update(account,task,t->AgentController.put(t,"workingBranch",branch));
        String expected=GitHubWorkspaceSource.head(api,repo,branch);JSONArray delta=changes.delta(source);
        DeltaPublisher publisher=new DeltaPublisher(api,()->{guard.run();try{changes.verifyFrozen(source,fingerprint);}catch(Exception conflict){throw new IllegalStateException(conflict);}},journal());
        changes.freeze(source,fingerprint);
        JSONObject result;
        try{result=publisher.publish(repo,branch,expected,delta,message);}
        catch(Exception error){String status=publicationStatus().optString("state");if(status.isEmpty()||status.equals("CONFIRMED")||status.equals("ABORTED_SAFE"))changes.unfreeze();throw error;}
        confirmed(result.getString("sha"),fingerprint);return result;
    }
    private void confirmed(String sha,String fingerprint)throws Exception {
        changes.published(sha,fingerprint);
        projects.tasks.update(account,task,t->{AgentController.put(t,"sourceCommitSha",sha);AgentController.put(t,"confirmedCommitSha",sha);});
    }
    public JSONObject reconcile()throws Exception {
        if(api==null)throw new SecurityException("Нет GitHub");JSONObject result=new DeltaPublisher(api,guard,journal()).reconcile();String frozen=changes.publishingFingerprint();
        if(frozen.isEmpty())return result.put("already_reconciled",true);
        if(!frozen.equals(result.optString("changesFingerprint")))throw new ChangeStore.Conflict("Сверка относится к другому пакету; новые изменения не изменены");
        if("ABORTED_SAFE".equals(result.optString("state")))changes.unfreeze();else confirmed(result.getString("commitSha"),frozen);return result;
    }
}
