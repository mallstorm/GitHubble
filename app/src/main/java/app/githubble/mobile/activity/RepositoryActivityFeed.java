package app.githubble.mobile.activity;

import android.content.SharedPreferences;

import app.githubble.mobile.network.GitHubApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Private-friendly repository activity: authenticated Contents API, not only
 * /users/{login}/events (that endpoint omits private events).
 * Ranking measures the latest push, GitHub event or local repository visit;
 * creation time is only a fallback for a repository without other activity.
 */
public final class RepositoryActivityFeed {
    public static final int MAX_COMMIT_REPOSITORIES = 20;
    public static final int COMMITS_PER_REPOSITORY = 40;
    public static final int MAX_TIMELINE = 180;
    private static final long WINDOW_MILLIS = 14L * 24L * 60L * 60L * 1000L;
    private static final String VISIT_PREFIX = "activity_repo_visit_";

    public static final class CommitResult {
        public final List<JSONObject> events;
        public final int queriedRepositories;
        public final int skippedRepositories;
        public final int inaccessibleRepositories;
        public final boolean interrupted;
        public final String firstError;

        CommitResult(List<JSONObject> events,int queriedRepositories,int skippedRepositories,
                     int inaccessibleRepositories,boolean interrupted,String firstError) {
            this.events=events;
            this.queriedRepositories=queriedRepositories;
            this.skippedRepositories=skippedRepositories;
            this.inaccessibleRepositories=inaccessibleRepositories;
            this.interrupted=interrupted;
            this.firstError=firstError;
        }

        public String coverageLabel(){
            String label="Коммиты: проверено "+queriedRepositories+" репозиториев";
            if(skippedRepositories>0)label+=", ещё "+skippedRepositories+" вне выборки";
            if(inaccessibleRepositories>0)label+=", нет доступа к "+inaccessibleRepositories;
            if(interrupted)label+="; загрузка прервана";
            if(!firstError.isEmpty())label+=". "+firstError;
            return label;
        }
    }

    private RepositoryActivityFeed(){}

    public static String visitKey(String account,String repository){
        return VISIT_PREFIX+account+"_"+repository;
    }

    public static void noteVisit(SharedPreferences prefs,String account,String repository,long when){
        if(prefs==null||account==null||account.isEmpty()||repository==null
                ||repository.isEmpty()||when<=0L)return;
        prefs.edit().putLong(visitKey(account,repository),when).apply();
    }

    public static void clearVisits(SharedPreferences prefs,String account){
        String prefix=VISIT_PREFIX+account+"_";
        SharedPreferences.Editor edit=prefs.edit();
        for(String key:prefs.getAll().keySet())if(key.startsWith(prefix))edit.remove(key);
        edit.apply();
    }

    public static long epoch(String iso){
        if(iso==null||iso.isEmpty())return 0L;
        try{return Math.max(0L,Instant.parse(iso).toEpochMilli());}
        catch(Exception ignored){return 0L;}
    }

    public static long rankTime(JSONObject repository,List<JSONObject> feed,long lastVisit){
        String full=repository.optString("full_name","");
        // pushed_at indicates real Git activity; updated_at can change after
        // metadata edits, so do not use it when an actual push time exists.
        long pushed=epoch(repository.optString("pushed_at",""));
        long newest=Math.max(pushed,lastVisit);
        if(newest==0L)newest=epoch(repository.optString("created_at",""));
        for(JSONObject event:feed){
            if(full.equals(event.optString("repo","")))
                newest=Math.max(newest,epoch(event.optString("updated_at","")));
        }
        return newest;
    }

    public static List<JSONObject> rank(JSONArray repositories,List<JSONObject> feed,
                                        SharedPreferences prefs,String account){
        List<JSONObject> ranked=new ArrayList<>();
        if(repositories==null)return ranked;
        Map<String,Long> eventTimes=new HashMap<>();
        Map<String,String> latestTitles=new HashMap<>();
        for(JSONObject event:feed){
            if(event==null)continue;
            String repo=event.optString("repo","");
            long when=epoch(event.optString("updated_at",""));
            if(repo.isEmpty()||when<=eventTimes.getOrDefault(repo,0L))continue;
            eventTimes.put(repo,when);
            latestTitles.put(repo,event.optString("title",""));
        }
        for(int i=0;i<repositories.length();i++){
            JSONObject repo=repositories.optJSONObject(i);
            if(repo==null)continue;
            String full=repo.optString("full_name","");
            if(full.isEmpty())continue;
            long push=epoch(repo.optString("pushed_at",""));
            long seen=(prefs==null||account==null)?0L:
                    prefs.getLong(visitKey(account,full),0L);
            long activity=eventTimes.getOrDefault(full,0L);
            long newest=Math.max(Math.max(push,seen),activity);
            String reason=activity>=push&&activity>=seen&&activity>0L
                    ?"Последнее событие":seen>=push&&seen>0L
                    ?"Открывался в GitHubble":push>0L
                    ?"Последний push":"Создан";
            if(newest==0L)newest=epoch(repo.optString("created_at",""));
            try{
                JSONObject row=new JSONObject();
                row.put("repo",full);
                row.put("updated_at",newest>0L?Instant.ofEpochMilli(newest).toString():"");
                row.put("reason",reason);
                row.put("private",repo.optBoolean("private",false));
                row.put("last_event",latestTitles.getOrDefault(full,""));
                ranked.add(row);
            }catch(Exception ignored){}
        }
        ranked.sort((a,b)->{
            int comparison=Long.compare(epoch(b.optString("updated_at","")),
                    epoch(a.optString("updated_at","")));
            return comparison!=0?comparison:a.optString("repo","").compareToIgnoreCase(
                    b.optString("repo",""));
        });
        return ranked;
    }

    public static List<JSONObject> rankForFetch(JSONArray repositories,SharedPreferences prefs,
                                                 String account){
        return rank(repositories,Collections.emptyList(),prefs,account);
    }

    /**
     * GitHub's commit message is shown verbatim, never replaced by a guessed
     * changelog. The official commit URL and author/timestamp are preserved.
     */
    public static JSONObject parseCommit(String repository,JSONObject source){
        if(repository==null||source==null
                ||!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))return null;
        String sha=source.optString("sha","");
        if(!sha.matches("[0-9a-fA-F]{40}"))return null;
        JSONObject details=source.optJSONObject("commit");
        if(details==null)return null;
        String message=details.optString("message","").trim();
        JSONObject person=details.optJSONObject("committer");
        if(person==null)person=details.optJSONObject("author");
        String date=person==null?"":person.optString("date","");
        if(epoch(date)==0L)return null;
        int newline=message.indexOf('\n');
        String subject=(newline>=0?message.substring(0,newline):message).trim();
        if(subject.isEmpty())subject="Коммит "+sha.substring(0,8);
        String body=newline>=0?message.substring(newline+1).trim():"";
        JSONObject actor=source.optJSONObject("author");
        String who=actor==null?"":actor.optString("login","");
        if(who.isEmpty()){
            JSONObject author=details.optJSONObject("author");
            who=author==null?"":author.optString("name","");
        }
        try{
            JSONObject item=new JSONObject();
            item.put("repo",repository);
            item.put("kind","Commit");
            item.put("event_type","CommitEvent");
            item.put("sha",sha);
            item.put("event_id","commit:"+repository+":"+sha);
            item.put("title",subject);
            item.put("summary",body);
            item.put("author",who);
            item.put("updated_at",date);
            item.put("commit_url","https://github.com/"+repository+"/commit/"+sha);
            return item;
        }catch(Exception ignored){return null;}
    }

    public static CommitResult loadCommits(GitHubApiClient api,JSONArray repositories,
                                           SharedPreferences prefs,String account) {
        List<JSONObject> found=new ArrayList<>();
        List<JSONObject> ranked=rankForFetch(repositories,prefs,account);
        int cap=Math.min(MAX_COMMIT_REPOSITORIES,ranked.size());
        int processed=0,inaccessible=0;
        String firstError="";
        Set<String> seen=new LinkedHashSet<>();
        boolean interrupted=false;
        String since=Instant.ofEpochMilli(System.currentTimeMillis()-WINDOW_MILLIS).toString();
        for(int i=0;i<cap;i++){
            if(Thread.currentThread().isInterrupted()){interrupted=true;break;}
            String full=ranked.get(i).optString("repo","");
            if(!full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))continue;
            processed++;
            try{
                // Authenticated repository API includes private commits permitted by
                // the current GitHub App installation (Contents: Read).
                JSONArray commits=api.getArray("https://api.github.com/repos/"+full+
                        "/commits?per_page="+COMMITS_PER_REPOSITORY+
                        "&since="+URLEncoder.encode(since,StandardCharsets.UTF_8.name()));
                for(int j=0;j<commits.length();j++){
                    JSONObject source=commits.optJSONObject(j);
                    JSONObject event=parseCommit(full,source);
                    if(event==null)continue;
                    String key=event.optString("event_id","");
                    if(seen.add(key))found.add(event);
                }
            }catch(Exception error){
                inaccessible++;
                if(firstError.isEmpty()){
                    String reason=error.getMessage();
                    firstError="Часть коммитов недоступна"+(reason==null?"":": "+reason);
                }
            }
        }
        found.sort((a,b)->Long.compare(epoch(b.optString("updated_at","")),
                epoch(a.optString("updated_at",""))));
        return new CommitResult(found,processed,Math.max(0,ranked.size()-cap),
                inaccessible,interrupted,firstError);
    }
}
