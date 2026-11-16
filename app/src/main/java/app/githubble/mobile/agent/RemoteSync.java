package app.githubble.mobile.agent;

import java.util.*;
import org.json.*;

/** Compare only changed paths against the current HEAD. Unrelated remote files remain in base_tree. */
public final class RemoteSync {
    private RemoteSync() {}
    public static final class Plan {
        public final String parent,tree;
        public final JSONArray entries;
        public final boolean rebased;
        Plan(String parent,String tree,JSONArray entries,boolean rebased){this.parent=parent;this.tree=tree;this.entries=entries;this.rebased=rebased;}
    }
    public static Plan plan(GitDataApi api,String repo,String branch,String expectedHead,JSONArray delta)throws Exception {
        String head=GitHubWorkspaceSource.head(api,repo,branch);
        String tree=api.get(GitDataApi.base(repo)+"/git/commits/"+head).getJSONObject("tree").getString("sha");GitObjectId.requireCommit(tree);
        GitHubWorkspaceSource current=new GitHubWorkspaceSource(api,repo,head);JSONArray verified=new JSONArray();
        for(int i=0;i<delta.length();i++){
            JSONObject entry=UnifiedAgentStore.copy(delta.getJSONObject(i));String path=entry.getString("path");ChangeStore.path(path);
            WorkspaceSource.Snapshot remote=current.snapshot(path);
            if(!entry.getString("beforeSha").equals(remote.sha))throw new ChangeStore.Conflict("Удалённый файл изменился: "+path);
            if(remote.text!=null)entry.put("mode",remote.mode);
            verified.put(entry);
        }
        return new Plan(head,tree,verified,!head.equals(expectedHead));
    }
}
