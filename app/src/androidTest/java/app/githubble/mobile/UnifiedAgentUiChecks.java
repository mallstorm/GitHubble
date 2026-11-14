package app.githubble.mobile;

import android.content.Context;
import app.githubble.mobile.agent.AgentProjectStore;
import app.githubble.mobile.agent.WorkspaceHub;
import org.json.JSONObject;

/** Real SharedPreferences and files: project/task persistence without any external account or secret. */
final class UnifiedAgentUiChecks {
    static void run(Context context) throws Exception {
        String account="unified-"+java.util.UUID.randomUUID();
        AgentProjectStore store=new AgentProjectStore(context);
        store.activeConversation(account);
        String project=store.registry.create(account,"Engineering","owner/repo","main").getString("projectId");
        String first=store.newTask(account,project,"Parser","Fix parser");
        store.appendToConversation(account,first,"user","First task context");
        String second=store.newTask(account,project,"Renderer","Fix renderer");
        if(first.equals(second))throw new AssertionError("Tasks reused an empty draft identity");
        if(!store.project(account).workspaceId.isEmpty())throw new AssertionError("New task automatically attached Workspace");
        store.appendToConversation(account,second,"user","Second task context");
        store=new AgentProjectStore(context);store.switchConversation(account,first);
        if(!project.equals(store.projectId(account,first)))throw new AssertionError("Restart lost permanent project");
        if(!"First task context".equals(store.messages(account,first).getJSONObject(0).getString("body")))
            throw new AssertionError("Tasks shared conversation content");
        if(store.registry.get(account,project).optBoolean("modelAccess"))throw new AssertionError("Upgrade opted project into external model access");
        store.deleteConversation(account,second);
        if(store.registry.list(account).length()!=1||store.tasks.list(account,project).length()!=1)
            throw new AssertionError("Task deletion removed its project");
        WorkspaceHub hub=new WorkspaceHub(context);String workspace=hub.create(account,"kept-source").getString("id");
        hub.saveFile(account,workspace,"src/Keep.java","class Keep {}\n");
        store.save(new AgentProjectStore.Project(account,"Engineering","owner/repo","main",workspace));
        store=new AgentProjectStore(context);
        if(!workspace.equals(store.projectForChat(account,first).workspaceId)
            ||!"class Keep {}\n".equals(hub.readFile(account,workspace,"src/Keep.java")))
            throw new AssertionError("Metadata migration moved or lost source files");
        System.out.println("PASS: unified Project/Task persistence and isolation");
    }
}
