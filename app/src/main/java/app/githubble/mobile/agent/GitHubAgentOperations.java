package app.githubble.mobile.agent;

import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.controller.ProfileController;
import app.githubble.mobile.controller.RepositoryAdminController;
import app.githubble.mobile.controller.AccountOperationsController;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONObject;

/** Explicit tool surface, never an arbitrary URL/method supplied by a model. */
public final class GitHubAgentOperations {
    public static final Set<String> READS=set("github_profile","github_usage","github_rate_limit","github_capabilities",
        "repository_branches","repository_read_file","repository_issues","repository_pulls",
        "issue_details","pull_request_details","build_artifacts","verify_build");
    public static final Set<String> WRITES=set("create_repository","create_branch",
        "update_repository","update_account_profile","create_issue","update_issue",
        "create_pull_request","update_pull_request","comment_issue","merge_pull_request","dispatch_workflow");
    public static final String READ_CATALOG="github_profile {}, github_usage {} (usage, not remaining quota), "+
        "github_rate_limit {}, github_capabilities {} (GitHub App installations and grants), repository_branches {repository}, "+
        "repository_read_file {repository,path,branch}, repository_issues {repository}, "+
        "repository_pulls {repository}, issue_details {repository,number}, "+
        "pull_request_details {repository,number}, "+
        "build_artifacts {repository,run_id} (artifact names/sizes/expiry of a run), "+
        "verify_build {repository,run_id,sha} (independent check: run finished, succeeded, matches sha, artifact exists; "+
        "it does NOT inspect an APK — never call a build delivered from a green run alone).";
    public static final String WRITE_CATALOG="create_repository {name,description,private:true}, "+
        "create_branch {repository,branch,from_ref}, "+
        "update_repository {repository,description,homepage,has_issues,has_wiki,has_projects,allow_squash_merge,allow_merge_commit,allow_rebase_merge,delete_branch_on_merge}, "+
        "update_account_profile {name,bio,company,location,blog,hireable}, "+
        "create_issue {repository,title,body}, update_issue {repository,number,title,body,state:open|closed}, "+
        "create_pull_request {repository,title,body,head,base,draft:true}, "+
        "update_pull_request {repository,number,title,body,state:open|closed,base}, "+
        "comment_issue {repository,number,body} (also for pull requests), "+
        "merge_pull_request {repository,number,sha,merge_method:squash|merge|rebase}, "+
        "dispatch_workflow {repository,workflow_id,ref}.";
    private static Set<String> set(String... names){return java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(names)));}
    private GitHubAgentOperations(){}
    public static boolean sensitive(String name){
        return set("update_account_profile","update_repository","merge_pull_request","dispatch_workflow").contains(name);
    }
    private static String enc(String s)throws Exception{return URLEncoder.encode(s,StandardCharsets.UTF_8.name()).replace("+","%20");}
    public static String repository(JSONObject a,String fallback){
        String repo=a.optString("repository",fallback);
        if(repo==null||!repo.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
            throw new IllegalArgumentException("Укажите OWNER/REPO");
        return repo;
    }
    private static String required(JSONObject a,String key,int max){
        String value=a.optString(key,"").trim();
        if(value.isEmpty()||value.length()>max)throw new IllegalArgumentException("Укажите "+key+" (до "+max+" символов)");
        return value;
    }
    private static int number(JSONObject a){
        int n=a.optInt("number",0);if(n<=0)throw new IllegalArgumentException("Нужен положительный number");return n;
    }
    private static JSONObject pick(JSONObject a,String... names)throws Exception{
        JSONObject out=new JSONObject();for(String n:names)if(a.has(n))out.put(n,a.get(n));return out;
    }
    private static long runId(JSONObject a){
        long id=a.optLong("run_id",0);if(id<=0)throw new IllegalArgumentException("Нужен положительный run_id");return id;
    }
    /** Independent Supervisor check of a CI run; the model's own claim of success is never used. */
    static JSONObject verifyBuild(GitHubApiClient api,String repo,String branch,long runId,String sha)throws Exception{
        if(!sha.isEmpty()&&!sha.matches("[a-fA-F0-9]{40}"))throw new IllegalArgumentException("Неверный sha");
        app.githubble.mobile.agent.core.Supervisor.Claims claims=new app.githubble.mobile.agent.core.Supervisor.Claims();
        claims.repo=repo;claims.runId=runId;claims.commitSha=sha;claims.requireArtifact=true;
        app.githubble.mobile.agent.core.Supervisor.Verdict verdict=
            new app.githubble.mobile.agent.core.Supervisor(new GitHubEvidence(api)).verifyDelivery(claims);
        org.json.JSONArray findings=new org.json.JSONArray();
        for(app.githubble.mobile.agent.core.Supervisor.Finding f:verdict.findings)
            findings.put(new JSONObject().put("severity",f.severity.name()).put("check",f.check).put("message",f.message)
                .put("return_to",f.returnTo==null?"":f.returnTo.name()));
        app.githubble.mobile.agent.core.TaskState back=verdict.returnTo();
        return new JSONObject().put("repository",repo).put("run_id",runId).put("accepted",verdict.accepted())
            .put("apk_inspected",false).put("findings",findings).put("return_to",back==null?"":back.name())
            .put("note","accepted=true confirms run success"+(sha.isEmpty()?"":", matching sha")+" and a non-empty artifact only. "+
                "APK package/version/signature are NOT verified by this call.");
    }
    public static JSONObject read(GitHubAgentBridge bridge,String account,String name,JSONObject a,
            String linked,String branch)throws Exception{
        GitHubApiClient api=bridge.toolApi(account);
        if("github_profile".equals(name)){
            JSONObject profile=api.getJson("https://api.github.com/user");
            return pick(profile,"login","name","bio","company","location","blog","hireable",
                "public_repos","total_private_repos","followers","following","plan","created_at","updated_at");
        }
        if("github_rate_limit".equals(name))return api.getJson("https://api.github.com/rate_limit");
        if("github_capabilities".equals(name)){
            JSONObject raw=api.getJson("https://api.github.com/user/installations?per_page=100");
            org.json.JSONArray grants=new org.json.JSONArray(),items=raw.optJSONArray("installations");
            for(int i=0;items!=null&&i<items.length();i++){
                JSONObject item=items.getJSONObject(i);
                grants.put(pick(item,"id","account","permissions","repository_selection","suspended_at"));
            }
            return new JSONObject().put("installations",grants).put("total_count",raw.optInt("total_count"))
                .put("partial",raw.optInt("total_count")>grants.length())
                .put("settings_url","https://github.com/settings/installations")
                .put("note","These are installation grants, not proof that every API operation is allowed. AI cannot elevate GitHub App registration, account security, payment or billing permissions.");
        }
        if("github_usage".equals(name)){
            AccountOperationsController.BillingSnapshot b=new AccountOperationsController(api).loadBillingOverview();
            return new JSONObject().put("login",b.login).put("plan",b.planName)
                .put("period",b.billingPeriod).put("billing_available",b.billingApiAvailable)
                .put("minutes_used",b.minutesUsed<0?JSONObject.NULL:b.minutesUsed)
                .put("remaining_minutes",JSONObject.NULL).put("warning",b.billingWarning)
                .put("note","Remaining allowance and billed storage are not exposed by this result; never infer them from plan names.");
        }
        String base="https://api.github.com/repos/"+repository(a,linked);
        switch(name){
            case "repository_branches":return new JSONObject().put("branches",api.getArray(base+"/branches?per_page=100")).put("limit",100);
            case "repository_issues":return new JSONObject().put("issues",api.getArray(base+"/issues?state=all&per_page=30")).put("limit",30);
            case "repository_pulls":return new JSONObject().put("pull_requests",api.getArray(base+"/pulls?state=all&per_page=30")).put("limit",30);
            case "issue_details":return api.getJson(base+"/issues/"+number(a));
            case "pull_request_details":return api.getJson(base+"/pulls/"+number(a));
            case "build_artifacts":{
                long runId=runId(a);
                JSONObject response=api.getJson(base+"/actions/runs/"+runId+"/artifacts?per_page=100");
                org.json.JSONArray items=response.optJSONArray("artifacts"),list=new org.json.JSONArray();
                for(int i=0;items!=null&&i<items.length();i++){
                    JSONObject item=items.getJSONObject(i);
                    list.put(pick(item,"id","name","size_in_bytes","expired","created_at"));
                }
                return new JSONObject().put("run_id",runId).put("total_count",response.optInt("total_count"))
                    .put("artifacts",list);
            }
            case "verify_build":return verifyBuild(api,repository(a,linked),a.optString("branch",branch),runId(a),a.optString("sha",""));
            case "repository_read_file":{
                String path=required(a,"path",220);
                if(!WorkspaceChangeReview.writablePath(path))throw new SecurityException("Защищённый путь");
                JSONObject file=api.getJson(base+"/contents/"+enc(path)+"?ref="+enc(a.optString("branch",branch)));
                if(file.optLong("size",0)>AgentFilePlan.MAX_FILE_BYTES)throw new IllegalArgumentException("Файл слишком большой");
                if(!"base64".equals(file.optString("encoding")))throw new IllegalArgumentException("Нет текстового содержимого");
                String content=new String(java.util.Base64.getMimeDecoder().decode(file.optString("content")),StandardCharsets.UTF_8);
                if(content.indexOf('\0')>=0)throw new IllegalArgumentException("Бинарный файл");
                return new JSONObject().put("path",path).put("sha",file.optString("sha"))
                    .put("content",app.githubble.mobile.memory.MemoryPolicy.INSTANCE.redactSecrets(content));
            }
            default:throw new SecurityException("Неизвестный инструмент чтения: "+name);
        }
    }
    public static JSONObject write(GitHubAgentBridge bridge,String account,String name,JSONObject a,
            String linked,String branch)throws Exception{
        if(!WRITES.contains(name))throw new SecurityException("Неизвестная операция");
        GitHubApiClient api=bridge.toolApi(account);
        if("create_repository".equals(name))return bridge.createRepository(account,required(a,"name",100),a.optString("description"),a.optBoolean("private",true));
        if("update_account_profile".equals(name)){
            ProfileController c=new ProfileController(api);
            JSONObject patch=ProfileController.editablePatch(c.current(),pick(a,"name","bio","company","location","blog","hireable"));
            return patch.length()==0?new JSONObject().put("unchanged",true):c.updateEditable(patch);
        }
        String repo=repository(a,linked),base="https://api.github.com/repos/"+repo;
        JSONObject result;
        switch(name){
            case "create_branch":{
                String target=required(a,"branch",160),from=a.optString("from_ref",branch);
                AgentGitWorkspace.validateRepository(repo,target);
                if(!from.matches("[a-fA-F0-9]{40}"))AgentGitWorkspace.validateRepository(repo,from);
                result=new RepositoryAdminController(api).createBranch(repo,target,from);break;
            }
            case "update_repository":{
                JSONObject patch=pick(a,"description","homepage","has_issues","has_wiki","has_projects",
                    "allow_squash_merge","allow_merge_commit","allow_rebase_merge","delete_branch_on_merge");
                if(patch.length()==0)throw new IllegalArgumentException("Нет параметров для изменения");
                result=api.patchJson(base,patch);break;
            }
            case "create_issue":{
                JSONObject body=pick(a,"body").put("title",required(a,"title",256));
                result=api.postJson(base+"/issues",body,true);break;
            }
            case "update_issue":{
                JSONObject patch=pick(a,"title","body","state");
                if(patch.length()==0)throw new IllegalArgumentException("Нет изменений issue");
                if(patch.has("state")&&!set("open","closed").contains(patch.optString("state")))throw new IllegalArgumentException("Неверный state");
                result=api.patchJson(base+"/issues/"+number(a),patch);break;
            }
            case "create_pull_request":{
                JSONObject body=pick(a,"body").put("title",required(a,"title",256))
                    .put("head",required(a,"head",200)).put("base",a.optString("base",branch))
                    .put("draft",a.optBoolean("draft",true));
                result=api.postJson(base+"/pulls",body,true);break;
            }
            case "update_pull_request":{
                JSONObject patch=pick(a,"title","body","state","base");
                if(patch.length()==0)throw new IllegalArgumentException("Нет изменений PR");
                if(patch.has("state")&&!set("open","closed").contains(patch.optString("state")))throw new IllegalArgumentException("Неверный state");
                result=api.patchJson(base+"/pulls/"+number(a),patch);break;
            }
            case "comment_issue":{
                JSONObject body=new JSONObject().put("body",required(a,"body",65000));
                result=api.postJson(base+"/issues/"+number(a)+"/comments",body,true);break;
            }
            case "merge_pull_request":{
                String sha=required(a,"sha",40),method=a.optString("merge_method","squash");
                if(!sha.matches("[a-fA-F0-9]{40}")||!set("squash","merge","rebase").contains(method))throw new IllegalArgumentException("Неверный SHA или merge_method");
                result=new JSONObject(api.executeText("PUT",base+"/pulls/"+number(a)+"/merge",new JSONObject().put("sha",sha).put("merge_method",method).toString(),true));break;
            }
            case "dispatch_workflow":bridge.dispatchWorkflow(account,repo,required(a,"workflow_id",128),a.optString("ref",branch));result=new JSONObject().put("dispatch_accepted",true).put("completed",false);break;
            default:throw new SecurityException("Операция не разрешена");
        }
        api.invalidatePrefix(base);return result;
    }
}
