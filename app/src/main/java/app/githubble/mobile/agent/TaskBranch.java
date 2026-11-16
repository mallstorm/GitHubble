package app.githubble.mobile.agent;

import org.json.*;
import java.util.Locale;

/** A changing task owns one generated branch; existing foreign branches are never adopted. */
public final class TaskBranch {
    private TaskBranch() {}
    public static String name(String title,String task) {
        String slug=title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");
        if(slug.isEmpty())slug="task";slug=slug.substring(0,Math.min(42,slug.length()));
        String id=task.replaceAll("[^a-zA-Z0-9]","");if(id.length()<8)throw new IllegalArgumentException("Task ID слишком короткий");
        return "hubble/"+slug+"-"+id.substring(0,Math.min(16,id.length())).toLowerCase(Locale.ROOT);
    }
    public static String ensure(GitDataApi api,String repo,String baseBranch,String title,String task,JSONObject state,
            java.util.function.Consumer<JSONObject> persist)throws Exception {
        String branch=name(title,task);AgentGitWorkspace.validateRepository(repo,branch);
        if(state.optString("branch").equals(branch)&&state.optString("repository").equals(repo)&&state.optString("state").equals("CONFIRMED")) {
            GitHubWorkspaceSource.head(api,repo,branch);return branch;
        }
        if(state.has("state"))throw new IllegalStateException("Создание ветки требует сверки предыдущего результата");
        String base=GitHubWorkspaceSource.head(api,repo,baseBranch);
        try{GitHubWorkspaceSource.head(api,repo,branch);throw new ChangeStore.Conflict("Ветка задачи уже существует и не принадлежит этой задаче");}
        catch(app.githubble.mobile.network.GitHubApiClient.ApiException missing){if(missing.statusCode!=404)throw missing;}
        JSONObject record=new JSONObject().put("branch",branch).put("repository",repo).put("baseSha",base).put("state","CREATE_PENDING");persist.accept(record);
        try{
            api.post(GitDataApi.base(repo)+"/git/refs",new JSONObject().put("ref","refs/heads/"+branch).put("sha",base));
            String actual=GitHubWorkspaceSource.head(api,repo,branch);
            if(!base.equals(actual))throw new ChangeStore.Conflict("Ветка сдвинулась при создании");
            record.put("state","CONFIRMED");persist.accept(record);return branch;
        }catch(Exception uncertain){record.put("state","UNKNOWN");persist.accept(record);throw uncertain;}
    }
}
