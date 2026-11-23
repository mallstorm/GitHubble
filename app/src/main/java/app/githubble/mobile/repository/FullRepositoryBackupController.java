package app.githubble.mobile.repository;

import android.content.Context;
import android.content.SharedPreferences;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.mirror.MirrorPolicy;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubSession;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent, account-scoped GitHub Actions controller for portable full backups. */
public final class FullRepositoryBackupController {
    private static final String API="https://api.github.com/repos/";
    private static final String PREF="githubble_full_repository_backup";
    private final GitHubAccountStore accounts;
    private final SharedPreferences store;

    public FullRepositoryBackupController(Context context,GitHubAccountStore accounts) {
        this.accounts=accounts;
        this.store=context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }

    public static final class Job {
        public String id="",repository="",accountId="",defaultBranch="",baselineSha="",bootstrapSha="";
        public String state="preparing",stage="Подготовка резервной копии",error="",runUrl="";
        public int percent=0;
        public long runId=0,artifactId=0,artifactBytes=0;
        public boolean includeReleases,workflowInstalled;
        public JSONObject json()throws Exception {
            return new JSONObject().put("id",id).put("repository",repository)
                    .put("accountId",accountId).put("defaultBranch",defaultBranch)
                    .put("baselineSha",baselineSha).put("bootstrapSha",bootstrapSha).put("state",state)
                    .put("stage",stage).put("error",error).put("runUrl",runUrl)
                    .put("percent",percent).put("runId",runId).put("artifactId",artifactId)
                    .put("artifactBytes",artifactBytes).put("includeReleases",includeReleases)
                    .put("workflowInstalled",workflowInstalled);
        }
        public static Job from(JSONObject v) {
            Job j=new Job();
            j.id=v.optString("id","");j.repository=v.optString("repository","");
            j.accountId=v.optString("accountId","");
            j.defaultBranch=v.optString("defaultBranch","");
            j.baselineSha=v.optString("baselineSha","");
            j.bootstrapSha=v.optString("bootstrapSha","");
            j.state=v.optString("state","preparing");j.stage=v.optString("stage","");
            j.error=v.optString("error","");j.runUrl=v.optString("runUrl","");
            j.percent=v.optInt("percent");j.runId=v.optLong("runId");
            j.artifactId=v.optLong("artifactId");j.artifactBytes=v.optLong("artifactBytes");
            j.includeReleases=v.optBoolean("includeReleases");
            j.workflowInstalled=v.optBoolean("workflowInstalled");
            return j;
        }
    }

    private GitHubAccountStore.Account account(String accountId) {
        for(GitHubAccountStore.Account a:accounts.listAccounts())
            if(a.id.equals(accountId))return a;
        throw new IllegalStateException("Выбранный GitHub-аккаунт отключён");
    }

    static void validateIdentity(String repository,String login) {
        MirrorPolicy.requireRepository(repository);
        if(login==null||!login.equalsIgnoreCase(repository.substring(0,repository.indexOf('/'))))
            throw new SecurityException("Создать архив может только подключённый владелец репозитория");
    }

    private GitHubApiClient client(String id)throws Exception {
        GitHubAccountStore.Account owner=account(id);
        String token=accounts.tokenForAccount(id);
        if(token==null||token.isEmpty())throw new IllegalStateException("Повторно войдите в GitHub");
        final GitHubSession s=new GitHubSession(token,owner.login,0L);
        GitHubApiClient api=new GitHubApiClient(new GitHubSession.Source(){
            public GitHubSession capture(){return s;}
            public boolean isCurrent(GitHubSession got){return got==s;}
            public void reauthorization(GitHubSession got,boolean flag){}
        },null);
        JSONObject identity=api.getJsonFresh("https://api.github.com/user");
        if(!owner.login.equalsIgnoreCase(identity.optString("login","")))
            throw new SecurityException("Токен принадлежит другому GitHub-аккаунту");
        return api;
    }

    private void save(Job j)throws Exception {
        if(!store.edit().putString("job_"+j.repository.toLowerCase(Locale.ROOT),j.json().toString()).commit())
            throw new IllegalStateException("Не удалось сохранить состояние создания архива");
    }
    public Job latest(String repository) {
        try{
            return Job.from(new JSONObject(store.getString("job_"+repository.toLowerCase(Locale.ROOT),"{}")));
        }catch(Exception ex){return null;}
    }

    private static String head(GitHubApiClient api,String repo,String branch)throws Exception {
        String escaped=java.net.URLEncoder.encode(branch,"UTF-8").replace("+","%20");
        JSONObject response=api.getJsonFresh(API+repo+"/git/ref/heads/"+escaped);
        JSONObject object=response.optJSONObject("object");
        String sha=object==null?"":object.optString("sha","");
        if(!sha.matches("[a-fA-F0-9]{40}"))throw new SecurityException("Не удалось проверить Git SHA");
        return sha.toLowerCase(Locale.ROOT);
    }

    public Job begin(String repository,String accountId,boolean releaseBinaries)throws Exception {
        validateIdentity(repository,account(accountId).login);
        GitHubApiClient api=client(accountId);
        JSONObject repo=api.getJsonFresh(API+repository);
        if(repo.optBoolean("archived")||repo.optBoolean("disabled"))
            throw new IllegalStateException("Архивный или отключённый репозиторий недоступен");
        String branch=repo.optString("default_branch","");
        if(!branch.matches("[A-Za-z0-9_./-]{1,250}")||branch.contains("..")||
                branch.startsWith("/")||branch.endsWith("/"))
            throw new IllegalStateException("Недопустимое имя основной ветки");
        Job previous=latest(repository);
        if(previous!=null&&("preparing".equals(previous.state)||"running".equals(previous.state)))
            throw new IllegalStateException("Резервирование уже выполняется. Откройте его прогресс.");
        Job j=new Job();
        j.id=UUID.randomUUID().toString().replace("-","");
        j.repository=repository;j.accountId=accountId;j.includeReleases=releaseBinaries;
        j.defaultBranch=branch;j.baselineSha=head(api,repository,branch);
        j.runUrl="https://github.com/"+repository+"/actions";
        j.percent=4;j.stage="Сверка исходного SHA и разрешений";
        save(j);
        try {
            String url=API+repository+"/contents/"+FullRepositoryBackupWorkflow.PATH;
            JSONObject existing=null;
            try {
                existing=api.getJsonFresh(url+"?ref="+java.net.URLEncoder.encode(branch,"UTF-8"));
            } catch(GitHubApiClient.ApiException missing) {
                if(missing.statusCode!=404)throw missing;
            }
            if(existing==null) {
                JSONObject request=new JSONObject()
                        .put("message","chore(backup): install GitHubble export workflow")
                        .put("branch",branch)
                        .put("content",Base64.getEncoder().encodeToString(
                                FullRepositoryBackupWorkflow.yaml().getBytes(StandardCharsets.UTF_8)));
                api.putJson(url,request);
                j.workflowInstalled=true;save(j);
            } else {
                String content=new String(Base64.getMimeDecoder().decode(
                        existing.optString("content","")),StandardCharsets.UTF_8);
                if(!FullRepositoryBackupWorkflow.yaml().equals(content))
                    throw new SecurityException("Существующий workflow отличается от GitHubble; перезапись запрещена");
            }
            // Do not dispatch until the new default branch HEAD is visible to Actions.
            String current=head(api,repository,branch);
            if(current.equals(j.baselineSha)&&j.workflowInstalled)
                throw new IllegalStateException("GitHub пока не опубликовал служебный workflow");
            j.bootstrapSha=current;save(j);
            JSONObject inputs=new JSONObject()
                    .put("request_id",j.id)
                    .put("source_sha",j.baselineSha)
                    .put("default_branch",branch)
                    .put("release_binaries",releaseBinaries?"true":"false");
            JSONObject dispatch=new JSONObject().put("ref",branch).put("inputs",inputs);
            api.executeText("POST",API+repository+"/actions/workflows/"
                    +FullRepositoryBackupWorkflow.WORKFLOW+"/dispatches",
                    dispatch.toString(),true);
            j.state="running";j.percent=8;
            j.stage="Создание резервной копии репозитория · запуск GitHub Actions";
            save(j);
            return j;
        } catch(Exception e) {
            // A dispatch timeout is ambiguous; do not auto-dispatch a second request.
            j.state="unknown";j.error=e.getMessage()==null?"Неизвестная ошибка":e.getMessage();
            j.stage="Проверьте Actions перед повторением";save(j);
            throw e;
        }
    }

    /** Safely determines which of the real GitHub Actions steps have completed. */
    public static int stagePercent(JSONArray steps) {
        int count=0;
        for(int i=0;i<steps.length();i++) {
            JSONObject row=steps.optJSONObject(i);
            if(row!=null&&"success".equals(row.optString("conclusion")))count++;
        }
        return Math.min(91,Math.max(8,8+count*11));
    }

    public Job refresh(String repository)throws Exception {
        Job j=latest(repository);
        if(j==null||j.id.isEmpty())throw new IllegalStateException("Нет активной резервной копии");
        if("ready".equals(j.state))return j;
        GitHubApiClient api=client(j.accountId);
        JSONObject data=api.getJsonFresh(API+repository+"/actions/runs?event=workflow_dispatch&per_page=100");
        JSONArray runs=data.optJSONArray("workflow_runs");
        JSONObject run=null;
        for(int i=0;runs!=null&&i<runs.length();i++){
            JSONObject item=runs.optJSONObject(i);
            if(item==null)continue;
            if(("GitHubble Full Backup "+j.id).equals(item.optString("display_title"))
                    &&"workflow_dispatch".equals(item.optString("event"))
                    &&item.optString("path","").startsWith(FullRepositoryBackupWorkflow.PATH)) {
                run=item;break;
            }
        }
        if(run==null){j.stage="Ожидается запуск GitHub Actions";save(j);return j;}
        j.runId=run.optLong("id");
        j.runUrl=run.optString("html_url",j.runUrl);
        String state=run.optString("status");
        if(!"completed".equals(state)){
            j.state="running";j.stage="Создание резервной копии репозитория";
            JSONArray jobs=api.getJsonFresh(API+repository+"/actions/runs/"+j.runId+
                    "/jobs?per_page=100").optJSONArray("jobs");
            JSONObject job=jobs==null||jobs.length()==0?null:jobs.optJSONObject(0);
            JSONArray steps=job==null?null:job.optJSONArray("steps");
            if(steps!=null){
                j.percent=stagePercent(steps);
                for(int i=0;i<steps.length();i++){
                    JSONObject step=steps.optJSONObject(i);
                    if(step!=null&&"in_progress".equals(step.optString("status"))) {
                        j.stage=step.optString("name",j.stage);break;
                    }
                }
            }
            save(j);return j;
        }
        if(!"success".equals(run.optString("conclusion"))) {
            j.state="failed";j.error="GitHub Actions: "+run.optString("conclusion","failure");
            j.stage="Архив не создан; проверьте журнал выполнения";save(j);return j;
        }
        j.state="verifying";j.percent=94;j.stage="Сверка информации · проверка опубликованного архива";save(j);
        JSONArray artifacts=api.getJsonFresh(API+repository+"/actions/runs/"+j.runId+
                "/artifacts?per_page=100").optJSONArray("artifacts");
        for(int i=0;artifacts!=null&&i<artifacts.length();i++){
            JSONObject artifact=artifacts.optJSONObject(i);
            if(artifact!=null&&("githubble-full-"+j.id).equals(artifact.optString("name"))
                    &&!artifact.optBoolean("expired")&&artifact.optLong("size_in_bytes")>0){
                j.artifactId=artifact.optLong("id");
                j.artifactBytes=artifact.optLong("size_in_bytes");
                j.percent=100;j.state="ready";
                j.stage="Архив создан и валидирован · сохраните ZIP вне GitHub";
                // Remove only the temporary workflow we installed, and only when
                // the source HEAD is still exactly the known bootstrap commit.
                // No force-push, no reset, and no deletion of somebody else's file.
                if(j.workflowInstalled) {
                    try {
                        if(j.bootstrapSha.equalsIgnoreCase(head(api,j.repository,j.defaultBranch))) {
                            String path=API+j.repository+"/contents/"+FullRepositoryBackupWorkflow.PATH;
                            JSONObject workflow=api.getJsonFresh(path+"?ref="+
                                java.net.URLEncoder.encode(j.defaultBranch,"UTF-8"));
                            String actual=new String(Base64.getMimeDecoder().decode(
                                workflow.optString("content","")),StandardCharsets.UTF_8);
                            if(FullRepositoryBackupWorkflow.yaml().equals(actual)) {
                                api.executeText("DELETE",path,new JSONObject()
                                    .put("message","chore(backup): remove temporary GitHubble workflow")
                                    .put("branch",j.defaultBranch)
                                    .put("sha",workflow.getString("sha")).toString(),true);
                                j.workflowInstalled=false;
                            }
                        }
                    } catch(Exception cleanup) {
                        j.stage+="\nСлужебный workflow не удалён; проверьте репозиторий.";
                    }
                }
                save(j);return j;
            }
        }
        j.stage="Ожидается публикация ZIP из GitHub Actions";save(j);return j;
    }
}
