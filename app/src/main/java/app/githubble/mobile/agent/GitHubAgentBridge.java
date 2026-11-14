package app.githubble.mobile.agent;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.controller.RepositoryAdminController;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.security.SecureStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Restricted, accountable GitHub tool bridge for future OmniChat AgentEngine calls. */
public final class GitHubAgentBridge {
    private final GitHubAccountStore accounts;
    private final SecureStore secrets;

    public GitHubAgentBridge(GitHubAccountStore accounts, SecureStore secrets, GitHubApiClient api) {
        this.accounts = accounts;
        this.secrets = secrets;
    }

    public String captureAccount() {
        String id = accounts.activeCacheKey();
        if ("guest".equals(id) || secrets.get("github_token").isEmpty())
            throw new IllegalStateException("Сначала подключите GitHub-аккаунт");
        return id;
    }

    public void requireAccount(String expectedAccountId) {
        String selected = accounts.activeCacheKey();
        if (expectedAccountId == null || expectedAccountId.isEmpty() || "guest".equals(selected)
                || !expectedAccountId.equals(selected) || secrets.get("github_token").isEmpty()) {
            throw new IllegalStateException("Активный GitHub-аккаунт изменился. Переключите аккаунт проекта.");
        }
    }

    /** Immutable network credentials are captured under the account switch lock. */
    private GitHubApiClient scopedApi(String expected) throws GitHubApiClient.SessionChangedException {
        synchronized (SecureStore.SESSION_LOCK) {
            requireAccount(expected);
            String token = secrets.get("github_token");
            if (token.isEmpty()) throw new IllegalStateException("Токен GitHub отсутствует");
            return new GitHubApiClient(secrets).bindCurrentSession();
        }
    }

    public GitDataApi taskData(String account,Runnable guard)throws Exception {return GitDataApi.bound(toolApi(account),guard);}
    GitHubApiClient toolApi(String expected)throws GitHubApiClient.SessionChangedException{return scopedApi(expected);}

    public JSONObject createRepository(String expected, String name, String description, boolean privateRepo)
            throws Exception {
        GitHubApiClient bound = scopedApi(expected);
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,100}") || ".".equals(name) || "..".equals(name))
            throw new IllegalArgumentException("Недопустимое имя репозитория");
        return new RepositoryAdminController(bound).createRepository(name, description, privateRepo, true);
    }

    public JSONObject listWorkflows(String expected, String fullName) throws Exception {
        GitHubApiClient bound = scopedApi(expected);
        verifyRepo(fullName);
        return bound.getJson("https://api.github.com/repos/" + fullName + "/actions/workflows?per_page=50");
    }

    /** Workflow dispatch is scoped to the previously approved account and repository. */
    public void dispatchWorkflow(String expected, String fullName, String workflowId, String ref)
            throws Exception {
        GitHubApiClient bound = scopedApi(expected);
        verifyRepo(fullName);
        if (workflowId == null || !workflowId.matches("[A-Za-z0-9_.-]{1,128}"))
            throw new IllegalArgumentException("Недопустимый workflow");
        String branch = ref == null || ref.isEmpty() ? "main" : ref;
        JSONObject body = new JSONObject().put("ref", branch);
        // GitHub returns HTTP 204 with an empty response body for dispatch.
        bound.executeText("POST", "https://api.github.com/repos/" + fullName
                + "/actions/workflows/" + workflowId + "/dispatches", body.toString(), true);
    }


    /** Repository list comes from the active user's OAuth token, never from the AI model. */
    public JSONObject listAccessibleRepositories(String expected) throws Exception {
        GitHubApiClient api = scopedApi(expected);
        JSONArray result = new JSONArray();
        int pages = 0;
        boolean truncated = false;
        for (int page=1; page<=10; page++) {
            JSONArray batch=api.getArray("https://api.github.com/user/repos?affiliation=owner,collaborator,organization_member&per_page=100&page="+page+"&sort=full_name");
            pages++;
            for(int i=0;i<batch.length();i++){
                JSONObject repo=batch.getJSONObject(i);
                result.put(new JSONObject()
                    .put("full_name",repo.optString("full_name"))
                    .put("private",repo.optBoolean("private"))
                    .put("default_branch",repo.optString("default_branch"))
                    .put("permissions",repo.optJSONObject("permissions")==null
                        ?new JSONObject():repo.optJSONObject("permissions")));
            }
            if(batch.length()<100)break;
            if(page==10)truncated=true;
        }
        return new JSONObject().put("account",accounts.activeAccount()==null?"":accounts.activeAccount().login)
            .put("count",result.length()).put("truncated",truncated)
            .put("pages",pages).put("repositories",result);
    }

    public JSONObject activeIdentity(String expected) throws Exception {
        GitHubApiClient api=scopedApi(expected);
        JSONObject who=api.getJson("https://api.github.com/user");
        return new JSONObject().put("login",who.optString("login"))
            .put("id",who.optLong("id"))
            .put("public_repos",who.optInt("public_repos"))
            .put("private_repos",who.optInt("total_private_repos"));
    }

    public JSONObject repositoryDetails(String expected,String fullName)throws Exception{
        verifyRepo(fullName);
        JSONObject src=scopedApi(expected).getJson("https://api.github.com/repos/"+fullName);
        JSONObject perms=src.optJSONObject("permissions");
        return new JSONObject()
            .put("full_name",src.optString("full_name"))
            .put("private",src.optBoolean("private"))
            .put("default_branch",src.optString("default_branch"))
            .put("description",src.optString("description"))
            .put("size_kb",src.optLong("size"))
            .put("permissions",perms==null?new JSONObject():perms);
    }

    public JSONObject repositoryTree(String expected,String fullName,String branch)throws Exception{
        AgentGitWorkspace.validateRepository(fullName,branch);
        AgentGitWorkspace.Snapshot snapshot=new AgentGitWorkspace(accounts,secrets).inspect(expected,fullName,branch);
        return new JSONObject().put("repository",fullName).put("branch",branch)
            .put("head",snapshot.head).put("files_overview",snapshot.overview);
    }

    /** Read-only GitHub -> local Workspace import, after explicit UI confirmation. */
    public JSONObject importRepositoryToWorkspace(String expected,String repo,String branch,
            WorkspaceHub workspaces)throws Exception{
        GitHubApiClient api=scopedApi(expected);
        return RepositoryWorkspaceImporter.copy(api,()->requireAccount(expected),
            workspaces,expected,repo,branch);
    }

    /** Only the owner of a repository may delete via Git Agent; UI requires typed confirmation. */
    public void deleteOwnRepository(String expected,String fullName)throws Exception{
        verifyRepo(fullName);
        GitHubApiClient api=scopedApi(expected);
        JSONObject who=api.getJson("https://api.github.com/user");
        String login=who.optString("login","");
        if(login.isEmpty()||!fullName.substring(0,fullName.indexOf('/')).equalsIgnoreCase(login))
            throw new SecurityException("Удаление допускается только для собственного репозитория");
        JSONObject repo=api.getJson("https://api.github.com/repos/"+fullName);
        JSONObject permissions=repo.optJSONObject("permissions");
        if(permissions==null||!permissions.optBoolean("admin"))
            throw new SecurityException("У токена нет административного доступа к репозиторию");
        new RepositoryAdminController(api).deleteRepository(fullName);
    }

    /** Model-visible read tools: no mutation endpoints or access token disclosure. */
    public JSONObject ciDiagnostics(String expected,String repo,long runId,boolean logs)throws Exception {
        requireAccount(expected);AgentBuildDiagnostics d=new AgentBuildDiagnostics(accounts,secrets);
        JSONObject result=logs?d.summarizeWithErrors(expected,repo,runId):d.summarize(expected,repo,runId);
        requireAccount(expected);return result;
    }
    public JSONObject executeReadOnly(String expected,String name,JSONObject args,String linkedRepo,String branch)throws Exception{
        if(args==null)args=new JSONObject();
        if(GitHubAgentOperations.READS.contains(name))
            return GitHubAgentOperations.read(this,expected,name,args,linkedRepo,branch);
        switch(name){
            case "github_account":return activeIdentity(expected);
            case "list_repositories":return listAccessibleRepositories(expected);
            case "repository_info":{
                String repo=args.optString("repository",linkedRepo);
                return repositoryDetails(expected,repo);
            }
            case "repository_files":{
                String repo=args.optString("repository",linkedRepo);
                return repositoryTree(expected,repo,args.optString("branch",branch));
            }
            case "recent_build_runs":{
                if(linkedRepo==null||linkedRepo.isEmpty())
                    throw new IllegalArgumentException("Сначала привяжите репозиторий");
                AgentGitWorkspace.validateRepository(linkedRepo,branch);
                JSONObject response=scopedApi(expected).getJson(
                    "https://api.github.com/repos/"+linkedRepo+
                    "/actions/runs?branch="+branch+"&per_page=8");
                JSONArray runs=response.optJSONArray("workflow_runs"),list=new JSONArray();
                for(int i=0;runs!=null&&i<runs.length()&&i<8;i++){
                    JSONObject item=runs.optJSONObject(i);
                    if(item!=null)list.put(new JSONObject()
                        .put("id",item.optLong("id")).put("name",item.optString("name"))
                        .put("sha",item.optString("head_sha"))
                        .put("branch",item.optString("head_branch"))
                        .put("status",item.optString("status"))
                        .put("conclusion",item.optString("conclusion")));
                }
                return new JSONObject().put("repository",linkedRepo).put("runs",list);
            }
            case "build_run_diagnostics":{
                if(linkedRepo==null||linkedRepo.isEmpty())
                    throw new IllegalArgumentException("Сначала привяжите репозиторий");
                long runId=args.optLong("run_id",0);
                if(runId<=0)throw new IllegalArgumentException("Укажите положительный run_id");
                requireAccount(expected);
                // General GitHub-read permission discloses ONLY run/job metadata.
                // Full log excerpts require the separate explicit repair dialog.
                JSONObject report=new AgentBuildDiagnostics(accounts,secrets)
                    .summarize(expected,linkedRepo,runId);
                requireAccount(expected);
                return report;
            }
            case "repository_workflows":{
                String repo=args.optString("repository",linkedRepo);
                JSONObject workflows=listWorkflows(expected,repo),compact=new JSONObject();
                JSONArray items=workflows.optJSONArray("workflows"),listed=new JSONArray();
                for(int i=0;items!=null&&i<items.length()&&i<30;i++){
                    JSONObject item=items.optJSONObject(i);
                    if(item!=null)listed.put(new JSONObject()
                        .put("name",item.optString("name"))
                        .put("path",item.optString("path"))
                        .put("state",item.optString("state")));
                }
                return compact.put("repository",repo).put("workflows",listed);
            }
            default:throw new SecurityException("GitHub-инструмент не разрешён: "+name);
        }
    }

    private static void verifyRepo(String fullName) {
        if (fullName == null || !fullName.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
            throw new IllegalArgumentException("Ожидается репозиторий OWNER/REPO");
    }
}

