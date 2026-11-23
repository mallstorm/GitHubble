package app.githubble.mobile.repository;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.mirror.MirrorPolicy;
import app.githubble.mobile.network.GitHubApiClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Repository metadata export and explicitly confirmed non-destructive restore.
 * Git refs/LFS are NOT copied here: existing Mirror Manager handles Git content.
 * PRs and release binaries cannot be recreated faithfully; exported for reference.
 */
public final class RepositoryMetadataBackup {
    private static final String API="https://api.github.com/repos/";
    private static final int MAX_PAGES=12;
    private static final int MAX_RESTORE_ISSUES=200;
    private final GitHubAccountStore accounts;
    private final RepositorySelectiveTransfer sessions;

    public RepositoryMetadataBackup(GitHubAccountStore accounts) {
        this.accounts=accounts;
        this.sessions=new RepositorySelectiveTransfer(accounts);
    }

    private GitHubAccountStore.Account account(String id) {
        for(GitHubAccountStore.Account a:accounts.listAccounts())
            if(a.id.equals(id))return a;
        throw new IllegalArgumentException("Выбранный GitHub-аккаунт не найден");
    }

    private GitHubApiClient client(String accountId) throws Exception {
        return sessions.client(accountId);
    }

    private static String encode(String value) {
        try{return URLEncoder.encode(value,"UTF-8").replace("+","%20");}
        catch(Exception invalid){throw new IllegalStateException(invalid);}
    }

    private static JSONArray readAll(GitHubApiClient api,String endpoint,int maxPages) throws Exception {
        JSONArray all=new JSONArray();
        for(int page=1;page<=maxPages+1;page++) {
            String url=endpoint+(endpoint.contains("?")?"&":"?")
                    +"per_page=100&page="+page;
            JSONArray batch=api.getArray(url);
            if(batch==null)throw new IllegalStateException("GitHub не вернул страницу "+url);
            if(page>maxPages) {
                if(batch.length()>0)
                    throw new IllegalStateException("Превышен лимит резервирования: "
                            +maxPages*100+" записей. Экспорт отменён, неполный архив не создаётся");
                break;
            }
            for(int i=0;i<batch.length();i++) {
                JSONObject item=batch.optJSONObject(i);
                if(item==null)throw new SecurityException("Неполная запись GitHub: "+url);
                all.put(item);
            }
            if(batch.length()<100)break;
        }
        return all;
    }

    private static JSONObject asSection(JSONArray values) {
        return new JSONObject().put("items",values);
    }

    private static JSONObject minimalRepository(JSONObject source) {
        JSONObject result=new JSONObject();
        for(String key:new String[]{"full_name","name","description","homepage",
                "private","archived","disabled","default_branch","has_issues",
                "has_projects","has_wiki","license","created_at","updated_at","pushed_at"}) {
            if(source.has(key))result.put(key,source.opt(key));
        }
        return result;
    }

    private static JSONArray cleanIssues(JSONArray issues) {
        JSONArray cleaned=new JSONArray();
        for(int i=0;i<issues.length();i++) {
            JSONObject issue=issues.optJSONObject(i);
            if(issue!=null&&!issue.has("pull_request"))cleaned.put(issue);
        }
        return cleaned;
    }

    /**
     * Reads every page, and refuses partial records. Git metadata is captured
     * as refs + default SHA ONLY, not as a false claim of historical backup.
     */
    public JSONObject capture(String repository,String accountId) throws Exception {
        MirrorPolicy.requireRepository(repository);
        GitHubApiClient api=client(accountId);
        JSONObject repo=api.getJsonFresh(API+repository);
        String branch=RepositorySelectiveTransfer.branch(repo);
        String startSha=RepositorySelectiveTransfer.head(api,repository,branch);
        JSONObject sections=new JSONObject();
        sections.put("repository",minimalRepository(repo));
        sections.put("branches",asSection(readAll(api,API+repository+"/branches",MAX_PAGES)));
        sections.put("tags",asSection(readAll(api,API+repository+"/tags",MAX_PAGES)));
        sections.put("labels",asSection(readAll(api,API+repository+"/labels",MAX_PAGES)));
        sections.put("milestones",asSection(readAll(api,API+repository+"/milestones?state=all",MAX_PAGES)));
        sections.put("issues",asSection(cleanIssues(readAll(api,API+repository+"/issues?state=all",MAX_PAGES))));
        sections.put("issue_comments",asSection(readAll(api,API+repository+"/issues/comments",MAX_PAGES)));
        sections.put("pulls",asSection(readAll(api,API+repository+"/pulls?state=all",MAX_PAGES)));
        sections.put("pull_comments",asSection(readAll(api,API+repository+"/pulls/comments",MAX_PAGES)));
        sections.put("releases",asSection(readAll(api,API+repository+"/releases",MAX_PAGES)));
        String endSha=RepositorySelectiveTransfer.head(api,repository,branch);
        if(!startSha.equals(endSha))
            throw new IllegalStateException("Исходный HEAD изменился во время сбора данных. "
                    +"Повторите создание архива");
        return new JSONObject().put("repository",repository)
                .put("default_branch",branch).put("default_sha",startSha)
                .put("created_at",Instant.now().toString())
                .put("private",repo.optBoolean("private",true))
                .put("sections",sections);
    }

    public static final class Plan {
        public final String target, accountId, currentHead;
        public final int newLabels, existingLabels, newMilestones, existingMilestones;
        public final int issues, comments, existingTargetIssues;
        public final boolean issueRestoreAllowed;
        Plan(String target,String accountId,String currentHead,int labels,int sameLabels,
             int milestones,int sameMilestones,int issues,int comments,int targetIssues,
             boolean allowed) {
            this.target=target;this.accountId=accountId;this.currentHead=currentHead;
            newLabels=labels;existingLabels=sameLabels;
            newMilestones=milestones;existingMilestones=sameMilestones;
            this.issues=issues;this.comments=comments;
            existingTargetIssues=targetIssues;issueRestoreAllowed=allowed;
        }
    }

    public static final class Result {
        public int labels,milestones,issues,comments,closed;
    }

    private static Set<String> names(JSONArray data,String key) {
        Set<String> result=new HashSet<>();
        for(int i=0;i<data.length();i++) {
            JSONObject item=data.optJSONObject(i);
            if(item==null)throw new SecurityException("Некорректная запись в архиве");
            String name=item.optString(key,"").trim();
            if(name.isEmpty())throw new SecurityException("Отсутствует название в архиве");
            result.add(name.toLowerCase(java.util.Locale.ROOT));
        }
        return result;
    }

    private static int countNew(JSONArray imported,Set<String> existing,String key) {
        int count=0;
        for(int i=0;i<imported.length();i++) {
            String v=imported.getJSONObject(i).optString(key,"");
            if(v.isEmpty())throw new SecurityException("Пустое имя в архиве");
            if(existing.add(v.toLowerCase(java.util.Locale.ROOT)))count++;
        }
        return count;
    }

    /** Read-only preview. No remote mutations, no automatic changes to Git refs. */
    public Plan preview(RepositoryBackupArchive.Archive backup,
                        String target,String accountId) throws Exception {
        MirrorPolicy.requireRepository(target);
        if(backup.manifest.optString("repository").equalsIgnoreCase(target))
            throw new IllegalArgumentException("Нельзя восстановить архив в исходный репозиторий");
        GitHubAccountStore.Account a=account(accountId);
        if(!target.substring(0,target.indexOf('/')).equalsIgnoreCase(a.login))
            throw new SecurityException("Репозиторий назначения не принадлежит выбранному аккаунту");
        GitHubApiClient api=client(accountId);
        JSONObject repo=api.getJsonFresh(API+target);
        if(repo.optBoolean("archived",false)||repo.optBoolean("disabled",false))
            throw new IllegalStateException("Архивный/отключённый репозиторий нельзя изменять");
        if(backup.manifest.optBoolean("private",true) && !repo.optBoolean("private",true))
            throw new SecurityException("Приватный архив нельзя восстанавливать в публичный репозиторий");
        JSONObject permission=repo.optJSONObject("permissions");
        if(permission!=null&&!permission.optBoolean("push",false))
            throw new SecurityException("Для восстановления требуются права записи");
        String branch=RepositorySelectiveTransfer.branch(repo);
        String sha=RepositorySelectiveTransfer.head(api,target,branch);
        JSONArray oldLabels=backup.list("labels"), oldMilestones=backup.list("milestones");
        JSONArray labels=readAll(api,API+target+"/labels",MAX_PAGES);
        JSONArray milestones=readAll(api,API+target+"/milestones?state=all",MAX_PAGES);
        JSONArray issues=readAll(api,API+target+"/issues?state=all",MAX_PAGES);
        int totalIssues=cleanIssues(issues).length();
        int newLabels=countNew(oldLabels,names(labels,"name"),"name");
        int newMilestones=countNew(oldMilestones,names(milestones,"title"),"title");
        int issueCount=backup.list("issues").length();
        int commentCount=backup.list("issue_comments").length();
        return new Plan(target,accountId,sha,newLabels,oldLabels.length()-newLabels,
                newMilestones,oldMilestones.length()-newMilestones,
                issueCount,commentCount,totalIssues,
                totalIssues==0&&issueCount<=MAX_RESTORE_ISSUES);
    }

    /**
     * Non-idempotent API mutations. Explicit user approval required.
     * Target HEAD and plan are revalidated before the first write. On failure
     * stops; no automatic retry because GitHub may have accepted the request.
     */
    public Result apply(RepositoryBackupArchive.Archive backup,
                        Plan accepted,boolean includeIssues) throws Exception {
        if(accepted==null)throw new IllegalArgumentException("План восстановления отсутствует");
        Plan latest=preview(backup,accepted.target,accepted.accountId);
        if(!latest.currentHead.equals(accepted.currentHead)
                ||latest.newLabels!=accepted.newLabels
                ||latest.newMilestones!=accepted.newMilestones
                ||latest.existingTargetIssues!=accepted.existingTargetIssues
                ||latest.issues!=accepted.issues)
            throw new IllegalStateException("Репозиторий изменён после предпросмотра. Повторите проверку");
        if(includeIssues&&!latest.issueRestoreAllowed)
            throw new SecurityException("Issues можно восстановить только в пустой по задачам "
                    +"репозиторий, до "+MAX_RESTORE_ISSUES+" записей");
        GitHubApiClient api=client(accepted.accountId);
        Result result=new Result();
        try {
            Set<String> existingLabels=names(readAll(api,API+accepted.target+"/labels",MAX_PAGES),"name");
            JSONArray labels=backup.list("labels");
            for(int i=0;i<labels.length();i++) {
                JSONObject item=labels.getJSONObject(i);
                String name=item.getString("name");
                if(existingLabels.add(name.toLowerCase(java.util.Locale.ROOT))) {
                    api.postJson(API+accepted.target+"/labels",
                            new JSONObject().put("name",name)
                                    .put("color",item.optString("color","ededed"))
                                    .put("description",item.optString("description","")),true);
                    result.labels++;
                }
            }
            JSONArray targetMilestones=readAll(api,API+accepted.target
                    +"/milestones?state=all",MAX_PAGES);
            Map<Integer,Integer> milestoneIds=new HashMap<>();
            Map<String,Integer> titles=new HashMap<>();
            for(int i=0;i<targetMilestones.length();i++) {
                JSONObject m=targetMilestones.getJSONObject(i);
                titles.put(m.optString("title","").toLowerCase(java.util.Locale.ROOT),
                        m.optInt("number",0));
            }
            JSONArray milestones=backup.list("milestones");
            for(int i=0;i<milestones.length();i++) {
                JSONObject m=milestones.getJSONObject(i);
                String title=m.getString("title"), key=title.toLowerCase(java.util.Locale.ROOT);
                Integer existing=titles.get(key);
                if(existing==null) {
                    JSONObject payload=new JSONObject().put("title",title)
                            .put("description",m.optString("description",""))
                            .put("state",m.optString("state","open"));
                    String due=m.optString("due_on","");
                    if(!due.isEmpty()&&!due.equals("null"))payload.put("due_on",due);
                    JSONObject created=api.postJson(API+accepted.target+"/milestones",payload,true);
                    existing=created.getInt("number");result.milestones++;
                    titles.put(key,existing);
                }
                milestoneIds.put(m.getInt("number"),existing);
            }
            if(includeIssues) {
                Map<Integer,JSONObject> importedIssues=new HashMap<>();
                JSONArray issues=backup.list("issues");
                for(int i=0;i<issues.length();i++) {
                    JSONObject original=issues.getJSONObject(i);
                    int number=original.getInt("number");
                    String originalUrl="https://github.com/"+backup.manifest.getString("repository")
                            +"/issues/"+number;
                    String provenance="Восстановлено из архива GitHubble.\n"
                            +"Оригинал: "+originalUrl+"\n"
                            +"Автор: "+original.optJSONObject("user").optString("login","unknown")+"\n"
                            +"Создано: "+original.optString("created_at","")+"\n\n";
                    JSONObject request=new JSONObject()
                            .put("title",original.optString("title","Восстановленная задача"))
                            .put("body",provenance+original.optString("body",""));
                    JSONArray selectedLabels=new JSONArray();
                    JSONArray old=original.optJSONArray("labels");
                    for(int j=0;old!=null&&j<old.length();j++) {
                        JSONObject label=old.optJSONObject(j);
                        if(label!=null)selectedLabels.put(label.optString("name",""));
                    }
                    request.put("labels",selectedLabels);
                    JSONObject mile=original.optJSONObject("milestone");
                    if(mile!=null) {
                        Integer newNumber=milestoneIds.get(mile.optInt("number"));
                        if(newNumber!=null)request.put("milestone",newNumber);
                    }
                    JSONObject created=api.postJson(API+accepted.target+"/issues",request,true);
                    importedIssues.put(number,created);result.issues++;
                }
                JSONArray comments=backup.list("issue_comments");
                for(int i=0;i<comments.length();i++) {
                    JSONObject c=comments.getJSONObject(i);
                    String issueUrl=c.optString("issue_url","");
                    String id=issueUrl.substring(issueUrl.lastIndexOf('/')+1);
                    int old;
                    try{old=Integer.parseInt(id);}catch(NumberFormatException bad){continue;}
                    JSONObject created=importedIssues.get(old);
                    if(created==null)continue;
                    String by=c.optJSONObject("user")==null?"unknown":
                            c.optJSONObject("user").optString("login","unknown");
                    String body="Комментарий из архива GitHubble — "+by
                            +" ("+c.optString("created_at","")+"):\n\n"
                            +c.optString("body","");
                    api.postJson(API+accepted.target+"/issues/"+created.getInt("number")
                            +"/comments",new JSONObject().put("body",body),true);
                    result.comments++;
                }
                for(int i=0;i<issues.length();i++) {
                    JSONObject oldIssue=issues.getJSONObject(i);
                    if(!"closed".equals(oldIssue.optString("state","")))continue;
                    JSONObject newIssue=importedIssues.get(oldIssue.getInt("number"));
                    if(newIssue!=null) {
                        api.patchJson(API+accepted.target+"/issues/"+newIssue.getInt("number"),
                                new JSONObject().put("state","closed"));
                        result.closed++;
                    }
                }
            }
            return result;
        } catch(Exception error) {
            throw new IllegalStateException("Восстановление частично выполнено. Создано: меток "
                    +result.labels+", этапов "+result.milestones+", задач "+result.issues
                    +", комментариев "+result.comments+". Не повторяйте автоматически; "
                    +"сначала проверьте репозиторий назначения. Причина: "+error.getMessage(),error);
        }
    }
}
