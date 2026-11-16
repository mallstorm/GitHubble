package app.githubble.mobile.agent;

import java.util.List;
import org.json.*;

/** Local and SAF access stays behind WorkspaceHub/WorkspaceEngine; task overlays never write that backend. */
public final class LocalWorkspaceSource implements WorkspaceSource {
    private final WorkspaceHub hub;private final String account,id;
    public LocalWorkspaceSource(WorkspaceHub hub,String account,String id){this.hub=hub;this.account=account;this.id=id;}
    public Snapshot snapshot(String path)throws Exception{ChangeStore.path(path);return new Snapshot(hub.taskRead(account,id,path),null,"100644");}
    public List<String> paths()throws Exception{return hub.taskPaths(account,id);}
    public java.util.Set<String> directories()throws Exception{return hub.taskDirectories(account,id);}
    public String identity(){return account+":"+id;}
    public String importReport()throws Exception {
        String raw=hub.importMap(account,id);if(raw.isEmpty())return "Local/SAF project";
        JSONObject report=new JSONObject(raw);JSONArray entries=report.getJSONArray("files"),skipped=new JSONArray();JSONObject reasons=new JSONObject();
        for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if("SKIPPED".equals(e.optString("status"))){String reason=e.optString("reason");reasons.put(reason,reasons.optInt(reason)+1);if(skipped.length()<100&&WorkspaceToolset.readable(e.optString("path")))skipped.put(e);}}
        return new JSONObject().put("source_sha",report.optString("source_sha")).put("partial",report.optBoolean("partial"))
            .put("skipped",report.optInt("skipped")).put("imported",report.optInt("imported")).put("skip_reasons",reasons).put("skipped_examples",skipped).toString();
    }
}
