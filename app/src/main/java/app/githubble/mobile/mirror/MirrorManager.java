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
    public static final String COPY="copy",SYNC="sync",RETURN="return",MANAGED="managed",FORK="fork";
    public static final String PREPARING="preparing",DEST_CREATED="destination_created",
            DISPATCHED="dispatched",RUNNING="running",VERIFIED="verified",
            FAILED="failed",DIVERGED="diverged",UNKNOWN="unknown",CLEANUP_PENDING="cleanup_pending",
            PR_READY="pr_ready",PR_MERGED="pr_merged",PR_CLOSED="pr_closed";
    private static final String API="https://api.github.com/repos/";
    private static final String PREFS="githubble_mirror_manager", KEY_JOBS="jobs";
    private final GitHubAccountStore accounts;
    private final MirrorLinkRegistry linkRegistry;
    private final Context applicationContext;
    private final SharedPreferences prefs;
    private final Object lock=new Object();

    public MirrorManager(Context context,GitHubAccountStore accounts){
        this.accounts=accounts;
        this.linkRegistry=new MirrorLinkRegistry(context);
        this.applicationContext=context.getApplicationContext();
        this.prefs=applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
    private Context prefsContext(){return applicationContext;}

    public static final class Job {
        public String id="",requestId="",source="",destination="",sourceAccountId="",destinationAccountId="";
        public String sourceDefault="",baselineSha="",mode=COPY,state=PREPARING,error="",runUrl="",targetDigest="";
        public String destinationDefault="",targetBaselineSha="",reviewBranch="",linkedCopyId="",linkPairId="",prUrl="";
        public boolean privateSource,secretInstalled,workflowInstalled,cleanupPending;
        public long runId,createdAt,updatedAt,prNumber;
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
                o.put("destinationDefault",destinationDefault);o.put("targetBaselineSha",targetBaselineSha);
                o.put("reviewBranch",reviewBranch);o.put("linkedCopyId",linkedCopyId);
                o.put("linkPairId",linkPairId);
                o.put("prUrl",prUrl);o.put("prNumber",prNumber);
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
            j.updatedAt=o.optLong("updatedAt");
            j.destinationDefault=o.optString("destinationDefault","");
            j.targetBaselineSha=o.optString("targetBaselineSha","");
            j.reviewBranch=o.optString("reviewBranch","");
            j.linkedCopyId=o.optString("linkedCopyId","");
            j.linkPairId=o.optString("linkPairId","");
            j.prUrl=o.optString("prUrl","");
            j.prNumber=o.optLong("prNumber");
            return j;
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
        job.mode=COPY;job.linkPairId=MirrorLinkRegistry.pairId(sourceFull,destination);
        job.createdAt=System.currentTimeMillis();save(job);
        try{
            JSONObject created=target.postJson("https://api.github.com/user/repos",
                    new JSONObject().put("name",targetName).put("private",job.privateSource)
                            .put("auto_init",false).put("description","OneShot independent copy of "+sourceFull),true);
            if(!destination.equalsIgnoreCase(created.optString("full_name",""))
                    ||created.optBoolean("fork",true)
                    ||created.optBoolean("private")!=job.privateSource)
                throw new SecurityException("Назначение не соответствует согласованным настройкам");
            job.state=DEST_CREATED;save(job);

            stageAndDispatch(source,job,targetAccountId);
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

    /**
     * Reconstructs durable pair links independently of the old transfer journal.
     * An older successful COPY is migrated once; subsequently the registry stands alone.
     */
    public List<MirrorLinkRegistry.Link> listLinks(){
        for(Job j:list()){
            if(COPY.equals(j.mode)&&VERIFIED.equals(j.state)
                    &&j.source.contains("/")&&j.destination.contains("/")){
                try{linkRegistry.remember(j.source,j.destination,j.source,j.id);}
                catch(IllegalArgumentException ignored){}
            }
        }
        return linkRegistry.list();
    }
    public List<MirrorLinkRegistry.Link> linksFor(String repo){
        listLinks();
        return linkRegistry.forRepository(repo);
    }
    public MirrorLinkRegistry.Link pair(String id){listLinks();return linkRegistry.find(id);}

    private GitHubAccountStore.Account accountForRepo(String repo){
        MirrorPolicy.requireRepository(repo);
        String owner=repo.substring(0,repo.indexOf('/'));
        for(GitHubAccountStore.Account a:accounts.listAccounts())
            if(owner.equalsIgnoreCase(a.login))return a;
        throw new IllegalStateException("Авторизуйте @"+owner+" в GitHubble для этой операции");
    }

    /**
     * Restore an existing independent clone without the original local OneShot journal.
     * Requires a shared, exactly matching Git commit SHA. It never edits either repository.
     */
    public MirrorLinkRegistry.Link recoverLink(String left,String right)throws Exception{
        MirrorPolicy.requireDifferent(left,right);
        GitHubAccountStore.Account a=accountForRepo(left),b=accountForRepo(right);
        if(a.id.equals(b.id))
            throw new IllegalArgumentException("Связывать можно только репозитории разных аккаунтов");
        GitHubApiClient src=api(a),dst=api(b);
        JSONObject r1=src.getJsonFresh(API+left),r2=dst.getJsonFresh(API+right);
        if(r1.optBoolean("fork")||r2.optBoolean("fork"))
            throw new IllegalArgumentException("Fork не принимается за независимое зеркало");
        java.util.HashSet<String> commits=new java.util.HashSet<>();
        for(int page=1;page<=5;page++){
            JSONArray one=src.getArray(API+left+"/commits?per_page=100&page="+page);
            for(int i=0;i<one.length();i++){
                JSONObject item=one.optJSONObject(i);
                if(item!=null)commits.add(item.optString("sha",""));
            }
            if(one.length()<100)break;
        }
        boolean shared=false;
        for(int page=1;page<=5 && !shared;page++){
            JSONArray two=dst.getArray(API+right+"/commits?per_page=100&page="+page);
            for(int i=0;i<two.length();i++){
                JSONObject item=two.optJSONObject(i);
                if(item!=null&&commits.contains(item.optString("sha",""))
                        &&item.optString("sha","").matches("[0-9a-f]{40}")){
                    shared=true;break;
                }
            }
            if(two.length()<100)break;
        }
        if(!shared)throw new IllegalStateException("В первых 500 коммитах не найдена общая "
                +"Git-история. Связь не создана: нужно отдельно проверить происхождение репозиториев.");
        return linkRegistry.remember(left,right,left,"");
    }

    public static final class PairState {
        public String from="",to="",fromBranch="",toBranch="",fromSha="",toSha="";
        public String relation="unknown";
        public boolean contentsDiffer;
    }

    private PairState inspect(GitHubApiClient source,GitHubApiClient target,
                              String sourceRepo,String targetRepo)throws Exception{
        JSONObject a=source.getJsonFresh(API+sourceRepo),b=target.getJsonFresh(API+targetRepo);
        if(a.optBoolean("fork")||b.optBoolean("fork"))
            throw new IllegalStateException("Связанная пара должна состоять из независимых репозиториев");
        PairState state=new PairState();
        state.from=sourceRepo;state.to=targetRepo;
        state.fromBranch=a.optString("default_branch","");
        state.toBranch=b.optString("default_branch","");
        requireBranch(state.fromBranch);requireBranch(state.toBranch);
        state.fromSha=head(source,sourceRepo,state.fromBranch);
        state.toSha=head(target,targetRepo,state.toBranch);
        String fromTree=source.getJsonFresh(API+sourceRepo+"/git/commits/"+state.fromSha)
                .getJSONObject("tree").optString("sha","");
        String toTree=target.getJsonFresh(API+targetRepo+"/git/commits/"+state.toSha)
                .getJSONObject("tree").optString("sha","");
        state.contentsDiffer=fromTree.isEmpty()||toTree.isEmpty()||!fromTree.equals(toTree);
        if(state.fromSha.equals(state.toSha)){state.relation="identical";return state;}
        try{
            JSONObject diff=source.getJsonFresh(API+sourceRepo+"/compare/"
                    +state.toSha+"..."+state.fromSha);
            state.relation=diff.optString("status","unknown");
        }catch(GitHubApiClient.ApiException e){
            if(e.statusCode==404||e.statusCode==422)state.relation="diverged";
            else throw e;
        }
        return state;
    }

    public PairState inspectLink(String linkId,String fromRepo)throws Exception{
        MirrorLinkRegistry.Link link=pair(linkId);
        if(link==null)throw new IllegalArgumentException("Связанная пара не найдена");
        String destination=link.partner(fromRepo);
        GitHubAccountStore.Account a=accountForRepo(fromRepo),b=accountForRepo(destination);
        return inspect(api(a),api(b),fromRepo,destination);
    }

    /**
     * Generic directionless transfer for any linked pair. If a safe FF sync is not possible,
     * only stage the source default branch for review through a draft PR on the destination.
     */
    public Job transferLinked(String linkId,String fromRepo)throws Exception{
        MirrorLinkRegistry.Link link=pair(linkId);
        if(link==null)throw new IllegalArgumentException("Связанная пара не найдена");
        String toRepo=link.partner(fromRepo);
        GitHubAccountStore.Account a=accountForRepo(fromRepo),b=accountForRepo(toRepo);
        GitHubApiClient source=api(a),target=api(b);
        for(Job previous:list()){
            if(!link.id.equals(previous.linkPairId)
                    &&!(fromRepo.equalsIgnoreCase(previous.source)
                         &&toRepo.equalsIgnoreCase(previous.destination)))continue;
            if(PREPARING.equals(previous.state)||DISPATCHED.equals(previous.state)
                    ||RUNNING.equals(previous.state)||UNKNOWN.equals(previous.state)
                    ||previous.cleanupPending)
                throw new IllegalStateException("В этой связанной паре ещё выполняется перенос или очистка");
            if(RETURN.equals(previous.mode)&&PR_READY.equals(previous.state)
                    &&previous.destination.equalsIgnoreCase(toRepo))
                throw new IllegalStateException("Для назначения уже открыт черновик Pull Request. "
                        +"Сначала проверьте или закройте предыдущий PR.");
        }
        PairState state=inspect(source,target,fromRepo,toRepo);
        if("identical".equals(state.relation)&&!state.contentsDiffer){
            // Tags and non-default branches may differ; a non-destructive sync verifies them.
        }else if(("behind".equals(state.relation)||"diverged".equals(state.relation)
                ||"unknown".equals(state.relation))&&!state.contentsDiffer){
            throw new IllegalStateException("В основной ветке нет изменений файлов для возврата. "
                    +"Проверьте остальные ветки вручную.");
        }
        boolean fastForward=("ahead".equals(state.relation)||"identical".equals(state.relation))
                &&state.fromBranch.equals(state.toBranch);
        if(!fastForward && !state.contentsDiffer)
            throw new IllegalStateException("Основные ветки различаются, а файлы одинаковы. "
                    +"Автоматическое объединение не требуется.");
        Job job=new Job();
        job.id=UUID.randomUUID().toString().replace("-","");
        job.requestId=job.id;job.mode=fastForward?SYNC:RETURN;
        job.linkPairId=link.id;job.linkedCopyId=link.copyJobId;
        job.source=fromRepo;job.destination=toRepo;
        job.sourceAccountId=a.id;job.destinationAccountId=b.id;
        job.sourceDefault=state.fromBranch;job.destinationDefault=state.toBranch;
        job.baselineSha=state.fromSha;job.targetBaselineSha=state.toSha;
        if(RETURN.equals(job.mode))job.reviewBranch="mirror-return/"+job.requestId;
        job.createdAt=System.currentTimeMillis();save(job);
        try{stageAndDispatch(source,job,b.id);return job;}
        catch(Exception e){handleStartFailure(source,job,e);throw e;}
    }

    /** Fast-forward already existing linked mirror. Does not create or overwrite another repo. */
    public Job updateMirror(String copyId)throws Exception {
        Job previous=find(copyId);
        if(previous==null || !COPY.equals(previous.mode) || !VERIFIED.equals(previous.state)
                || previous.cleanupPending)
            throw new IllegalStateException("Обновление доступно только для проверенного OneShot зеркала");
        for(Job existing:list())
            if(SYNC.equals(existing.mode) && copyId.equals(existing.linkedCopyId)
                    && (PREPARING.equals(existing.state)||DISPATCHED.equals(existing.state)
                        ||RUNNING.equals(existing.state)||UNKNOWN.equals(existing.state)
                        ||existing.cleanupPending))
                throw new IllegalStateException("Обновление этого зеркала уже выполняется");
        GitHubAccountStore.Account sa=account(previous.sourceAccountId);
        GitHubAccountStore.Account da=account(previous.destinationAccountId);
        requireOwner(sa,previous.source);requireOwner(da,previous.destination);
        GitHubApiClient source=api(sa),target=api(da);
        JSONObject sourceRepo=source.getJsonFresh(API+previous.source);
        JSONObject targetRepo=target.getJsonFresh(API+previous.destination);
        if(sourceRepo.optBoolean("fork")||targetRepo.optBoolean("fork"))
            throw new IllegalArgumentException("Нельзя обновлять Fork как независимое зеркало");
        String sourceBranch=sourceRepo.optString("default_branch","");
        String targetBranch=targetRepo.optString("default_branch","");
        requireBranch(sourceBranch);requireBranch(targetBranch);
        if(!sourceBranch.equals(targetBranch))
            throw new IllegalStateException("Основные ветки отличаются; автоматическое обновление отключено");
        Job job=new Job();
        job.id=UUID.randomUUID().toString().replace("-","");
        job.requestId=job.id;job.mode=SYNC;job.linkedCopyId=previous.id;
        job.source=previous.source;job.destination=previous.destination;
        job.sourceAccountId=sa.id;job.destinationAccountId=da.id;
        job.sourceDefault=sourceBranch;job.destinationDefault=targetBranch;
        job.baselineSha=head(source,job.source,sourceBranch);
        job.targetBaselineSha=head(target,job.destination,targetBranch);
        job.privateSource=sourceRepo.optBoolean("private",false);
        job.createdAt=System.currentTimeMillis();save(job);
        try{stageAndDispatch(source,job,da.id);return job;}
        catch(Exception error){handleStartFailure(source,job,error);throw error;}
    }

    /** Reverse changes by opening a draft pull request in the original repository.
     * Only an isolated review branch is pushed; destination main is never replaced.
     */
    public Job returnChanges(String copyId)throws Exception {
        Job previous=find(copyId);
        if(previous==null || !COPY.equals(previous.mode) || !VERIFIED.equals(previous.state)
                ||previous.cleanupPending)
            throw new IllegalStateException("Возврат доступен только для проверенного OneShot зеркала");
        for(Job existing:list())
            if(RETURN.equals(existing.mode) && copyId.equals(existing.linkedCopyId)
                    && (PREPARING.equals(existing.state)||DISPATCHED.equals(existing.state)
                        ||RUNNING.equals(existing.state)||UNKNOWN.equals(existing.state)
                        ||PR_READY.equals(existing.state)||existing.cleanupPending))
                throw new IllegalStateException("Для этого зеркала уже есть незакрытый возврат или ожидает очистка");
        GitHubAccountStore.Account sa=account(previous.destinationAccountId);
        GitHubAccountStore.Account da=account(previous.sourceAccountId);
        requireOwner(sa,previous.destination);requireOwner(da,previous.source);
        GitHubApiClient source=api(sa),target=api(da);
        JSONObject sourceRepo=source.getJsonFresh(API+previous.destination);
        JSONObject targetRepo=target.getJsonFresh(API+previous.source);
        if(sourceRepo.optBoolean("fork")||targetRepo.optBoolean("fork"))
            throw new IllegalArgumentException("Fork не поддерживается как независимое зеркало");
        String sourceBranch=sourceRepo.optString("default_branch","");
        String targetBranch=targetRepo.optString("default_branch","");
        requireBranch(sourceBranch);requireBranch(targetBranch);
        String srcSha=head(source,previous.destination,sourceBranch);
        String dstSha=head(target,previous.source,targetBranch);
        String srcTree=source.getJsonFresh(API+previous.destination+"/git/commits/"+srcSha)
                .getJSONObject("tree").optString("sha","");
        String dstTree=target.getJsonFresh(API+previous.source+"/git/commits/"+dstSha)
                .getJSONObject("tree").optString("sha","");
        if(srcTree.isEmpty()||dstTree.isEmpty())throw new SecurityException("Не удалось проверить дерево Git");
        if(srcTree.equals(dstTree))
            throw new IllegalStateException("Файлы обоих репозиториев совпадают. Возвращать пока нечего.");
        Job job=new Job();
        job.id=UUID.randomUUID().toString().replace("-","");
        job.requestId=job.id;job.mode=RETURN;job.linkedCopyId=previous.id;
        job.source=previous.destination;job.destination=previous.source;
        job.sourceAccountId=sa.id;job.destinationAccountId=da.id;
        job.sourceDefault=sourceBranch;job.destinationDefault=targetBranch;
        job.baselineSha=srcSha;job.targetBaselineSha=dstSha;
        job.reviewBranch="mirror-return/"+job.requestId;
        job.privateSource=sourceRepo.optBoolean("private",false);
        job.createdAt=System.currentTimeMillis();save(job);
        try{stageAndDispatch(source,job,da.id);return job;}
        catch(Exception error){handleStartFailure(source,job,error);throw error;}
    }

    private static void requireOwner(GitHubAccountStore.Account account,String repo){
        if(repo==null || !repo.contains("/")
                || !repo.substring(0,repo.indexOf('/')).equalsIgnoreCase(account.login))
            throw new SecurityException("Аккаунт репозитория изменён; повторите авторизацию");
    }
    private static void requireBranch(String branch){
        if(branch==null || !branch.matches("[A-Za-z0-9_./-]{1,250}")
                || branch.contains("..")||branch.startsWith("/")||branch.endsWith("/"))
            throw new IllegalArgumentException("Неподдерживаемое имя основной ветки");
    }
    private static String head(GitHubApiClient api,String repo,String branch)throws Exception{
        JSONObject ref=api.getJsonFresh(API+repo+"/git/ref/heads/"+enc(branch));
        JSONObject object=ref.optJSONObject("object");
        String sha=object==null?"":object.optString("sha","");
        if(!sha.matches("[0-9a-f]{40}"))throw new SecurityException("Некорректный Git commit SHA");
        return sha;
    }

    /** Shared only-by-request runner for first copy, safe fast-forward sync and return PR. */
    private void stageAndDispatch(GitHubApiClient source,Job job,String targetAccountId)throws Exception{
        String path=MirrorWorkflow.path(job.requestId);
        JSONObject file=new JSONObject()
                .put("message","chore(mirror): temporary GitHubble OneShot runner")
                .put("branch",job.sourceDefault)
                .put("content",Base64.getEncoder().encodeToString(
                        MirrorWorkflow.yaml().getBytes(StandardCharsets.UTF_8)));
        source.putJson(API+job.source+"/contents/"+path,file);
        job.workflowInstalled=true;save(job);
        MirrorSecrets.put(source,job.source,job.requestId,accounts.tokenForAccount(targetAccountId));
        job.secretInstalled=true;save(job);
        JSONObject dispatch=new JSONObject().put("ref",job.sourceDefault)
                .put("inputs",new JSONObject()
                        .put("destination",job.destination)
                        .put("source_default",job.sourceDefault)
                        .put("baseline_sha",job.baselineSha)
                        .put("request_id",job.requestId)
                        .put("operation",job.mode)
                        .put("destination_base",job.destinationDefault)
                        .put("target_sha",job.targetBaselineSha));
        source.executeText("POST",API+job.source+"/actions/workflows/"
                +enc(path.substring(path.lastIndexOf('/')+1))+"/dispatches",
                dispatch.toString(),true);
        job.state=DISPATCHED;
        job.runUrl="https://github.com/"+job.source+"/actions";
        save(job);
        enqueueRecovery(prefsContext(),job.id);
    }

    private void handleStartFailure(GitHubApiClient source,Job job,Exception error){
        job.error=safeError(error);
        job.state=error instanceof GitHubApiClient.MutationOutcomeUnknownException?UNKNOWN:FAILED;
        if(!UNKNOWN.equals(job.state)){
            try{cleanup(source,job);}
            catch(Exception problem){
                job.cleanupPending=true;
                job.error+="; очистка: "+safeError(problem);
            }
        }else job.cleanupPending=true;
        save(job);
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

    /** Create/recover a draft PR after the cloud runner has verified the review branch. */
    private void ensureReturnPullRequest(Job job)throws Exception {
        GitHubApiClient target=api(account(job.destinationAccountId));
        JSONObject ref=target.getJsonFresh(API+job.destination+"/git/ref/heads/"+job.reviewBranch);
        JSONObject object=ref.optJSONObject("object");
        if(object==null || !job.baselineSha.equals(object.optString("sha","")))
            throw new SecurityException("Ветка возврата отсутствует или её SHA изменился");
        String current=head(target,job.destination,job.destinationDefault);
        if(!current.equals(job.targetBaselineSha))
            throw new IllegalStateException("Основная ветка назначения изменилась после запуска. "
                    +"Ветка возврата сохранена, но PR не создан.");
        String owner=job.destination.substring(0,job.destination.indexOf('/'));
        JSONArray found=target.getArray(API+job.destination+"/pulls?state=all&head="
                +enc(owner+":"+job.reviewBranch)+"&per_page=100");
        JSONObject pr=null;
        for(int i=0;i<found.length();i++){
            JSONObject value=found.optJSONObject(i);if(value==null)continue;
            JSONObject h=value.optJSONObject("head"),base=value.optJSONObject("base");
            if(h!=null&&base!=null&&job.reviewBranch.equals(h.optString("ref",""))
                    &&job.destinationDefault.equals(base.optString("ref",""))){
                pr=value;break;
            }
        }
        if(pr==null){
            JSONObject request=new JSONObject()
                    .put("title","Вернуть изменения: "+job.source+" → "+job.destination)
                    .put("head",job.reviewBranch)
                    .put("base",job.destinationDefault)
                    .put("draft",true)
                    .put("body","GitHubble OneShot: безопасный обратный перенос.\n\n"
                            +"Источник: "+job.source+"\n"
                            +"Назначение: "+job.destination+"\n"
                            +"Snapshot: "+job.baselineSha+"\n\n"
                            +"Основная ветка назначения не перезаписывалась. "
                            +"Проверьте изменения и конфликты перед Merge.");
            pr=target.postJson(API+job.destination+"/pulls",request,true);
        }
        job.prNumber=pr.optLong("number");
        job.prUrl=pr.optString("html_url","");
        job.error="";
        updatePrFromData(job,pr);
        save(job);
    }

    private void updatePrFromData(Job job,JSONObject pr){
        if(!pr.isNull("merged_at"))job.state=PR_MERGED;
        else if("closed".equals(pr.optString("state","")))job.state=PR_CLOSED;
        else job.state=PR_READY;
    }

    private Job refreshPullRequest(Job job)throws Exception {
        if(job.prNumber<=0)return job;
        GitHubApiClient target=api(account(job.destinationAccountId));
        JSONObject pr=target.getJsonFresh(API+job.destination+"/pulls/"+job.prNumber);
        updatePrFromData(job,pr);
        job.prUrl=pr.optString("html_url",job.prUrl);
        save(job);
        return job;
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
        if(RETURN.equals(job.mode) && job.prNumber>0 && !job.cleanupPending)
            return refreshPullRequest(job);
        if((VERIFIED.equals(job.state)||PR_MERGED.equals(job.state)||PR_CLOSED.equals(job.state))
                &&!job.cleanupPending)return job;
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
                if(RETURN.equals(job.mode)){
                    ensureReturnPullRequest(job);
                }else{
                GitHubApiClient dst=api(account(job.destinationAccountId));
                Map<String,String> srcRefs=refs(source,job.source);
                srcRefs.put("refs/heads/"+job.sourceDefault,job.baselineSha);
                Map<String,String> dstRefs=refs(dst,job.destination);
                boolean matches=!srcRefs.isEmpty();
                for(Map.Entry<String,String> ref:srcRefs.entrySet())
                    if(!ref.getValue().equals(dstRefs.get(ref.getKey())))matches=false;
                if(COPY.equals(job.mode)&&srcRefs.size()!=dstRefs.size())matches=false;
                if(!matches){
                    job.state=DIVERGED;job.error="SHA веток/тегов источника не совпадают; см. Actions";
                }else{
                    job.state=VERIFIED;job.error="";
                    String actualDefault=dst.getJsonFresh(API+job.destination).optString("default_branch","");
                    if(!job.sourceDefault.equals(actualDefault)){
                        dst.patch(API+job.destination,new JSONObject().put("default_branch",job.sourceDefault));
                    }
                }
                }
            }catch(Exception e){
                job.state=UNKNOWN;job.error="Проверка и подготовка PR: "+safeError(e);
            }
        }
        if(COPY.equals(job.mode)&&VERIFIED.equals(job.state)){
            // Persistent bidirectional pair survives independent OneShot journal pruning.
            linkRegistry.remember(job.source,job.destination,job.source,job.id);
        }
        job.cleanupPending=job.secretInstalled||job.workflowInstalled;
        save(job);
        try{cleanup(source,job);}
        catch(Exception e){job.cleanupPending=true;job.error+="; очистка: "+safeError(e);save(job);}
        return job;
    }

    /** Public compatibility entrypoint: safely update an existing linked mirror. */
    public Job sync(String copyId)throws Exception{
        return updateMirror(copyId);
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
