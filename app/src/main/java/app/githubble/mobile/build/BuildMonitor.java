package app.githubble.mobile.build;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import app.githubble.mobile.MainActivity;
import app.githubble.mobile.R;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.security.SecureStore;

/** Account-scoped Actions monitor. A completed build is not "APK ready" until artifacts exist. */
public final class BuildMonitor {
    public static final String PREF_ENABLED="build_monitor_enabled";
    public static final String CHANNEL="githubble_build_updates_v1";
    public static final String OPEN_ACTION="app.githubble.mobile.OPEN_BUILDS";
    private BuildMonitor(){}

    public static SharedPreferences prefs(Context context){
        return context.getSharedPreferences("githubble",Context.MODE_PRIVATE);
    }
    public static String account(Context context){
        return new SecureStore(context).get("github_login").trim().toLowerCase(Locale.ROOT);
    }
    private static String scope(Context context){return "build_"+account(context)+"_";}
    private static boolean valid(String full){
        return full!=null&&full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                &&full.length()<=190;
    }
    public static Set<String> watched(Context context){
        if(account(context).isEmpty())return new LinkedHashSet<>();
        Set<String> result=new LinkedHashSet<>();
        String raw=prefs(context).getString(scope(context)+"watched","[]");
        try{
            JSONArray array=new JSONArray(raw);
            for(int i=0;i<array.length()&&result.size()<30;i++){
                String full=array.optString(i,"");
                if(valid(full))result.add(full);
            }
        }catch(Exception ignored){}
        return result;
    }
    public static boolean isWatched(Context context,String full){
        return watched(context).contains(full);
    }
    public static void setWatched(Context context,String full,boolean enabled){
        if(!valid(full)||account(context).isEmpty())return;
        Set<String> updated=watched(context);
        if(enabled&&updated.size()<30)updated.add(full);
        else if(!enabled)updated.remove(full);
        JSONArray array=new JSONArray();
        for(String repo:updated)array.put(repo);
        prefs(context).edit().putString(scope(context)+"watched",array.toString())
                .putBoolean(PREF_ENABLED,!updated.isEmpty()).apply();
        BuildMonitorJobService.reschedule(context);
    }
    public static void clearAccount(Context context){
        // Do not erase downloaded archives; remove only account-specific monitoring state.
        SharedPreferences p=prefs(context);
        String prefix=scope(context);
        SharedPreferences.Editor editor=p.edit();
        for(String key:p.getAll().keySet())if(key.startsWith(prefix))editor.remove(key);
        editor.apply();
        BuildMonitorJobService.reschedule(context);
    }
    public static JSONArray recent(Context context){
        JSONArray merged=new JSONArray();
        List<JSONObject> all=new ArrayList<>();
        for(String repo:watched(context)){
            try{
                JSONArray rows=new JSONArray(prefs(context).getString(
                        scope(context)+"rows_"+repo,"[]"));
                for(int i=0;i<rows.length();i++){
                    JSONObject run=rows.optJSONObject(i);
                    if(run!=null)all.add(run);
                }
            }catch(Exception ignored){}
        }
        all.sort((left,right)->right.optString("created_at","")
                .compareTo(left.optString("created_at","")));
        for(int i=0;i<Math.min(60,all.size());i++)merged.put(all.get(i));
        return merged;
    }
    public static void createChannel(Context context){
        if(Build.VERSION.SDK_INT<26)return;
        NotificationManager nm=context.getSystemService(NotificationManager.class);
        if(nm==null)return;
        NotificationChannel channel=new NotificationChannel(CHANNEL,
                "Сборки и артефакты GitHub",NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Новые коммиты, результаты GitHub Actions и готовые APK/ZIP");
        nm.createNotificationChannel(channel);
    }
    private static boolean canNotify(Context context){
        if(!prefs(context).getBoolean("build_notifications",true))return false;
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager nm=context.getSystemService(NotificationManager.class);
        return nm!=null&&(Build.VERSION.SDK_INT<24||nm.areNotificationsEnabled());
    }
    private static void notification(Context context,JSONObject run,boolean ready){
        if(!canNotify(context))return;
        createChannel(context);
        NotificationManager nm=context.getSystemService(NotificationManager.class);
        if(nm==null)return;
        String repo=run.optString("repository","");
        String label=run.optString("name","GitHub Actions");
        boolean failure="failure".equals(run.optString("conclusion",""));
        String title=ready?"Артефакты готовы: "+repo:
                !"completed".equals(run.optString("status",""))?"Новая сборка: "+repo:
                failure?"Сборка завершилась с ошибкой: "+repo:
                "Сборка завершена: "+repo;
        String detail=label+" · "+run.optString("head_sha","");
        Intent open=new Intent(context,MainActivity.class).setAction(OPEN_ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int id=(int)(run.optLong("id",0L)&0x7fffffffL);
        PendingIntent pi=PendingIntent.getActivity(context,id,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n=Build.VERSION.SDK_INT>=26?
                new Notification.Builder(context,CHANNEL):new Notification.Builder(context);
        n.setSmallIcon(R.drawable.ic_repository).setColor(0xFF33964B)
                .setContentTitle(title).setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(pi).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS);
        if(Build.VERSION.SDK_INT<26)n.setPriority(Notification.PRIORITY_DEFAULT);
        nm.notify(id,n.build());
    }

    /** The initial snapshot is a baseline, not a flood of notifications for historical runs. */
    public static int scan(Context context,boolean emitNotifications) throws Exception{
        if(account(context).isEmpty())return 0;
        if(!prefs(context).getBoolean(PREF_ENABLED,true))return 0;
        Set<String> repositories=watched(context);
        if(repositories.isEmpty())return 0;
        GitHubApiClient api=new GitHubApiClient(new SecureStore(context));
        int changes=0;
        String prefix=scope(context);
        for(String repo:repositories){
            // Explicitly selected repos only. No public GitHub search or guessing account access.
            JSONArray response;
            try{
                JSONObject body=api.getJson("https://api.github.com/repos/"+repo+
                        "/actions/runs?per_page=15");
                response=body.optJSONArray("workflow_runs");
                if(response==null)throw new IllegalStateException("GitHub не вернул workflow_runs");
            }catch(Exception error){
                prefs(context).edit().putString(prefix+"error_"+repo,
                        error.getMessage()==null?"Ошибка сети":error.getMessage()).apply();
                continue;
            }
            String stateKey=prefix+"states_"+repo;
            String rowsKey=prefix+"rows_"+repo;
            boolean initialized=prefs(context).contains(stateKey);
            JSONObject previous;
            try{previous=new JSONObject(prefs(context).getString(stateKey,"{}"));}
            catch(Exception ignored){previous=new JSONObject();}
            JSONObject now=new JSONObject();
            JSONArray rows=new JSONArray();
            for(int i=0;i<response.length();i++){
                JSONObject original=response.optJSONObject(i);
                if(original==null)continue;
                long id=original.optLong("id",0);
                if(id<=0)continue;
                String status=original.optString("status","");
                String conclusion=original.optString("conclusion","");
                JSONArray artifacts=new JSONArray();
                // GitHub publishes artifacts after a run; inspect its latest completed runs.
                if("completed".equals(status)&&i<8){
                    try{
                        JSONObject listing=api.getJson("https://api.github.com/repos/"+repo+
                                "/actions/runs/"+id+"/artifacts?per_page=100");
                        JSONArray raw=listing.optJSONArray("artifacts");
                        if(raw!=null)for(int k=0;k<raw.length();k++){
                            JSONObject artifact=raw.optJSONObject(k);
                            if(artifact!=null&&!artifact.optBoolean("expired",false)
                                    &&artifact.optLong("id",0)>0)artifacts.put(artifact);
                        }
                    }catch(Exception ignored){
                        // Do not convert a temporary artifacts API failure into "no artifacts".
                        JSONArray older=oldArtifacts(context,rowsKey,id);
                        if(older!=null)artifacts=older;
                    }
                }else if("completed".equals(status)){
                    JSONArray older=oldArtifacts(context,rowsKey,id);
                    if(older!=null)artifacts=older;
                }
                JSONObject row=new JSONObject();
                row.put("id",id);
                row.put("repository",repo);
                row.put("name",original.optString("name","GitHub Actions"));
                row.put("status",status);
                row.put("conclusion",conclusion);
                row.put("head_sha",original.optString("head_sha",""));
                row.put("head_branch",original.optString("head_branch",""));
                row.put("created_at",original.optString("created_at",""));
                row.put("html_url",original.optString("html_url",""));
                row.put("artifacts",artifacts);
                rows.put(row);
                String marker=status+":"+conclusion+":"+artifactIds(artifacts);
                String key=Long.toString(id);
                now.put(key,marker);
                if(initialized&&emitNotifications){
                    String old=previous.optString(key,"");
                    if(!marker.equals(old)){
                        boolean ready=artifacts.length()>0&&
                                (!old.contains(":"+conclusion+":")||
                                 !old.endsWith(":"+artifactIds(artifacts)));
                        boolean justFinished="completed".equals(status)&&
                                (old.isEmpty()||!old.startsWith("completed:"));
                        if(ready||justFinished||(old.isEmpty()&&!"completed".equals(status))){
                            notification(context,row,ready);
                            changes++;
                        }
                    }
                }
            }
            prefs(context).edit().putString(rowsKey,rows.toString())
                    .putString(stateKey,now.toString()).remove(prefix+"error_"+repo)
                    .putLong(prefix+"last_check",System.currentTimeMillis()).apply();
        }
        return changes;
    }
    private static JSONArray oldArtifacts(Context context,String key,long runId){
        try{
            JSONArray rows=new JSONArray(prefs(context).getString(key,"[]"));
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);
                if(row!=null&&row.optLong("id")==runId)return row.optJSONArray("artifacts");
            }
        }catch(Exception ignored){}
        return null;
    }
    private static String artifactIds(JSONArray artifacts){
        StringBuilder b=new StringBuilder();
        for(int i=0;i<artifacts.length();i++){
            JSONObject a=artifacts.optJSONObject(i);
            if(a!=null)b.append(a.optLong("id",0L)).append(',');
        }
        return b.toString();
    }
}
