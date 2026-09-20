package app.githubble.mobile.build;

import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Download policy, independent of activity rendering and notifications.
 * The newest successful workflow run is the ONLY automatic candidate for each
 * repository. Never fall back to an older run while a newer one lacks artifacts.
 */
public final class BuildAutoDownloadPolicy {
    private static final long MAX_ARTIFACT_BYTES=150L*1024L*1024L;
    private BuildAutoDownloadPolicy(){}

    public static JSONObject newestSuccessfulRun(JSONArray runs){
        JSONObject newest=null;
        long highest=0L;
        if(runs==null)return null;
        for(int i=0;i<runs.length();i++){
            JSONObject run=runs.optJSONObject(i);
            if(run==null || !"completed".equals(run.optString("status","")) ||
                    !"success".equals(run.optString("conclusion","")))continue;
            long id=run.optLong("id",0L);
            if(id>highest){highest=id;newest=run;}
        }
        return newest;
    }

    public static long latestSuccessfulRunId(JSONArray runs){
        JSONObject newest=newestSuccessfulRun(runs);
        return newest==null?0L:newest.optLong("id",0L);
    }

    /** Null means do not automatically enqueue anything on this scan. */
    public static JSONObject selectArtifact(JSONArray runs,long activationBaseline,
                                             long lastQueuedRunId){
        JSONObject newest=newestSuccessfulRun(runs);
        if(newest==null)return null;
        long runId=newest.optLong("id",0L);
        if(runId<=activationBaseline||runId<=lastQueuedRunId)return null;
        return bestApkArtifact(newest.optJSONArray("artifacts"));
    }

    public static JSONObject bestApkArtifact(JSONArray artifacts){
        JSONObject best=null;
        int maxScore=0;
        if(artifacts==null)return null;
        for(int i=0;i<artifacts.length();i++){
            JSONObject item=artifacts.optJSONObject(i);
            if(item==null||item.optBoolean("expired",false)||
                    item.optLong("id",0L)<=0)continue;
            long bytes=item.optLong("size_in_bytes",0L);
            if(bytes<=0||bytes>MAX_ARTIFACT_BYTES)continue;
            String name=item.optString("name","").toLowerCase(Locale.ROOT);
            if(name.contains("source")||name.contains("исход")||name.contains("windows")||
                    name.contains("win64")||name.contains("linux")||name.contains("macos")||
                    name.contains("report")||name.contains("lint")||name.contains("mapping")||
                    name.contains("log")||name.contains("test-results"))continue;
            int score=name.contains("apk")?3:name.contains("android")?2:
                    name.contains("githubble")?1:0;
            if(score>maxScore){maxScore=score;best=item;}
        }
        return best;
    }
}
