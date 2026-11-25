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
        String other = viewAs.get();
        if (other != null && !other.isEmpty()) {
            boolean active;
            synchronized (SecureStore.SESSION_LOCK) { requireAccount(expected); active = isActiveLogin(other); }
            if (!active) {
                // The network check runs outside the session lock.
                try { return foreignClient(other); }
                catch (RuntimeException e) { throw e; }
                catch (Exception e) { throw new IllegalStateException("Не удалось открыть аккаунт @" + other, e); }
            }
        }
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
    // ---------------------------------------------------------------- several connected accounts (read-only)

    /** Login whose data the current read-only call looks at; null means the active account. */
    private final ThreadLocal<String> viewAs = new ThreadLocal<>();

    private boolean isActiveLogin(String login) {
        GitHubAccountStore.Account active = accounts.activeAccount();
        return active != null && active.login.equalsIgnoreCase(login);
    }

    private GitHubAccountStore.Account accountByLogin(String login) {
        for (GitHubAccountStore.Account a : accounts.listAccounts()) if (a.login.equalsIgnoreCase(login)) return a;
        return null;
    }

    /** A client for another connected account. It never switches the foreground account and is never used for writes. */
    /** Renews a saved session of any connected account without the user (set by the host). */
    public interface AccountRefresher { boolean refresh(String accountId, boolean force) throws Exception; }
    private volatile AccountRefresher refresher;
    public void setRefresher(AccountRefresher value) { refresher = value; }

    private boolean renew(GitHubAccountStore.Account account, boolean force) {
        AccountRefresher r = refresher;
        if (r == null) return false;
        try { return r.refresh(account.id, force); } catch (Exception ignored) { return false; }
    }

    private GitHubApiClient foreignClient(String login) throws Exception {
        GitHubAccountStore.Account account = accountByLogin(login);
        if (account == null) throw new IllegalArgumentException("Аккаунт @" + login + " не подключён к GitHubble");
        if (!accounts.tokenUsable(account.id)) renew(account, false);
        try { return openForeign(account); }
        catch (SecurityException wrongAccount) { throw wrongAccount; }
        catch (Exception rejected) {
            // The token may have been revoked or rotated: one forced silent refresh, then a single retry.
            if (!renew(account, true)) throw rejected;
            return openForeign(account);
        }
    }

    private GitHubApiClient openForeign(GitHubAccountStore.Account account) throws Exception {
        String login = account.login;
        String token = accounts.tokenForAccount(account.id);
        if (token == null || token.isEmpty()) throw new IllegalStateException("Нет действующего токена @" + login + ": войдите в аккаунт заново (автоматическое обновление не сработало: сессия отозвана или истекла)");
        final app.githubble.mobile.network.GitHubSession session =
                new app.githubble.mobile.network.GitHubSession(token, account.login, 0L);
        GitHubApiClient client = new GitHubApiClient(new app.githubble.mobile.network.GitHubSession.Source() {
            public app.githubble.mobile.network.GitHubSession capture() { return session; }
            public boolean isCurrent(app.githubble.mobile.network.GitHubSession got) { return got == session; }
            public void reauthorization(app.githubble.mobile.network.GitHubSession got, boolean flag) {}
        }, null);
        JSONObject who = client.getJsonFresh("https://api.github.com/user");
        if (!account.login.equalsIgnoreCase(who.optString("login", "")))
            throw new SecurityException("Сессия принадлежит другому GitHub-аккаунту");
        return client;
    }

    /** Normalised login of another connected account, or "" when the call targets the active account. */
    public String foreignLogin(String raw) {
        String other = raw == null ? "" : raw.trim();
        if (other.startsWith("@")) other = other.substring(1);
        if (other.isEmpty() || isActiveLogin(other)) return "";
        if (!other.matches("[A-Za-z0-9-]{1,39}")) throw new IllegalArgumentException("Некорректный логин аккаунта");
        GitHubAccountStore.Account a = accountByLogin(other);
        if (a == null) throw new IllegalArgumentException("Аккаунт @" + other + " не подключён к GitHubble");
        return a.login;
    }

    public String activeLogin() {
        GitHubAccountStore.Account active = accounts.activeAccount();
        if (active == null) throw new IllegalStateException("Нет активного аккаунта GitHub");
        return active.login;
    }

    /** Raw job log of a run in the active account's repository. */
    public String jobLog(String expected, String repository, long jobId) throws Exception {
        requireAccount(expected);
        return new AgentBuildDiagnostics(accounts, secrets).jobLog(expected, repository, jobId);
    }

    /** Label shown on approval cards: the account the write really goes to. */
    public String writeLabel(String foreign) {
        if (foreign != null && !foreign.isEmpty()) return "@" + foreign;
        GitHubAccountStore.Account active = accounts.activeAccount();
        return active == null ? "" : "@" + active.login;
    }

    /** Runs an already-approved write as another connected account (never switches the foreground account). */
    public <T> T asLogin(String foreign, java.util.concurrent.Callable<T> task) throws Exception {
        if (foreign == null || foreign.isEmpty()) return task.call();
        viewAs.set(foreign);
        try { return task.call(); } finally { viewAs.remove(); }
    }

    /** All connected accounts: logins and names only, never tokens. */
    public JSONObject connectedAccounts(String expected) throws Exception {
        requireAccount(expected);
        JSONArray list = new JSONArray();
        String active = "";
        for (GitHubAccountStore.Account a : accounts.listAccounts()) {
            list.put(new JSONObject().put("login", a.login).put("name", a.name).put("active", a.active)
                    .put("status", a.active ? "active (builds, publishing, default for writes)" : "connected (reads; repository deletes, github_request and release signing accept {account} after the user approves)"));
            if (a.active) active = a.login;
        }
        return new JSONObject().put("count", list.length()).put("max", GitHubAccountStore.MAX_ACCOUNTS)
                .put("active_login", active).put("accounts", list)
                .put("note", "To read another connected account pass {\"account\":\"<login>\"} to a read-only tool; "
                        + "list_repositories also accepts \"all\". github_request, delete_repository, actions_cleanup and setup_release_signing accept the same {\"account\":\"<login>\"} "
                        + "and always show the user a confirmation card with that account; builds and file publishing use the active account.");
    }

    /** Repositories of every connected account; a broken session of one account is reported, not fatal. */
    public JSONObject listAllRepositories(String expected) throws Exception {
        requireAccount(expected);
        JSONArray perAccount = new JSONArray();
        int total = 0;
        for (GitHubAccountStore.Account a : accounts.listAccounts()) {
            JSONObject item = new JSONObject().put("login", a.login).put("active", a.active);
            viewAs.set(a.login);
            try {
                JSONObject r = listAccessibleRepositories(expected);
                item.put("count", r.optInt("count")).put("truncated", r.optBoolean("truncated"))
                        .put("repositories", r.getJSONArray("repositories"));
                total += r.optInt("count");
            } catch (SecurityException | IllegalStateException e) {
                item.put("error", "Сессия аккаунта недействительна, войдите в @" + a.login + " заново");
            } catch (Exception e) {
                item.put("error", "GitHub не вернул список репозиториев @" + a.login);
            } finally { viewAs.remove(); }
            perAccount.put(item);
        }
        return new JSONObject().put("accounts_count", perAccount.length()).put("total_repositories", total)
                .put("accounts", perAccount);
    }

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
        String shown=viewAs.get();
        if(shown==null||shown.isEmpty())shown=accounts.activeAccount()==null?"":accounts.activeAccount().login;
        return new JSONObject().put("account",shown)
            .put("count",result.length()).put("truncated",truncated)
            .put("pages",pages).put("repositories",result);
    }

    public JSONObject activeIdentity(String expected) throws Exception {
        GitHubApiClient api=scopedApi(expected);
        return accountSummary(api.getJsonFresh("https://api.github.com/user"));
    }

    /** Missing profile counters are unknown, never evidence of zero accessible repositories. */
    static JSONObject accountSummary(JSONObject who)throws org.json.JSONException {
        Object publicCount=profileCount(who,"public_repos"),privateCount=profileCount(who,"total_private_repos");
        return new JSONObject().put("login",who.optString("login")).put("id",who.optLong("id"))
            .put("public_repos",publicCount).put("private_repos",privateCount)
            .put("private_repos_status",privateCount==JSONObject.NULL?"UNKNOWN":"REPORTED_BY_PROFILE")
            .put("counts_scope","account_profile_not_accessible_repository_inventory")
            .put("repository_count_note","Null means unknown, not zero. Profile counts do not prove private repository visibility. Use list_repositories to count actually accessible repositories, including collaborators and organizations.");
    }
    private static Object profileCount(JSONObject who,String key){
        Object value=who.opt(key);return value instanceof Number&&((Number)value).longValue()>=0?value:JSONObject.NULL;
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
        String other=viewAs.get();
        AgentGitWorkspace workspace=new AgentGitWorkspace(accounts,secrets);
        AgentGitWorkspace.Snapshot snapshot=(other!=null&&!other.isEmpty()&&!isActiveLogin(other))
            ?workspace.inspect(scopedApi(expected),fullName,branch):workspace.inspect(expected,fullName,branch);
        JSONObject out=new JSONObject().put("repository",fullName).put("branch",branch)
            .put("head",snapshot.head).put("files_overview",snapshot.overview);
        try{ // other branches often hold the real project; show them so the agent never stops at a bare default branch
            JSONArray list=scopedApi(expected).getArray("https://api.github.com/repos/"+fullName+"/branches?per_page=30");
            JSONArray names=new JSONArray();
            for(int i=0;list!=null&&i<list.length();i++){JSONObject b=list.optJSONObject(i);if(b!=null)names.put(b.optString("name"));}
            out.put("branches",names).put("note","This is only branch '"+branch+"'. If it has few files, inspect other branches by passing branch.");
        }catch(Exception ignored){}
        return out;
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
        if("github_accounts".equals(name))return connectedAccounts(expected);
        String other=args.optString("account","").trim();
        if(other.startsWith("@"))other=other.substring(1);
        if(other.isEmpty())return executeReadOnlyActive(expected,name,args,linkedRepo,branch);
        if("all".equalsIgnoreCase(other)&&"list_repositories".equals(name))return listAllRepositories(expected);
        if(!other.matches("[A-Za-z0-9-]{1,39}"))throw new IllegalArgumentException("Некорректный логин аккаунта");
        if(isActiveLogin(other))return executeReadOnlyActive(expected,name,args,linkedRepo,branch);
        viewAs.set(other);
        try{return executeReadOnlyActive(expected,name,args,linkedRepo,branch);}
        finally{viewAs.remove();}
    }

    private JSONObject executeReadOnlyActive(String expected,String name,JSONObject args,String linkedRepo,String branch)throws Exception{
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

