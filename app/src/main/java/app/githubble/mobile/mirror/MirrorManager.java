package app.githubble.mobile.mirror;

import android.content.Context;
import android.content.SharedPreferences;
import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubSession;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Independent Git mirror controller. Two separate user sessions authorize setup; on the hosted
 * runner only restricted GitHub App installation tokens are minted from a PRIVATE Actions
 * repository secret. Neither token nor private key enters workflow_dispatch or app preferences.
 */
public final class MirrorManager {
    public static final String COPY="copy",MANAGED="managed",FORK="fork";
    public static final String PREPARING="preparing",DEST_CREATED="destination_created",
            DISPATCHED="dispatched",RUNNING="running",VERIFIED="verified",
            FAILED="failed",DIVERGED="diverged",UNKNOWN="unknown";
    public static final String CONTROLLER="mallstorm/"+MirrorWorkflow.CONTROLLER_NAME;
    public static final String CONTROLLER_URL="https://github.com/"+CONTROLLER;
    private static final String API="https://api.github.com/repos/";
    private static final String PREFS="githubble_mirror_manager", KEY_JOBS="jobs";
    private final GitHubAccountStore accounts;
    private final SharedPreferences prefs;
    private final Object lock=new Object();

    public MirrorManager(Context context,GitHubAccountStore accounts) {
        this.accounts=accounts;
        this.prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    public static final class Job {
        public String id="",requestId="",source="",destination="",sourceAccountId="",destinationAccountId="";
        public String sourceDefault="",mode=COPY,state=PREPARING,error="",runUrl="",targetDigest="";
        public boolean privateSource;
        public long runId,createdAt,updatedAt;
        public boolean isManaged(){return MANAGED.equals(mode);}
        public JSONObject json(){
            JSONObject j=new JSONObject();
            try {
                j.put("id",id);j.put("requestId",requestId);
                j.put("source",source);j.put("destination",destination);
                j.put("sourceAccountId",sourceAccountId);j.put("destinationAccountId",destinationAccountId);
                j.put("sourceDefault",sourceDefault);j.put("mode",mode);j.put("state",state);
                j.put("error",error);j.put("runUrl",runUrl);j.put("targetDigest",targetDigest);
                j.put("privateSource",privateSource);j.put("runId",runId);
                j.put("createdAt",createdAt);j.put("updatedAt",updatedAt);
            }catch(Exception ignored){}
            return j;
        }
        public static Job fromJson(JSONObject j){
            Job x=new Job();if(j==null)return x;
            x.id=j.optString("id","");x.requestId=j.optString("requestId",x.id);
            x.source=j.optString("source","");x.destination=j.optString("destination","");
            x.sourceAccountId=j.optString("sourceAccountId","");
            x.destinationAccountId=j.optString("destinationAccountId","");
            x.sourceDefault=j.optString("sourceDefault","main");
            x.mode=j.optString("mode",COPY);x.state=j.optString("state",PREPARING);
            x.error=j.optString("error","");x.runUrl=j.optString("runUrl","");
            x.targetDigest=j.optString("targetDigest","");
            x.privateSource=j.optBoolean("privateSource");
            x.runId=j.optLong("runId");x.createdAt=j.optLong("createdAt");
            x.updatedAt=j.optLong("updatedAt");return x;
        }
    }

    public List<Job> list(){
        synchronized(lock){
            List<Job> out=new ArrayList<>();
            try{
                JSONArray a=new JSONArray(prefs.getString(KEY_JOBS,"[]"));
                for(int i=0;i<a.length();i++){
                    JSONObject j=a.optJSONObject(i);if(j!=null)out.add(Job.fromJson(j));
                }
            }catch(Exception ignored){}
            out.sort((a,b)->Long.compare(b.updatedAt,a.updatedAt));
            return out;
        }
    }
    public Job find(String id){for(Job j:list())if(j.id.equals(id))return j;return null;}
    private void save(Job job){
        synchronized(lock){
            List<Job> all=list();boolean found=false;
            for(int i=0;i<all.size();i++)if(all.get(i).id.equals(job.id)){
                all.set(i,job);found=true;break;
            }
            if(!found)all.add(job);
            job.updatedAt=System.currentTimeMillis();
            JSONArray json=new JSONArray();for(Job j:all)json.put(j.json());
            if(!prefs.edit().putString(KEY_JOBS,json.toString()).commit())
                throw new IllegalStateException("Не удалось сохранить журнал зеркал");
        }
    }

    private GitHubAccountStore.Account account(String id){
        for(GitHubAccountStore.Account a:accounts.listAccounts())if(a.id.equals(id))return a;
        throw new IllegalArgumentException("Аккаунт GitHub удалён или не подключён");
    }
    private GitHubAccountStore.Account controllerAccount(){
        for(GitHubAccountStore.Account a:accounts.listAccounts())
            if("mallstorm".equalsIgnoreCase(a.login))return a;
        throw new IllegalStateException("Для управления доверенным runner подключите @mallstorm");
    }
    private static GitHubApiClient client(String token,String login){
        if(token==null||token.isEmpty())
            throw new IllegalStateException("Нужна повторная авторизация GitHub: "+login);
        final GitHubSession session=new GitHubSession(token,login,0);
        return new GitHubApiClient(new GitHubSession.Source(){
            public GitHubSession capture(){return session;}
            public boolean isCurrent(GitHubSession requested){return requested==session;}
            public void reauthorization(GitHubSession requested,boolean required){}
        },null);
    }
    private GitHubApiClient api(GitHubAccountStore.Account a)throws Exception{
        GitHubApiClient out=client(accounts.tokenForAccount(a.id),a.login);
        JSONObject identity=out.getJsonFresh("https://api.github.com/user");
        if(!a.login.equalsIgnoreCase(identity.optString("login","")))
            throw new SecurityException("Токен принадлежит другому пользователю, не @"+a.login);
        return out;
    }
    private static String enc(String text){
        try{return URLEncoder.encode(text,"UTF-8").replace("+","%20");}
        catch(UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}
    }

    /**
     * Explicit user action. Creates or upgrades only the fixed, PRIVATE trusted controller repo.
     * No GitHub App keys are handled here: they must be configured in GitHub's repository secrets UI.
     */
    public String setupController()throws Exception{
        GitHubApiClient api=api(controllerAccount());
        JSONObject control;
        try{control=api.getJsonFresh(API+CONTROLLER);}
        catch(GitHubApiClient.ApiException error){
            if(error.statusCode!=404)throw error;
            JSONObject payload=new JSONObject()
                    .put("name",MirrorWorkflow.CONTROLLER_NAME)
                    .put("description","GitHubble Secure Mirror Control (private runner)")
                    .put("private",true).put("auto_init",true);
            control=api.postJson("https://api.github.com/user/repos",payload,true);
        }
        if(!CONTROLLER.equalsIgnoreCase(control.optString("full_name",""))
                ||!control.optBoolean("private")||control.optBoolean("fork"))
            throw new SecurityException("Контроллер должен быть отдельным приватным репозиторием @mallstorm");
        String branch=control.optString("default_branch","main");
        if(branch.isEmpty())throw new IllegalStateException("У контроллера ещё нет default branch");
        String url=API+CONTROLLER+"/contents/"+MirrorWorkflow.WORKFLOW_PATH;
        JSONObject payload=new JSONObject().put("message","feat: provision secure GitHubble Mirror controller")
                .put("branch",branch)
                .put("content",Base64.getEncoder().encodeToString(
                        MirrorWorkflow.yaml().getBytes(StandardCharsets.UTF_8)));
        try{
            JSONObject existing=api.getJsonFresh(url+"?ref="+enc(branch));
            String sha=existing.optString("sha","");
            if(!sha.isEmpty()){
                String raw=existing.optString("content","").replaceAll("\\s+","");
                String current=new String(Base64.getDecoder().decode(raw),StandardCharsets.UTF_8);
                if(current.equals(MirrorWorkflow.yaml()))return CONTROLLER_URL;
                payload.put("sha",sha);
            }
        }catch(GitHubApiClient.ApiException missing){
            if(missing.statusCode!=404)throw missing;
        }
        api.putJson(url,payload);
        return CONTROLLER_URL;
    }

    private GitHubApiClient requireController()throws Exception{
        GitHubApiClient api=api(controllerAccount());
        JSONObject repo=api.getJsonFresh(API+CONTROLLER);
        if(!repo.optBoolean("private")||repo.optBoolean("fork"))
            throw new SecurityException("Runner должен находиться в приватном независимом репозитории");
        JSONObject workflow=api.getJsonFresh(API+CONTROLLER+"/actions/workflows/"
                +enc("githubble-mirror-controller.yml"));
        if(!"active".equals(workflow.optString("state","")))
            throw new IllegalStateException("Контроллер не активен: откройте Mirror Manager → Настроить контроллер");
        return api;
    }

    /** Create a new independent destination repository, then dispatch the trusted cloud runner. */
    public Job start(String sourceFull,String sourceAccountId,String destAccountId,
                     String destName,boolean managed)throws Exception{
        MirrorPolicy.requireRepository(sourceFull);MirrorPolicy.requireName(destName);
        GitHubAccountStore.Account sa=account(sourceAccountId),da=account(destAccountId);
        if(sa.id.equals(da.id)||sa.login.equalsIgnoreCase(da.login))
            throw new IllegalArgumentException("Исходный и целевой аккаунты должны отличаться");
        if(!sourceFull.substring(0,sourceFull.indexOf('/')).equalsIgnoreCase(sa.login))
            throw new IllegalArgumentException("Источник должен принадлежать выбранному аккаунту");
        String dest=MirrorPolicy.requireDifferent(sourceFull,da.login+"/"+destName);
        for(Job previous:list())if(previous.destination.equalsIgnoreCase(dest))
            throw new IllegalStateException("Этот репозиторий уже зарегистрирован в Mirror Manager");
        GitHubApiClient controller=requireController();
        GitHubApiClient source=api(sa),target=api(da);
        JSONObject sourceRepo=source.getJsonFresh(API+sourceFull);
        String defaultBranch=sourceRepo.optString("default_branch","main");
        if(defaultBranch.isEmpty()||defaultBranch.length()>250||defaultBranch.contains("..")
                ||!defaultBranch.matches("[A-Za-z0-9_./-]+"))
            throw new IllegalStateException("Неподдерживаемое имя default branch");
        source.getJsonFresh(API+sourceFull+"/git/ref/heads/"+enc(defaultBranch));
        try{
            target.getJsonFresh(API+dest);
            throw new IllegalStateException("Назначение уже существует — первичное зеркало никогда не перезаписывает чужой проект");
        }catch(GitHubApiClient.ApiException e){if(e.statusCode!=404)throw e;}

        Job job=new Job();job.id=UUID.randomUUID().toString().replace("-","");
        job.requestId=job.id;job.source=sourceFull;job.destination=dest;
        job.sourceAccountId=sa.id;job.destinationAccountId=da.id;
        job.sourceDefault=defaultBranch;job.mode=managed?MANAGED:COPY;
        job.privateSource=sourceRepo.optBoolean("private");
        job.createdAt=System.currentTimeMillis();save(job);
        try{
            JSONObject created=target.postJson("https://api.github.com/user/repos",
                    new JSONObject().put("name",destName).put("private",job.privateSource)
                            .put("auto_init",false)
                            .put("description","Independent Git mirror of "+sourceFull),true);
            if(!dest.equalsIgnoreCase(created.optString("full_name",""))
                    ||created.optBoolean("fork"))
                throw new SecurityException("GitHub вернул неверное или связанное с Fork назначение");
            job.state=DEST_CREATED;save(job);
            dispatch(controller,job,"copy","EMPTY");
            return job;
        }catch(Exception error){
            failure(job,error);throw error;
        }
    }

    private void dispatch(GitHubApiClient controller,Job job,String mode,String expected)throws Exception{
        String controllerBranch=controller.getJsonFresh(API+CONTROLLER)
                .optString("default_branch","main");
        JSONObject payload=new JSONObject().put("ref",controllerBranch)
                .put("inputs",new JSONObject()
                        .put("source",job.source).put("target",job.destination)
                        .put("mode",mode).put("expected_target",expected)
                        .put("request_id",job.requestId));
        controller.executeText("POST",API+CONTROLLER+
                "/actions/workflows/githubble-mirror-controller.yml/dispatches",
                payload.toString(),true);
        job.state=DISPATCHED;job.runId=0;job.runUrl="";job.error="";save(job);
    }

    /** Re-checks an interrupted initial operation; never silently overwrites nonempty targets. */
    public Job retry(String id)throws Exception{
        Job job=find(id);
        if(job==null)throw new IllegalArgumentException("План не найден");
        if(FORK.equals(job.mode))throw new IllegalStateException("Старый Fork не поддерживает независимое зеркалирование");
        if(!(FAILED.equals(job.state)||DEST_CREATED.equals(job.state)))
            throw new IllegalStateException("Повтор доступен только для остановленной первичной операции");
        GitHubApiClient dst=api(account(job.destinationAccountId));
        if(!refs(dst,job.destination).isEmpty())
            throw new IllegalStateException("Назначение уже содержит Git refs. Автоматический повтор заблокирован для защиты данных");
        GitHubApiClient src=api(account(job.sourceAccountId));
        src.getJsonFresh(API+job.source);
        job.requestId=UUID.randomUUID().toString().replace("-","");
        job.state=PREPARING;save(job);
        try{dispatch(requireController(),job,"copy","EMPTY");return job;}
        catch(Exception error){failure(job,error);throw error;}
    }

    /** Explicit, conflict-checked re-mirroring. No force push if target changed independently. */
    public Job sync(String id)throws Exception{
        Job job=find(id);
        if(job==null)throw new IllegalArgumentException("План не найден");
        if(!job.isManaged()||!VERIFIED.equals(job.state))
            throw new IllegalStateException("Обновлять можно только проверенное управляемое зеркало");
        if(!job.targetDigest.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Не сохранена контрольная сумма предыдущего зеркала");
        GitHubApiClient src=api(account(job.sourceAccountId)),dst=api(account(job.destinationAccountId));
        JSONObject source=src.getJsonFresh(API+job.source);
        JSONObject target=dst.getJsonFresh(API+job.destination);
        if(target.optBoolean("fork"))
            throw new IllegalStateException("Fork нельзя использовать как независимое зеркало");
        if(!source.optString("default_branch","main").equals(job.sourceDefault))
            throw new IllegalStateException("Default branch изменилась — требуется новая операция");
        if(!digest(refs(dst,job.destination)).equals(job.targetDigest))
            throw new IllegalStateException("Назначение изменено независимо — синхронизация отменена без перезаписи");
        job.requestId=UUID.randomUUID().toString().replace("-","");
        job.state=PREPARING;save(job);
        try{dispatch(requireController(),job,"sync",job.targetDigest);return job;}
        catch(Exception error){failure(job,error);throw error;}
    }

    /** Query the private controller's run ledger, then verify remote SHA values on BOTH accounts. */
    public Job refresh(String id)throws Exception{
        Job job=find(id);if(job==null)throw new IllegalArgumentException("План не найден");
        if(FORK.equals(job.mode))throw new IllegalStateException("Устаревшая операция Fork, не зеркало");
        if(VERIFIED.equals(job.state))return job;
        GitHubApiClient controller=requireController();
        JSONObject root=controller.getJsonFresh(API+CONTROLLER+
                "/actions/workflows/githubble-mirror-controller.yml/runs?event=workflow_dispatch&per_page=100");
        JSONArray runs=root.optJSONArray("workflow_runs");
        JSONObject found=null;
        for(int i=0;runs!=null&&i<runs.length();i++){
            JSONObject item=runs.optJSONObject(i);
            if(item!=null&&item.optString("display_title","").contains(job.requestId)){
                found=item;break;
            }
        }
        if(found==null){
            if(System.currentTimeMillis()-job.updatedAt>10L*60_000L){
                job.state=UNKNOWN;job.error="Не удалось найти запуск по идентификатору: проверьте private Actions";
                save(job);
            }
            return job;
        }
        job.runId=found.optLong("id");job.runUrl=found.optString("html_url","");
        if(!"completed".equals(found.optString("status",""))){
            job.state=RUNNING;save(job);return job;
        }
        if(!"success".equals(found.optString("conclusion",""))){
            job.state=FAILED;job.error="Runner: "+found.optString("conclusion","failure");
            save(job);return job;
        }
        try{
            GitHubApiClient source=api(account(job.sourceAccountId));
            GitHubApiClient target=api(account(job.destinationAccountId));
            Map<String,String> a=refs(source,job.source),b=refs(target,job.destination);
            if(a.isEmpty()||!a.equals(b)){
                job.state=DIVERGED;
                job.error="Ветки и теги различаются. Завершение нельзя подтвердить";
            }else{
                job.targetDigest=digest(b);
                job.state=VERIFIED;job.error="";
                String actualDefault=target.getJsonFresh(API+job.destination)
                        .optString("default_branch","");
                if(!job.sourceDefault.equals(actualDefault)){
                    try{target.patch(API+job.destination,
                            new JSONObject().put("default_branch",job.sourceDefault));}
                    catch(Exception error){
                        job.state=DIVERGED;
                        job.error="Git refs совпали, но default branch не удалось установить";
                    }
                }
            }
        }catch(Exception error){
            job.state=UNKNOWN;job.error="Проверка Git refs: "+safeError(error);
        }
        save(job);return job;
    }

    private static Map<String,String> refs(GitHubApiClient api,String repository)throws Exception{
        Map<String,String> out=new LinkedHashMap<>();
        for(String kind:new String[]{"heads","tags"}){
            for(int page=1;page<=50;page++){
                JSONArray items=api.getArray(API+repository+"/git/matching-refs/"+kind
                        +"?per_page=100&page="+page);
                for(int i=0;i<items.length();i++){
                    JSONObject ref=items.optJSONObject(i);
                    if(ref==null)continue;
                    String name=ref.optString("ref","");
                    JSONObject object=ref.optJSONObject("object");
                    if(name.startsWith("refs/"+kind+"/")&&object!=null)
                        out.put(name,object.optString("sha",""));
                }
                if(items.length()<100)break;
                if(page==50)throw new IllegalStateException("Слишком много Git refs для безопасной проверки");
            }
        }
        return out;
    }

    public static String digest(Map<String,String> refs)throws Exception{
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        for(Map.Entry<String,String> e:new TreeMap<>(refs).entrySet()){
            String row=e.getValue()+"\t"+e.getKey()+"\n";
            sha.update(row.getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder b=new StringBuilder();
        for(byte value:sha.digest())b.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return b.toString();
    }
    private static String safeError(Exception e){
        String message=e.getMessage();
        if(message==null||message.isEmpty())return e.getClass().getSimpleName();
        // Neither token nor raw GitHub authorization headers is ever deliberately reported.
        return message.length()>240?message.substring(0,240):message;
    }
    private void failure(Job job,Exception e){
        job.state=e instanceof GitHubApiClient.MutationOutcomeUnknownException?UNKNOWN:FAILED;
        job.error=safeError(e);save(job);
    }
}
