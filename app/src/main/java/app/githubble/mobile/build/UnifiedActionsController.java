package app.githubble.mobile.build;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** A repository's workflow runs and their actual GitHub-hosted artifacts. */
public final class UnifiedActionsController {
    private static final int PAGE_SIZE = 20;
    private final GitHubApiClient api;

    public static final class Snapshot {
        public final JSONArray runs;
        public final int runCount, artifactCount;
        public final long artifactBytes;
        public final boolean inventoryPartial, hasMoreRuns;
        Snapshot(JSONArray runs, int runCount, int artifactCount, long artifactBytes,
                 boolean inventoryPartial, boolean hasMoreRuns) {
            this.runs=runs; this.runCount=runCount; this.artifactCount=artifactCount;
            this.artifactBytes=artifactBytes; this.inventoryPartial=inventoryPartial;
            this.hasMoreRuns=hasMoreRuns;
        }
    }

    public UnifiedActionsController(GitHubApiClient api) { this.api=api; }

    private static String base(String full) {
        if (full==null || !full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Неверное имя репозитория");
        return "https://api.github.com/repos/"+full+"/actions/";
    }

    /**
     * Enumerate the project artifacts, then join by workflow_run.id. Do not
     * misrepresent a limited scan as an exact GitHub billing/storage balance.
     */
    public Snapshot load(String full, int page) throws Exception {
        if (page<1 || page>1000) throw new IllegalArgumentException("Недопустимая страница");
        String url=base(full);
        JSONObject listing=api.getJson(url+"runs?per_page="+PAGE_SIZE+"&page="+page);
        JSONArray runs=listing.optJSONArray("workflow_runs");
        if (runs==null) throw new IllegalStateException("GitHub не вернул список запусков");
        int runCount=listing.optInt("total_count",runs.length());
        Map<Long,JSONArray> byRun=new LinkedHashMap<>();
        for(int i=0;i<runs.length();i++){
            JSONObject run=runs.optJSONObject(i);
            if(run==null)continue;
            byRun.put(run.optLong("id"),new JSONArray());
        }
        long bytes=0L;
        int totalArtifacts=0,seen=0;
        boolean complete=false;
        for(int p=1;p<=20;p++){
            JSONObject response=api.getJson(url+"artifacts?per_page=100&page="+p);
            JSONArray items=response.optJSONArray("artifacts");
            if(items==null)throw new IllegalStateException("GitHub не вернул список артефактов");
            if(p==1)totalArtifacts=response.optInt("total_count",items.length());
            for(int i=0;i<items.length();i++){
                JSONObject item=items.optJSONObject(i);
                if(item==null)continue;
                seen++;
                bytes+=Math.max(0L,item.optLong("size_in_bytes",0L));
                JSONObject workflow=item.optJSONObject("workflow_run");
                long runId=workflow==null?0L:workflow.optLong("id",0L);
                JSONArray group=byRun.get(runId);
                if(group!=null && !item.optBoolean("expired",false))group.put(item);
            }
            if(items.length()<100){complete=true;break;}
        }
        boolean partial=!complete||seen<totalArtifacts;
        for(int i=0;i<runs.length();i++){
            JSONObject run=runs.optJSONObject(i);
            if(run!=null){
                JSONArray group=byRun.get(run.optLong("id",0L));
                run.put("artifacts",group==null?new JSONArray():group);
            }
        }
        return new Snapshot(runs,runCount,totalArtifacts,bytes,partial,
                page*PAGE_SIZE<runCount);
    }

    /** Returns true if the whole run was deleted as the selected artifact was its last. */
    public boolean deleteArtifact(String full,long runId,long artifactId)throws Exception {
        String url=base(full);
        if(runId<=0||artifactId<=0)throw new IllegalArgumentException("Некорректный ID");
        int count=0;boolean found=false,complete=false;
        for(int p=1;p<=20;p++){
            JSONObject response=api.getJson(url+"runs/"+runId+
                    "/artifacts?per_page=100&page="+p);
            JSONArray items=response.optJSONArray("artifacts");
            if(items==null)throw new IllegalStateException("GitHub не вернул артефакты запуска");
            for(int i=0;i<items.length();i++){
                JSONObject artifact=items.optJSONObject(i);
                if(artifact==null||artifact.optBoolean("expired",false))continue;
                count++;
                if(artifact.optLong("id",0L)==artifactId)found=true;
            }
            if(items.length()<100){complete=true;break;}
        }
        if(!complete)throw new IllegalStateException("Список файлов неполный: удаление отменено");
        if(!found)throw new IllegalStateException("Артефакт больше не существует. Обновите список.");
        if(count==1){
            // A single GitHub DELETE removes the run and all its artifacts atomically.
            api.delete(url+"runs/"+runId);
        }else{
            api.delete(url+"artifacts/"+artifactId);
        }
        api.invalidatePrefix(url);
        return count==1;
    }

    /** Explicit destructive operation: removes run logs and ALL its artifacts. */
    public void deleteRun(String full,long runId)throws Exception {
        if(runId<=0L)throw new IllegalArgumentException("Некорректный ID запуска");
        String url=base(full);
        api.delete(url+"runs/"+runId);
        api.invalidatePrefix(url);
    }
}
