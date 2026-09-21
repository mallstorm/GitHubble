package app.githubble.mobile.build;

import app.githubble.mobile.controller.CommitDetailController;
import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only Actions run snapshot for native GitHubble UI. */
public final class BuildRunDetailController {
    public static final class Detail {
        public final JSONObject run;
        public final JSONArray jobs;
        public final JSONArray artifacts;
        public final boolean partial;
        Detail(JSONObject run,JSONArray jobs,JSONArray artifacts,boolean partial){
            this.run=run;this.jobs=jobs;this.artifacts=artifacts;this.partial=partial;
        }
    }
    private final GitHubApiClient api;
    public BuildRunDetailController(GitHubApiClient api){this.api=api;}
    public Detail load(String full,long runId) throws Exception {
        if(!CommitDetailController.validRepository(full)||runId<=0L)
            throw new IllegalArgumentException("Некорректный репозиторий или номер запуска");
        String base="https://api.github.com/repos/"+full+"/actions/runs/"+runId;
        JSONObject run=api.getJson(base);
        JSONArray jobs=new JSONArray(),artifacts=new JSONArray();
        boolean partial=false;
        for(int page=1;page<=5;page++){
            JSONArray batch=api.getJson(base+"/jobs?per_page=100&page="+page).optJSONArray("jobs");
            if(batch==null){partial=true;break;}
            for(int i=0;i<batch.length();i++)jobs.put(batch.optJSONObject(i));
            if(batch.length()<100)break;
            if(page==5)partial=true;
        }
        for(int page=1;page<=5;page++){
            JSONArray batch=api.getJson(base+"/artifacts?per_page=100&page="+page).optJSONArray("artifacts");
            if(batch==null){partial=true;break;}
            for(int i=0;i<batch.length();i++)artifacts.put(batch.optJSONObject(i));
            if(batch.length()<100)break;
            if(page==5)partial=true;
        }
        return new Detail(run,jobs,artifacts,partial);
    }
}
