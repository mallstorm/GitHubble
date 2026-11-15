package app.githubble.mobile.mirror;

import android.content.Context;
import android.content.SharedPreferences;
import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * GitHubble OneShot. A short-lived workflow is hosted by the SOURCE repository only.
 * Destination OAuth credential is sealed to GitHub's secret public key and is removed
 * after the run completes. Never persist destination tokens, or expose them in inputs/logs.
 */
public final class MirrorManager {
    public static final String COPY="copy",MANAGED="managed",FORK="fork";
    public static final String PREPARING="preparing",DEST_CREATED="destination_created",
            DISPATCHED="dispatched",RUNNING="running",VERIFIED="verified",
            FAILED="failed",DIVERGED="diverged",UNKNOWN="unknown",CLEANUP_PENDING="cleanup_pending";
    private static final String API="https://api.github.com/repos/";
    private static final String PREFS="githubble_mirror_manager", KEY_JOBS="jobs";
    private final GitHubAccountStore accounts;
    private final Context applicationContext;
    private final SharedPreferences prefs;
    private final Object lock=new Object();

    public MirrorManager(Context context,GitHubAccountStore accounts){
        this.accounts=accounts;
        this.applicationContext=context.getApplicationContext();
        this.prefs=applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
    private Context prefsContext(){return applicationContext;}

    public static final class Job {
        public String id="",requestId="",source="",destination="",sourceAccountId="",destinationAccountId="";
        public String sourceDefault="",baselineSha="",mode=COPY,state=PREPARING,error="",runUrl="",targetDigest="";
        public boolean privateSource,secretInstalled,workflowInstalled,cleanupPending;
        public long runId,createdAt,updatedAt;
        public boolean isManaged(){return false;}
        public JSONObject json(){
            JSONObject o=new JSONObject();
            try{
                o.put("id",id);o.put("requestId",requestId);o.put("source",source);
                o.put("destination",destination);o.put("sourceAccountId",sourceAccountId);
                o.put("destinationAccountId",destinationAccountId);
                o.put("sourceDefault",sourceDefault);o.put("baselineSha",baselineSha);
                o.put("mode",mode);o.put("state",state);o.put("error",error);
                o.put("runUrl",runUrl);o.put("targetDigest",targetDigest);
                o.put("privateSource",privateSource);o.put("secretInstalled",secretInstalled);
                o.put("workflowInstalled",workflowInstalled);o.put("cleanupPending",cleanupPending);
                o.put("runId",runId);o.put("createdAt",createdAt);o.put("updatedAt",updatedAt);
            }catch(Exception ignored){}
            return o;
        }
        public static Job fromJson(JSONObject o){
            Job j=new Job();if(o==null)return j;
            j.id=o.optString("id","");j.requestId=o.optString("requestId",j.id);
            j.source=o.optString("source","");j.destination=o.optString("destination","");
            j.sourceAccountId=o.optString("sourceAccountId","");
            j.destinationAccountId=o.optString("destinationAccountId","");
            j.sourceDefault=o.optString("sourceDefault","main");
            j.baselineSha=o.optString("baselineSha","");
            j.mode=o.optString("mode",COPY);j.state=o.optString("state",PREPARING);
            j.error=o.optString("error","");j.runUrl=o.optString("runUrl","");
            j.targetDigest=o.optString("targetDigest","");
            j.privateSource=o.optBoolean("privateSource");
            j.secretInstalled=o.optBoolean("secretInstalled");
            j.workflowInstalled=o.optBoolean("workflowInstalled");
            j.cleanupPending=o.optBoolean("cleanupPending");
            j.runId=o.optLong("runId");j.createdAt=o.optLong("createdAt");
            j.updatedAt=o.optLong("updatedAt");return j;
        }
    }

    public List<Job> list(){
        synchronized(lock){
            List<Job> jobs=new ArrayList<>();
            try{
                JSONArray data=new JSONArray(prefs.getString(KEY_JOBS,"[]"));
                for(int i=0;i<data.length();i++){
                    JSONObject json=data.optJSONObject(i);
                    if(json!=null)jobs.add(Job.fromJson(json));
                }
            }catch(Exception ignored){}
            jobs.sort((a,b)->Long.compare(b.updatedAt,a.updatedAt));
            return jobs;
        }
    }
    public Job find(String id){for(Job j:list())if(j.id.equals(id))return j;return null;}
    private void save(Job job){
        synchronized(lock){
            List<Job> jobs=list();boolean found=false;
            for(int i=0;i<jobs.size();i++)if(jobs.get(i).id.equals(job.id)){
                jobs.set(i,job);found=true;break;
            }
            if(!found)jobs.add(job);
            job.updatedAt=System.currentTimeMillis();
            JSONArray arr=new JSONArray();for(Job j:jobs)arr.put(j.json());
            if(!prefs.edit().putString(KEY_JOBS,arr.toString()).commit())
                throw new IllegalStateException("Не удалось сохранить OneShot journal");
        }
    }

    private GitHubAccountStore.Account account(String id){
        for(GitHubAccountStore.Account a:accounts.listAccounts())if(a.id.equals(id))return a;
        throw new IllegalArgumentException("Аккаунт не подключён — повторно войдите в GitHubble");
    }
    private static GitHubApiClient client(String token,String login){
        if(token==null||token.isEmpty())throw new IllegalStateException("Нет действующего токена @"+login);
        final GitHubSession s=new GitHubSession(token,login,0L);
        return new GitHubApiClient(new GitHubSession.Source(){
            public GitHubSession capture(){return s;}
            public boolean isCurrent(GitHubSession got){return got==s;}
            public void reauthorization(GitHubSession got,boolean flag){}
        },null);
    }
    private GitHubApiClient api(GitHubAccountStore.Account a)throws Exception{
        GitHubApiClient gh=client(accounts.tokenForAccount(a.id),a.login);
        JSONObject who=gh.getJsonFresh("https://api.github.com/user");
        if(!a.login.equalsIgnoreCase(who.optString("login","")))
            throw new SecurityException("Сессия принадлежит другому GitHub-аккаунту");
        return gh;
    }
    private static void enqueueRecovery(Context app,String jobId){
        try{
            androidx.work.Data data=new androidx.work.Data.Builder()
                    .putString(MirrorOneShotWorker.JOB_ID,jobId).build();
            androidx.work.Constraints c=new androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build();
            androidx.work.OneTimeWorkRequest request=
                    new androidx.work.OneTimeWorkRequest.Builder(MirrorOneShotWorker.class)
                            .setInputData(data).setConstraints(c)
                            .setInitialDelay(30,java.util.concurrent.TimeUnit.SECONDS)
                            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR,
                                    45,java.util.concurrent.TimeUnit.SECONDS)
                            .build();
            androidx.work.WorkManager.getInstance(app).enqueueUniqueWork(
                    "GitHubble-OneShot-"+jobId,
                    androidx.work.ExistingWorkPolicy.KEEP,request);
        }catch(RuntimeException ignored){
            // User may still press "Проверить" in the repository screen.
        }
    }

    private static String enc(String s){
        try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}
        catch(java.io.UnsupportedEncodingException e){throw new IllegalStateException(e);}
    }

    public static final class SetupStatus {
        public static final int UNKNOWN=0,PRESENT=1,MISSING=2;
        public int connectedAccounts;
        public boolean controllerAccountConnected;
        public int controller=UNKNOWN,workflow=UNKNOWN,secret=UNKNOWN,variable=UNKNOWN;
        public String controllerMessage="",workflowMessage="",secretMessage="",variableMessage="";
        public boolean verifiedReady(){return connectedAccounts>=2;}
    }

    /** OneShot setup is per source repository, with NO controller or persistent PEM. */
    public SetupStatus setupStatus(){
        SetupStatus status=new SetupStatus();
        status.connectedAccounts=accounts.listAccounts().size();
        status.controllerAccountConnected=status.connectedAccounts>=2;
        status.controllerMessage="Отдельный Mirror Controller не требуется";
        status.workflowMessage="Временный Actions workflow создаётся при подтверждении копирования";
        status.secretMessage="Целевой токен временно шифруется для Secrets исходного репозитория";
        status.variableMessage="Client ID и Private Key вручную не требуются";
        return status;
    }

    /** Removed: users no longer create shared controller repositories. */
    public String setupController(){
        throw new UnsupportedOperationException(
                "Mirror Controller удалён из OneShot. Используйте Mirror Manager внутри репозитория.");
    }

    public Job start(String sourceFull,String sourceAccountId,String targetAccountId,
                     String targetName,boolean unusedManaged)throws Exception{
        MirrorPolicy.requireRepository(sourceFull);MirrorPolicy.requireName(targetName);
        GitHubAccountStore.Account sourceAccount=account(sourceAccountId),targetAccount=account(targetAccountId);
        if(sourceAccount.id.equals(targetAccount.id)||sourceAccount.login.equalsIgnoreCase(targetAccount.login))
            throw new IllegalArgumentException("Выберите другой GitHub-аккаунт");
        if(!sourceFull.substring(0,sourceFull.indexOf('/')).equalsIgnoreCase(sourceAccount.login))
            throw new IllegalArgumentException("Источник должен принадлежать выбранному аккаунту");
        String destination=MirrorPolicy.requireDifferent(sourceFull,targetAccount.login+"/"+targetName);
        for(Job old:list())if(old.destination.equalsIgnoreCase(destination)
                &&!FORK.equals(old.mode))
            throw new IllegalStateException("Это назначение уже есть в журнале OneShot");

        GitHubApiClient source=api(sourceAccount),target=api(targetAccount);
        JSONObject repo=source.getJsonFresh(API+sourceFull);
        String defaultBranch=repo.optString("default_branch","");
        if(!defaultBranch.matches("[A-Za-z0-9_./-]{1,250}") ||defaultBranch.contains(".."))
            throw new IllegalArgumentException("Невозможно зеркалировать репозиторий без корректной default branch");
        JSONObject head=source.getJsonFresh(API+sourceFull+"/git/ref/heads/"+enc(defaultBranch));
        String baseline=head.optJSONObject("object")==null?"":head.getJSONObject("object").optString("sha","");
        if(!baseline.matches("[0-9a-f]{40}"))throw new IllegalStateException("Нет исходного Git commit SHA");
        try{
            target.getJsonFresh(API+destination);
            throw new IllegalStateException("Репозиторий назначения существует — OneShot не перезаписывает проекты");
        }catch(GitHubApiClient.ApiException notFound){if(notFound.statusCode!=404)throw notFound;}

        Job job=new Job();
        job.id=UUID.randomUUID().toString().replace("-","");
        job.requestId=job.id;job.source=sourceFull;job.destination=destination;
        job.sourceAccountId=sourceAccountId;job.destinationAccountId=targetAccountId;
        job.sourceDefault=defaultBranch;job.baselineSha=baseline;
        job.privateSource=repo.optBoolean("private",false);
        job.mode=COPY;job.createdAt=System.currentTimeMillis();save(job);
        try{
            JSONObject created=target.postJson("https://api.github.com/user/repos",
                    new JSONObject().put("name",targetName).put("private",job.privateSource)
                            .put("auto_init",false).put("description","OneShot independent copy of "+sourceFull),true);
            if(!destination.equalsIgnoreCase(created.optString("full_name",""))
                    ||created.optBoolean("fork",true)
                    ||created.optBoolean("private")!=job.privateSource)
                throw new SecurityException("Назначение не соответствует согласованным настройкам");
            job.state=DEST_CREATED;save(job);

            // OneShot source workflow MUST be created on the source default branch.
            String path=MirrorWorkflow.path(job.requestId);
            JSONObject workflow=new JSONObject().put("message","chore(mirror): temporary GitHubble OneShot runner")
                    .put("branch",defaultBranch)
                    .put("content",Base64.getEncoder().encodeToString(
                            MirrorWorkflow.yaml().getBytes(StandardCharsets.UTF_8)));
            source.putJson(API+sourceFull+"/contents/"+path,workflow);
            job.workflowInstalled=true;save(job);

            // Put only the TARGET token, encrypted with GitHub's repository secret public key.
            // Never write the token to the app preferences, workflow input or source.
            MirrorSecrets.put(source,sourceFull,job.requestId,accounts.tokenForAccount(targetAccountId));
            job.secretInstalled=true;save(job);
            JSONObject dispatch=new JSONObject().put("ref",defaultBranch)
                    .put("inputs",new JSONObject().put("destination",destination)
                            .put("source_default",defaultBranch)
                            .put("baseline_sha",baseline)
                            .put("request_id",job.requestId));
            source.executeText("POST",API+sourceFull+"/actions/workflows/"
                    +enc(path.substring(path.lastIndexOf('/')+1))+"/dispatches",
                    dispatch.toString(),true);
            job.state=DISPATCHED;
            job.runUrl="https://github.com/"+sourceFull+"/actions";
            save(job);
            enqueueRecovery(prefsContext(),job.id);
            return job;
        }catch(Exception error){
            job.error=safeError(error);
            job.state=error instanceof GitHubApiClient.MutationOutcomeUnknownException?UNKNOWN:FAILED;
            if(!UNKNOWN.equals(job.state)){
                // The dispatch has not succeeded; no runner is expected to read the token.
                try{cleanup(source,job);}catch(Exception clean){
                    job.cleanupPending=true;job.error+="; очистка: "+safeError(clean);
                }
            }else job.cleanupPending=true;
            save(job);
            throw error;
        }
    }

    private void cleanup(GitHubApiClient source,Job job)throws Exception{
        // Delete secret first (safe only after GitHub reports run COMPLETED or pre-dispatch failure).
        if(job.secretInstalled){
            MirrorSecrets.delete(source,job.source,job.requestId);
            job.secretInstalled=false;save(job);
        }
        if(job.workflowInstalled){
            String path=MirrorWorkflow.path(job.requestId);
            String url=API+job.source+"/contents/"+path;
            JSONObject remote;
            try{remote=source.getJsonFresh(url+"?ref="+enc(job.sourceDefault));}
            catch(GitHubApiClient.ApiException e){
                if(e.statusCode!=404)throw e;
                job.workflowInstalled=false;save(job);
                job.cleanupPending=false;return;
            }
            String text=remote.optString("content","").replaceAll("\\s+","");
            String actual=new String(Base64.getDecoder().decode(text),StandardCharsets.UTF_8);
            if(!MirrorWorkflow.yaml().equals(actual))
                throw new SecurityException("Содержимое временного workflow изменено — автоматическое удаление запрещено");
            JSONObject delete=new JSONObject().put("message","chore(mirror): remove temporary OneShot runner")
                    .put("sha",remote.getString("sha")).put("branch",job.sourceDefault);
            source.executeText("DELETE",url,delete.toString(),true);
            job.workflowInstalled=false;save(job);
        }
        job.cleanupPending=false;save(job);
    }

    /** Detect a completed run by opaque request ID, verify refs, then delete temporary credentials. */
    public Job refresh(String id)throws Exception{
        Job job=find(id);if(job==null)throw new IllegalArgumentException("OneShot job не найден");
        if(FORK.equals(job.mode)||MANAGED.equals(job.mode))
            throw new IllegalStateException("Старый план: используется другая версия Mirror Manager");
        GitHubApiClient source=api(account(job.sourceAccountId));
        if(job.cleanupPending && !RUNNING.equals(job.state)
                && !DISPATCHED.equals(job.state)&&!UNKNOWN.equals(job.state)){
            try{cleanup(source,job);}
            catch(Exception e){job.error="Осталась очистка: "+safeError(e);save(job);}
            return job;
        }
        if(VERIFIED.equals(job.state)&&!job.cleanupPending)return job;
        JSONObject result=source.getJsonFresh(API+job.source+"/actions/runs?event=workflow_dispatch&per_page=100");
        JSONArray runs=result.optJSONArray("workflow_runs");
        JSONObject found=null;
        for(int i=0;runs!=null&&i<runs.length();i++){
            JSONObject run=runs.optJSONObject(i);
            if(run!=null&&run.optString("display_title","").contains(job.requestId)){found=run;break;}
        }
        if(found==null){
            if(System.currentTimeMillis()-job.createdAt>60L*60*1000){
                job.state=UNKNOWN;job.error="Run не найден. Секрет не удалён: запуск может ещё ожидать очереди.";
                job.cleanupPending=true;save(job);
            }
            return job;
        }
        job.runId=found.optLong("id");
        job.runUrl=found.optString("html_url",job.runUrl);
        if(!"completed".equals(found.optString("status",""))){
            job.state=RUNNING;save(job);return job;
        }
        if(!"success".equals(found.optString("conclusion",""))){
            job.state=FAILED;job.error="GitHub Actions: "+found.optString("conclusion","failure");
        }else{
            try{
                GitHubApiClient dst=api(account(job.destinationAccountId));
                Map<String,String> srcRefs=refs(source,job.source);
                srcRefs.put("refs/heads/"+job.sourceDefault,job.baselineSha);
                Map<String,String> dstRefs=refs(dst,job.destination);
                if(srcRefs.isEmpty()||!srcRefs.equals(dstRefs)){
                    job.state=DIVERGED;job.error="SHA веток/тегов снимка не совпадают; см. Actions";
                }else{
                    job.state=VERIFIED;job.error="";
                    String actualDefault=dst.getJsonFresh(API+job.destination).optString("default_branch","");
                    if(!job.sourceDefault.equals(actualDefault)){
                        dst.patch(API+job.destination,new JSONObject().put("default_branch",job.sourceDefault));
                    }
                }
            }catch(Exception e){
                job.state=UNKNOWN;job.error="Проверка refs: "+safeError(e);
            }
        }
        job.cleanupPending=job.secretInstalled||job.workflowInstalled;
        save(job);
        try{cleanup(source,job);}
        catch(Exception e){job.cleanupPending=true;job.error+="; очистка: "+safeError(e);save(job);}
        return job;
    }

    public Job sync(String ignored)throws Exception{
        throw new UnsupportedOperationException("OneShot — только разовое копирование. Синхронизация отключена.");
    }
    public Job retry(String ignored)throws Exception{
        throw new UnsupportedOperationException("Создайте новый план с другим именем назначения.");
    }

    private static Map<String,String> refs(GitHubApiClient api,String repo)throws Exception{
        Map<String,String> map=new LinkedHashMap<>();
        for(String kind:new String[]{"heads","tags"}){
            for(int page=1;page<=50;page++){
                JSONArray a=api.getArray(API+repo+"/git/matching-refs/"+kind+"?per_page=100&page="+page);
                for(int i=0;i<a.length();i++){
                    JSONObject item=a.optJSONObject(i);if(item==null)continue;
                    JSONObject o=item.optJSONObject("object");
                    String key=item.optString("ref","");
                    if(o!=null&&key.startsWith("refs/"+kind+"/"))map.put(key,o.optString("sha",""));
                }
                if(a.length()<100)break;
                if(page==50)throw new IllegalStateException("Слишком много refs для проверки");
            }
        }
        return map;
    }
    private static String safeError(Exception e){
        if(e instanceof GitHubApiClient.ApiException){
            GitHubApiClient.ApiException gh=(GitHubApiClient.ApiException)e;
            return "HTTP "+gh.statusCode+": проверьте Contents, Workflows, Secrets и Actions права GitHub App";
        }
        String msg=e.getMessage();
        if(msg==null||msg.isEmpty())return e.getClass().getSimpleName();
        return msg.length()>180?msg.substring(0,180):msg;
    }
}
