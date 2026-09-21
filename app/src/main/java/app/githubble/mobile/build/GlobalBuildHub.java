package app.githubble.mobile.build;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.security.SecureStore;

/**
 * Account-wide, repository-independent index for the sixth bottom tab.
 * Never silently subscribes repositories to APK auto-download or per-repository notifications.
 * Android JobScheduler periodically refreshes this index while the user is signed in.
 */
public final class GlobalBuildHub {
    public static final String PREF_ENABLED="build_hub_background_enabled";
    private static final int MAX_REPOSITORIES=100;
    private static final int RUNS_PER_REPOSITORY=6;
    private static final int MAX_VISIBLE_RUNS=120;
    private static final long MIN_REFRESH_INTERVAL_MS=60_000L;

    private GlobalBuildHub(){}

    public static final class Snapshot {
        public final JSONArray runs;
        public final int scannedRepositories;
        public final int failedRepositories;
        public final boolean partial;
        public final long syncedAt;
        public final String warning;

        Snapshot(JSONArray runs,int scannedRepositories,int failedRepositories,
                 boolean partial,long syncedAt,String warning){
            this.runs=runs;
            this.scannedRepositories=scannedRepositories;
            this.failedRepositories=failedRepositories;
            this.partial=partial;
            this.syncedAt=syncedAt;
            this.warning=warning;
        }
    }

    private static String account(Context context){
        return BuildMonitor.account(context);
    }
    private static String prefix(Context context){
        return "global_build_hub_"+account(context)+"_";
    }
    public static boolean enabled(Context context){
        return !account(context).isEmpty() &&
                BuildMonitor.prefs(context).getBoolean(PREF_ENABLED,true);
    }

    public static Snapshot cached(Context context){
        if(account(context).isEmpty())
            return new Snapshot(new JSONArray(),0,0,false,0L,"");
        SharedPreferences prefs=BuildMonitor.prefs(context);
        String key=prefix(context);
        JSONArray runs;
        try{runs=new JSONArray(prefs.getString(key+"runs","[]"));}
        catch(Exception ignored){runs=new JSONArray();}
        return new Snapshot(runs,prefs.getInt(key+"repositories",0),
                prefs.getInt(key+"failed",0),prefs.getBoolean(key+"partial",false),
                prefs.getLong(key+"checked_at",0L),prefs.getString(key+"warning",""));
    }

    private static boolean validRepository(String full){
        return full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") && full.length()<=190;
    }

    /**
     * Read the actual repositories accessible to the signed-in GitHub account. A successful
     * Actions run is listed even when it publishes zero artifacts; unavailable artifacts
     * never cause a successful run to be misreported as an APK.
     */
    public static synchronized Snapshot refresh(Context context,boolean force) throws Exception {
        String login=account(context);
        if(login.isEmpty())return cached(context);
        Snapshot old=cached(context);
        if(!force&&old.syncedAt>0L&&
                System.currentTimeMillis()-old.syncedAt<MIN_REFRESH_INTERVAL_MS)return old;
        GitHubApiClient api=new GitHubApiClient(new SecureStore(context));
        SharedPreferences prefs=BuildMonitor.prefs(context);
        List<String> repositories=new ArrayList<>();
        Set<String> unique=new HashSet<>();
        boolean partial=false;
        // One page plus a one-item check avoids indexing an unbounded number of repositories.
        JSONArray repos=api.getArray("https://api.github.com/user/repos?visibility=all"
                +"&affiliation=owner,collaborator,organization_member&per_page=100&page=1");
        for(int i=0;i<repos.length()&&repositories.size()<MAX_REPOSITORIES;i++){
            JSONObject item=repos.optJSONObject(i);
            if(item==null||item.optBoolean("archived",false))continue;
            String full=item.optString("full_name","");
            if(validRepository(full)&&unique.add(full))repositories.add(full);
        }
        if(repos.length()==100){
            JSONArray next=api.getArray("https://api.github.com/user/repos?visibility=all"
                    +"&affiliation=owner,collaborator,organization_member&per_page=1&page=101");
            partial=next.length()>0;
        }

        List<JSONObject> all=new ArrayList<>();
        int scanned=0;
        int failed=0;
        String firstError="";
        for(String full:repositories){
            if(!login.equals(account(context)))return cached(context);
            try{
                JSONObject response=api.getJson("https://api.github.com/repos/"+full+
                        "/actions/runs?per_page="+RUNS_PER_REPOSITORY);
                JSONArray runs=response.optJSONArray("workflow_runs");
                if(runs==null)throw new IllegalStateException("GitHub не вернул workflow_runs");
                if(runs.length()==0){scanned++;continue;}
                Map<Long,JSONArray> byRun=new HashMap<>();
                try{
                    JSONObject responseArtifacts=api.getJson("https://api.github.com/repos/"+
                            full+"/actions/artifacts?per_page=100");
                    JSONArray artifacts=responseArtifacts.optJSONArray("artifacts");
                    if(artifacts!=null)for(int i=0;i<artifacts.length();i++){
                        JSONObject artifact=artifacts.optJSONObject(i);
                        if(artifact==null||artifact.optLong("id",0L)<=0L)continue;
                        JSONObject workflow=artifact.optJSONObject("workflow_run");
                        long runId=workflow==null?0L:workflow.optLong("id",0L);
                        if(runId>0L){
                            JSONArray entries=byRun.get(runId);
                            if(entries==null){entries=new JSONArray();byRun.put(runId,entries);}
                            entries.put(artifact);
                        }
                    }
                }catch(Exception artifactError){
                    // A failed artifact request means unknown availability, not "no artifacts".
                    firstError=firstError.isEmpty()?"Часть артефактов недоступна: "+
                            artifactError.getMessage():firstError;
                    partial=true;
                }
                for(int i=0;i<runs.length();i++){
                    JSONObject original=runs.optJSONObject(i);
                    if(original==null||original.optLong("id",0L)<=0L)continue;
                    JSONObject row=new JSONObject();
                    long runId=original.optLong("id",0L);
                    row.put("id",runId);
                    row.put("repository",full);
                    row.put("name",original.optString("name","GitHub Actions"));
                    row.put("status",original.optString("status",""));
                    row.put("conclusion",original.optString("conclusion",""));
                    row.put("head_sha",original.optString("head_sha",""));
                    row.put("head_branch",original.optString("head_branch",""));
                    row.put("event",original.optString("event",""));
                    row.put("created_at",original.optString("created_at",""));
                    row.put("run_started_at",original.optString("run_started_at",""));
                    row.put("updated_at",original.optString("updated_at",""));
                    row.put("html_url",original.optString("html_url",""));
                    row.put("artifacts",byRun.containsKey(runId)?byRun.get(runId):new JSONArray());
                    all.add(row);
                }
                scanned++;
            }catch(Exception error){
                failed++;
                if(firstError.isEmpty())firstError=error.getMessage()==null?
                        "Некоторые проекты недоступны":error.getMessage();
            }
        }
        if(!login.equals(account(context)))return cached(context);
        all.sort((left,right)->right.optString("created_at","")
                .compareTo(left.optString("created_at","")));
        JSONArray output=new JSONArray();
        for(int i=0;i<Math.min(MAX_VISIBLE_RUNS,all.size());i++)output.put(all.get(i));
        partial=partial||all.size()>MAX_VISIBLE_RUNS||failed>0;
        long timestamp=System.currentTimeMillis();
        String key=prefix(context);
        prefs.edit().putString(key+"runs",output.toString())
                .putInt(key+"repositories",scanned)
                .putInt(key+"failed",failed)
                .putBoolean(key+"partial",partial)
                .putLong(key+"checked_at",timestamp)
                .putString(key+"warning",firstError)
                .apply();
        return new Snapshot(output,scanned,failed,partial,timestamp,firstError);
    }
}
