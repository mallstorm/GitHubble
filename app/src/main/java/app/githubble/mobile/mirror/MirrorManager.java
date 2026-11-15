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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cloud-only Git mirror: the phone sends REST commands; Git/LFS bytes stay on a GitHub runner.
 * Accounts are pinned per operation. Never use an active-account switch to borrow credentials.
 * Public repository transfers are implemented. Private repositories require a separately
 * approved secure runner credential exchange; never put an OAuth token in workflow inputs.
 */
public final class MirrorManager {
    public static final String COPY="copy", MANAGED="managed";
    public static final String PREPARING="preparing", DEST_CREATED="destination_created",
            DISPATCHED="dispatched", RUNNING="running", VERIFIED="verified",
            FAILED="failed", DIVERGED="diverged", UNKNOWN="unknown";
    private static final String PREFS="githubble_mirror_manager";
    private static final String KEY_JOBS="jobs";
    private static final String API="https://api.github.com/repos/";
    private final GitHubAccountStore accounts;
    private final SharedPreferences prefs;
    private final Object lock=new Object();

    public MirrorManager(Context context,GitHubAccountStore accounts) {
        this.accounts=accounts;
        this.prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    public static final class Job {
        public String id="",requestId="",source="",destination="",sourceAccountId="",destinationAccountId="";
        public String sourceDefault="",mode=COPY,state=PREPARING,error="",runUrl="";
        public long runId,createdAt,updatedAt;
        public boolean isManaged(){return MANAGED.equals(mode);}
        public JSONObject json(){
            JSONObject j=new JSONObject();
            try {
                j.put("id",id);j.put("requestId",requestId);j.put("source",source);j.put("destination",destination);
                j.put("sourceAccountId",sourceAccountId);
                j.put("destinationAccountId",destinationAccountId);
                j.put("sourceDefault",sourceDefault);j.put("mode",mode);
                j.put("state",state);j.put("error",error);j.put("runUrl",runUrl);
                j.put("runId",runId);j.put("createdAt",createdAt);j.put("updatedAt",updatedAt);
            }catch(Exception ignored){}
            return j;
        }
        public static Job fromJson(JSONObject j){
            Job r=new Job();if(j==null)return r;
            r.id=j.optString("id","");r.requestId=j.optString("requestId",r.id);r.source=j.optString("source","");
            r.destination=j.optString("destination","");
            r.sourceAccountId=j.optString("sourceAccountId","");
            r.destinationAccountId=j.optString("destinationAccountId","");
            r.sourceDefault=j.optString("sourceDefault","main");
            r.mode=j.optString("mode",COPY);r.state=j.optString("state",PREPARING);
            r.error=j.optString("error","");r.runUrl=j.optString("runUrl","");
            r.runId=j.optLong("runId");r.createdAt=j.optLong("createdAt");
            r.updatedAt=j.optLong("updatedAt");return r;
        }
    }

    public List<Job> list(){
        synchronized(lock) {
            List<Job> jobs=new ArrayList<>();
            try {
                JSONArray arr=new JSONArray(prefs.getString(KEY_JOBS,"[]"));
                for(int i=0;i<arr.length();i++){
                    JSONObject j=arr.optJSONObject(i);
                    if(j!=null)jobs.add(Job.fromJson(j));
                }
            }catch(Exception ignored){}
            jobs.sort((a,b)->Long.compare(b.updatedAt,a.updatedAt));
            return jobs;
        }
    }
    public Job find(String id){
        for(Job job:list())if(job.id.equals(id))return job;
        return null;
    }
    private void save(Job job){
        synchronized(lock) {
            List<Job> all=list();boolean replaced=false;
            for(int i=0;i<all.size();i++)if(all.get(i).id.equals(job.id)){
                all.set(i,job);replaced=true;break;
            }
            if(!replaced)all.add(job);
            job.updatedAt=System.currentTimeMillis();
            JSONArray arr=new JSONArray();
            for(Job item:all)arr.put(item.json());
            if(!prefs.edit().putString(KEY_JOBS,arr.toString()).commit())
                throw new IllegalStateException("Не удалось сохранить план зеркалирования");
        }
    }

    private GitHubAccountStore.Account account(String id) {
        for(GitHubAccountStore.Account a:accounts.listAccounts())
            if(a.id.equals(id))return a;
        throw new IllegalArgumentException("Аккаунт GitHub больше не подключён");
    }

    private static GitHubApiClient client(String token,String owner){
        if(token==null||token.isEmpty())
            throw new IllegalStateException("Срок действия входа GitHub истёк — переподключите аккаунт");
        final GitHubSession session=new GitHubSession(token,owner,0L);
        return new GitHubApiClient(new GitHubSession.Source(){
            public GitHubSession capture(){return session;}
            public boolean isCurrent(GitHubSession s){return s==session;}
            public void reauthorization(GitHubSession s,boolean required){}
        },null);
    }

    private GitHubApiClient forAccount(GitHubAccountStore.Account account)throws Exception {
        GitHubApiClient client=client(accounts.tokenForAccount(account.id),account.login);
        JSONObject identity=client.getJsonFresh("https://api.github.com/user");
        if(!account.login.equalsIgnoreCase(identity.optString("login","")))
            throw new SecurityException("GitHub выдал сессию не того аккаунта: "+account.login);
        return client;
    }

    public Job start(String sourceFull,String sourceAccountId,String destinationAccountId,
                     String destinationName,boolean managed)throws Exception {
        MirrorPolicy.requireRepository(sourceFull);
        MirrorPolicy.requireName(destinationName);
        GitHubAccountStore.Account sourceAccount=account(sourceAccountId);
        GitHubAccountStore.Account targetAccount=account(destinationAccountId);
        if(sourceAccount.id.equals(targetAccount.id))
            throw new IllegalArgumentException("Выберите другой GitHub-аккаунт");
        if(!sourceFull.substring(0,sourceFull.indexOf('/')).equalsIgnoreCase(sourceAccount.login))
            throw new IllegalArgumentException("Для зеркала выберите собственный репозиторий исходного аккаунта");
        String destination=MirrorPolicy.requireDifferent(sourceFull,targetAccount.login+"/"+destinationName);
        for(Job old:list())if(old.destination.equalsIgnoreCase(destination))
            throw new IllegalStateException("Для этого назначения уже есть план. Откройте его для синхронизации");

        GitHubApiClient src=forAccount(sourceAccount);
        GitHubApiClient dst=forAccount(targetAccount);
        JSONObject repository=src.getJsonFresh(API+sourceFull);
        if(repository.optBoolean("private",false))
            throw new IllegalStateException("Приватный источник: безопасная передача токена на runner пока не настроена. Создание остановлено без передачи секретов.");
        String sourceDefault=repository.optString("default_branch","main");
        if(!sourceDefault.matches("[A-Za-z0-9_.\\/-]{1,250}") || sourceDefault.contains(".."))
            throw new IllegalStateException("Недопустимая default branch");
        // An empty source cannot replace the target default branch safely.
        src.getJsonFresh(API+sourceFull+"/git/ref/heads/"+encode(sourceDefault));
        try {
            dst.getJsonFresh(API+destination);
            throw new IllegalStateException("Назначение уже существует. Существующие репозитории никогда не перезаписываются при первичном копировании.");
        }catch(GitHubApiClient.ApiException notFound){
            if(notFound.statusCode!=404)throw notFound;
        }

        Job job=new Job();
        job.id=UUID.randomUUID().toString().replace("-","");
        job.requestId=job.id;
        job.createdAt=System.currentTimeMillis();
        job.source=sourceFull;job.sourceAccountId=sourceAccountId;
        job.destination=destination;job.destinationAccountId=destinationAccountId;
        job.sourceDefault=sourceDefault;job.mode=managed?MANAGED:COPY;
        save(job);
        try {
            JSONObject payload=new JSONObject();
            payload.put("name",destinationName).put("description","GitHubble mirror of "+sourceFull);
            payload.put("auto_init",true).put("private",false);
            JSONObject created=dst.postJson("https://api.github.com/user/repos",payload,true);
            if(!destination.equalsIgnoreCase(created.optString("full_name","")))
                throw new SecurityException("GitHub создал неожиданный репозиторий — проверьте назначения");
            job.state=DEST_CREATED;save(job);
            configureDefaultBranch(dst,job,created);
            dispatch(dst,job);
            return job;
        }catch(Exception error){
            failed(job,error);throw error;
        }
    }

    private static String encode(String value){
        try {return URLEncoder.encode(value,"UTF-8").replace("+","%20");}
        catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}
    }

    /** Change initial GitHub-generated default branch before the first mirror push. */
    private void configureDefaultBranch(GitHubApiClient dest,Job job,JSONObject created)throws Exception {
        String original=created.optString("default_branch","main");
        if(original.equals(job.sourceDefault))return;
        JSONObject ref=dest.getJsonFresh(API+job.destination+"/git/ref/heads/"+encode(original));
        String sha=ref.getJSONObject("object").getString("sha");
        JSONObject create=new JSONObject().put("ref","refs/heads/"+job.sourceDefault).put("sha",sha);
        dest.postJson(API+job.destination+"/git/refs",create,true);
        dest.patch(API+job.destination,new JSONObject().put("default_branch",job.sourceDefault));
        // Delete only the automatically generated bootstrap ref; this is a NEW repository.
        dest.delete(API+job.destination+"/git/refs/heads/"+encode(original));
    }

    private void dispatch(GitHubApiClient destination,Job job)throws Exception {
        String url=API+job.destination+"/contents/"+MirrorPolicy.WORKFLOW_PATH;
        JSONObject payload=new JSONObject().put("message","chore: prepare temporary GitHubble mirror runner")
                .put("branch",job.sourceDefault)
                .put("content",Base64.getEncoder().encodeToString(
                        MirrorWorkflow.yaml().getBytes(StandardCharsets.UTF_8)));
        try{
            JSONObject existing=destination.getJsonFresh(url+"?ref="+encode(job.sourceDefault));
            String sha=existing.optString("sha","");
            if(!sha.isEmpty())payload.put("sha",sha);
        }catch(GitHubApiClient.ApiException missing){
            if(missing.statusCode!=404)throw missing;
        }
        destination.putJson(url,payload);
        JSONObject input=new JSONObject()
                .put("ref",job.sourceDefault)
                .put("inputs",new JSONObject().put("source",job.source).put("request_id",job.requestId));
        // Dispatch returns HTTP 204; never send GitHub user tokens as inputs.
        destination.executeText("POST",API+job.destination+
                "/actions/workflows/githubble-mirror.yml/dispatches",input.toString(),true);
        job.state=DISPATCHED;job.error="";job.runId=0L;job.runUrl="";
        save(job);
    }

    /** Explicit, user-confirmed update. Target may be rewritten and deleted refs are pruned. */
    public Job sync(String jobId)throws Exception {
        Job job=find(jobId);
        if(job==null)throw new IllegalArgumentException("План не найден");
        if(!job.isManaged())throw new IllegalStateException("Разовая копия не имеет режима повторного зеркалирования");
        if(PREPARING.equals(job.state)||DEST_CREATED.equals(job.state)
                ||DISPATCHED.equals(job.state)||RUNNING.equals(job.state)
                ||UNKNOWN.equals(job.state))
            throw new IllegalStateException("Предыдущая операция не завершена или требует проверки");
        GitHubAccountStore.Account sourceAccount=account(job.sourceAccountId);
        GitHubAccountStore.Account destinationAccount=account(job.destinationAccountId);
        GitHubApiClient src=forAccount(sourceAccount),dst=forAccount(destinationAccount);
        JSONObject source=src.getJsonFresh(API+job.source);
        if(source.optBoolean("private",false))
            throw new IllegalStateException("Приватные репозитории требуют безопасного облачного канала");
        dst.getJsonFresh(API+job.destination);
        String currentDefault=source.optString("default_branch","main");
        if(!currentDefault.equals(job.sourceDefault))
            throw new IllegalStateException("Default branch изменилась. Перепривязка плана нужна перед синхронизацией");
        job.requestId=UUID.randomUUID().toString().replace("-","");
        // Mirror identity remains stable across synchronization runs.
        job.state=PREPARING;job.runId=0;job.error="";job.runUrl="";save(job);
        try{dispatch(dst,job);return job;}
        catch(Exception error){failed(job,error);throw error;}
    }

    public Job refresh(String id)throws Exception {
        Job job=find(id);if(job==null)throw new IllegalArgumentException("План не найден");
        if(VERIFIED.equals(job.state)||FAILED.equals(job.state)||DIVERGED.equals(job.state))
            return job;
        GitHubApiClient destination=forAccount(account(job.destinationAccountId));
        JSONObject root=destination.getJsonFresh(API+job.destination+"/actions/runs?event=workflow_dispatch&per_page=100");
        JSONArray runs=root.optJSONArray("workflow_runs");
        JSONObject found=null;
        for(int i=0;runs!=null&&i<runs.length();i++){
            JSONObject run=runs.optJSONObject(i);
            if(run!=null&&run.optString("display_title","").contains(job.requestId)){found=run;break;}
        }
        if(found==null)return job;
        job.runId=found.optLong("id");
        job.runUrl=found.optString("html_url","");
        String status=found.optString("status","");
        if(!"completed".equals(status)){
            job.state=RUNNING;save(job);return job;
        }
        if(!"success".equals(found.optString("conclusion",""))){
            job.state=FAILED;job.error="GitHub Actions: "+found.optString("conclusion","failure");
            save(job);return job;
        }
        try{
            GitHubApiClient source=forAccount(account(job.sourceAccountId));
            if(!refs(source,job.source).equals(refs(destination,job.destination))){
                job.state=DIVERGED;
                job.error="Ветки или теги различаются: зеркало нельзя считать завершённым";
            } else {
                job.state=VERIFIED;job.error="";
            }
        }catch(Exception error){
            job.state=UNKNOWN;job.error="Невозможно проверить refs: "+safeError(error);
        }
        save(job);return job;
    }

    private static Map<String,String> refs(GitHubApiClient api,String repo)throws Exception{
        Map<String,String> all=new LinkedHashMap<>();
        for(String kind:new String[]{"heads","tags"}){
            for(int page=1;page<=30;page++){
                JSONArray items=api.getArray(API+repo+"/git/matching-refs/"+kind
                        +"?per_page=100&page="+page);
                for(int i=0;i<items.length();i++){
                    JSONObject item=items.optJSONObject(i);
                    if(item==null)continue;
                    String ref=item.optString("ref","");
                    JSONObject obj=item.optJSONObject("object");
                    if(obj!=null&&ref.startsWith("refs/"+kind+"/"))
                        all.put(ref,obj.optString("sha",""));
                }
                if(items.length()<100)break;
                if(page==30)throw new IllegalStateException("Количество Git-ссылок превышает лимит проверки");
            }
        }
        return all;
    }

    private static String safeError(Exception e){
        String msg=e.getMessage();if(msg==null||msg.isEmpty())return e.getClass().getSimpleName();
        return msg.length()>240?msg.substring(0,240):msg;
    }
    private void failed(Job job,Exception error){
        job.state=error instanceof GitHubApiClient.MutationOutcomeUnknownException?UNKNOWN:FAILED;
        job.error=safeError(error);save(job);
    }
}
