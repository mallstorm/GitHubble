package app.githubble.mobile.agent;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** One account/chat-bound bridge from model tools to reviewed native operations. */
public final class NativeAgentToolHost implements ProjectRuntime.ToolHost {
    private final Activity activity;
    private final GitHubAgentBridge github;
    private final AgentGitWorkspace git;
    private final WorkspaceHub workspace;
    private final AgentProjectStore projects;
    private final String account,thread;
    private String workspaceId;
    private String repository,branch;
    private boolean writeDenied;
    private final boolean githubEnabled,filesEnabled,planning;
    private final Runnable guard,refresh;
    private final IntSupplier permission;
    public NativeAgentToolHost(Activity activity,GitHubAgentBridge github,AgentGitWorkspace git,
            WorkspaceHub workspace,AgentProjectStore projects,String account,String thread,
            AgentProjectStore.Project project,boolean githubEnabled,boolean filesEnabled,
            boolean planning,IntSupplier permission,Runnable guard,Runnable refresh){
        this.activity=activity;this.github=github;this.git=git;this.workspace=workspace;
        this.projects=projects;this.account=account;this.thread=thread;
        repository=project.repository;branch=project.branch;workspaceId=project.workspaceId;
        this.githubEnabled=githubEnabled;this.filesEnabled=filesEnabled;this.planning=planning;
        this.permission=permission;this.guard=guard;this.refresh=refresh;
    }
    public String catalog(){
        String result=planning?"PLANNING MODE: discuss, inspect and plan. ALL mutations are forbidden.":
            "AGENT MODE: reason and discuss normally; execute only actions requested by the user.";
        if(AgentPermissionPolicy.mayWrite(permission.getAsInt(),planning)){
            result+="\nPROJECT: create_workspace {name,instructions} (empty local project; no GitHub needed).";
            if(filesEnabled)result+="\nLOCAL WRITE: write_files {summary,files:[{path,content}]} (1–12 complete UTF-8 files, 80 KiB/file, 240 KiB total; checkpoint and stale-file checks).";
            if(githubEnabled){
                result+="\nGITHUB WRITE: "+GitHubAgentOperations.WRITE_CATALOG;
                if(filesEnabled)result+=" publish_workspace {commit_message} (only linked repository/branch; bounded text snapshot; no deletions).";
            }
        }
        if(filesEnabled)result+="\nLOCAL READ (create/select a Workspace first): list_files {}, read_file {path}, search_files {query}, symbol_index {query}.";
        return result+"\nNo shell, arbitrary HTTP, security/billing policy changes, permission escalation or deletion tools exist. "+
            "Never claim that GitHub App permissions can be granted by an AI model. "+
            "If tools lack authorization, explain the exact limitation. A cancelled operation must not be retried.";
    }
    public boolean handles(String name){
        if(filesEnabled&&java.util.Arrays.asList("list_files","read_file","search_files","symbol_index").contains(name))return true;
        if(writeDenied||!AgentPermissionPolicy.mayWrite(permission.getAsInt(),planning))return false;
        return "create_workspace".equals(name)||(filesEnabled&&"write_files".equals(name))
            ||(githubEnabled&&(GitHubAgentOperations.WRITES.contains(name)
                ||(filesEnabled&&"publish_workspace".equals(name))));
    }
    private void check(){
        guard.run();
        if(!workspaceId.equals(projects.project(account).workspaceId))
            throw new java.util.concurrent.CancellationException("Workspace изменился");
        if(activity.isFinishing()||activity.isDestroyed()||Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Операция отменена");
    }
    private void event(String text,JSONArray operations){
        projects.appendOperations(account,thread,text,operations);
        activity.runOnUiThread(refresh);
    }
    public void observed(String name,JSONObject args){check();event("✓ "+name+" · выполнено",null);}
    private boolean approve(String name,String preview,boolean remote,boolean sensitive)throws Exception{
        check();
        if(!AgentPermissionPolicy.mayWrite(permission.getAsInt(),planning))throw new SecurityException("Запись запрещена");
        if(!AgentPermissionPolicy.needsReview(permission.getAsInt(),remote,sensitive))return true;
        CompletableFuture<Boolean> answer=new CompletableFuture<>();
        AtomicReference<AlertDialog> dialog=new AtomicReference<>();
        activity.runOnUiThread(()->{
            try{
                check();
                AlertDialog d=new AlertDialog.Builder(activity).setTitle("Git Agent · "+name)
                    .setMessage("Аккаунт: "+account+"\n"+preview)
                    .setPositiveButton("Разрешить один раз",(v,w)->answer.complete(true))
                    .setNegativeButton("Отклонить",(v,w)->answer.complete(false)).create();
                d.setOnCancelListener(v->answer.complete(false));
                d.setOnDismissListener(v->answer.complete(false));dialog.set(d);d.show();
            }catch(Exception cancelled){answer.complete(false);}
        });
        try{
            while(true){
                check();
                try{return answer.get(250,TimeUnit.MILLISECONDS);}
                catch(TimeoutException pending){/* Keep cancellation and account checks responsive. */}
            }
        }finally{activity.runOnUiThread(()->{AlertDialog d=dialog.get();if(d!=null)d.dismiss();});}
    }
    public String execute(String name,JSONObject arguments)throws Exception{
        check();
        if(!handles(name))throw new SecurityException("Операция недоступна в текущем режиме");
        if(arguments.toString().length()>300000)throw new IllegalArgumentException("Аргументы превышают лимит");
        JSONObject a=new JSONObject(arguments.toString());
        if(filesEnabled&&java.util.Arrays.asList("list_files","read_file","search_files","symbol_index").contains(name)){
            if(workspaceId.isEmpty())throw new IllegalArgumentException("Сначала создайте или выберите Workspace");
            String result=AgentExecutionEngine.executeReadOnly(workspace,account,workspaceId,name,a);
            observed(name,a);return result;
        }
        if("create_workspace".equals(name)){
            String title=a.optString("name","").trim();
            if(title.isEmpty()||title.length()>80)throw new IllegalArgumentException("Название проекта: 1–80 символов");
            String instructions=a.optString("instructions","");
            if(instructions.length()>12000)throw new IllegalArgumentException("Слишком длинные инструкции");
            if(!workspaceId.isEmpty())throw new IllegalStateException("В этом диалоге уже есть Workspace; создайте новый диалог для другого проекта");
            if(!approve("Создание проекта",title+"\n"+instructions,false,false))return denied(name);
            check();requireWrite();
            JSONObject created=workspace.create(account,title);
            workspaceId=created.getString("id");
            workspace.configure(account,workspaceId,title,"CREATED",instructions);
            projects.save(new AgentProjectStore.Project(account,title,repository,branch,workspaceId));
            event("📂 Создан проект «"+title+"»",null);
            return created.toString();
        }
        if("write_files".equals(name)){
            if(workspaceId.isEmpty())throw new IllegalArgumentException("Сначала создайте Workspace");
            AgentFilePlan plan=AgentFilePlan.parse(a.toString());
            WorkspaceChangeReview review=WorkspaceChangeReview.capture(workspace,account,workspaceId,plan,true);
            if(!review.hasChanges())return new JSONObject().put("unchanged",true).toString();
            if(!approve("Запись файлов",review.preview()+"\n\n"+plan.preview(),false,false))return denied(name);
            check();requireWrite();
            event("Записываются файлы проекта…",null);
            AgentExecutionEngine.Applied applied=WorkspaceChangeReview.applyApproved(activity,workspace,account,workspaceId,plan,review);
            event("Файлы записаны · checkpoint "+applied.checkpointId,review.operations);
            return new JSONObject().put("applied",true).put("checkpoint",applied.checkpointId)
                .put("files",review.operations).toString();
        }
        if("publish_workspace".equals(name)){
            if(workspaceId.isEmpty()||repository.isEmpty())throw new IllegalArgumentException("Создайте Workspace и привяжите репозиторий");
            github.requireAccount(account);
            WorkspacePublishPlan plan=WorkspacePublishPlan.capture(workspace,account,workspaceId);
            AgentGitWorkspace.Snapshot snapshot=git.inspect(account,repository,branch);
            String message=a.optString("commit_message","feat: publish workspace");
            if(!approve("Публикация проекта",plan.preview(repository,branch)+"\nКоммит: "+message,true,plan.skipped>0))return denied(name);
            check();requireWrite();github.requireAccount(account);
            JSONObject result=git.publishApprovedWorkspace(account,repository,branch,snapshot.head,workspace,plan,message);
            event("✓ Проект опубликован в "+repository+" / "+branch+" · "+result.toString(),null);
            return result.toString();
        }
        // Exact immutable arguments are presented before dispatch, not a paraphrase of the task.
        if(!approve(name,a.toString(2),true,GitHubAgentOperations.sensitive(name)))return denied(name);
        check();requireWrite();github.requireAccount(account);
        JSONObject result=GitHubAgentOperations.write(github,account,name,a,repository,branch);
        // Creating a repository should make it the project's target without losing local files.
        if("create_repository".equals(name)){
            check();
            String full=result.optString("full_name","");
            if(!full.isEmpty()){
                AgentProjectStore.Project current=projects.project(account);
                repository=full;branch=result.optString("default_branch","main");
                projects.save(new AgentProjectStore.Project(account,current.name,full,branch,current.workspaceId));
                // The runtime's target remains pinned; subsequent tools must pass repository explicitly.
            }
        }
        event("✓ "+name+" · "+result.optString("html_url",result.optString("full_name","выполнено")),null);
        return result.toString();
    }
    private void requireWrite(){
        if(!AgentPermissionPolicy.mayWrite(permission.getAsInt(),planning))throw new SecurityException("Разрешение на запись отозвано");
    }
    private String denied(String name)throws Exception{
        writeDenied=true;
        event("Отклонено пользователем: "+name,null);
        return new JSONObject().put("cancelled",true).put("instruction","Do not retry or substitute another write operation.").toString();
    }
}
