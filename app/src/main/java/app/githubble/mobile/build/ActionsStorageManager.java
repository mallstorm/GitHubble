package app.githubble.mobile.build;

import android.content.SharedPreferences;
import app.githubble.mobile.network.GitHubApiClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/**
 * GitHub repository retention is a server-side policy (new items only).
 * Local cleanup rules never pretend to alter GitHub billing or storage quotas:
 * they only prepare an explicitly confirmed deletion preview.
 */
public final class ActionsStorageManager {
    private final GitHubApiClient api;
    private final SharedPreferences prefs;

    public static final class Retention {
        public final int days, maxDays;
        public Retention(int days,int maxDays){this.days=days;this.maxDays=maxDays;}
    }
    public static final class Policy {
        public final int keepLatest, minAgeDays, softLimitMb;
        public Policy(int keepLatest,int minAgeDays,int softLimitMb){
            if(keepLatest<1||keepLatest>100||minAgeDays<0||minAgeDays>3650
                    ||softLimitMb<0||softLimitMb>100000)
                throw new IllegalArgumentException("Некорректные параметры правила хранения");
            this.keepLatest=keepLatest;this.minAgeDays=minAgeDays;this.softLimitMb=softLimitMb;
        }
    }
    public static final class Artifact {
        public final long id, sizeBytes, runId;
        public final String name, createdAt, expiresAt;
        public final boolean expired;
        public Artifact(JSONObject json){
            id=json.optLong("id",0);
            JSONObject run=json.optJSONObject("workflow_run");
            runId=run==null?0L:run.optLong("id",0L);
            name=json.optString("name","Артефакт");
            sizeBytes=Math.max(0,json.optLong("size_in_bytes",0));
            createdAt=json.optString("created_at","");
            expiresAt=json.optString("expires_at","");
            expired=json.optBoolean("expired",false);
        }
    }
    public static final class Page {
        public final List<Artifact> artifacts;
        public final long totalCount, visibleBytes;
        public final boolean incomplete;
        Page(List<Artifact> artifacts,long totalCount,long visibleBytes,boolean incomplete){
            this.artifacts=artifacts;this.totalCount=totalCount;
            this.visibleBytes=visibleBytes;this.incomplete=incomplete;
        }
    }

    public ActionsStorageManager(GitHubApiClient api,SharedPreferences prefs){
        this.api=api;this.prefs=prefs;
    }
    private static String base(String full){
        if(full==null||!full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Некорректное имя репозитория");
        return "https://api.github.com/repos/"+full+"/actions/";
    }
    private String key(String full){
        base(full);
        return "actions_storage_policy_"+api.accountScope()+"_"+full;
    }
    public Retention loadRetention(String full)throws Exception{
        JSONObject r=api.getJson(base(full)+"permissions/artifact-and-log-retention");
        return new Retention(r.optInt("days",-1),r.optInt("maximum_allowed_days",-1));
    }
    public Retention setRetention(String full,int days)throws Exception{
        Retention current=loadRetention(full);
        if(days<1||(current.maxDays>0&&days>current.maxDays))
            throw new IllegalArgumentException("GitHub допускает срок хранения от 1 до "+current.maxDays+" дней");
        api.put(base(full)+"permissions/artifact-and-log-retention",new JSONObject().put("days",days));
        api.invalidatePrefix(base(full)+"permissions/");
        return loadRetention(full);
    }

    /** Cache quota is independent of the 500 MB artifacts/Packages allowance. */
    public int loadCacheStorageLimitGb(String full)throws Exception{
        return api.getJson(base(full)+"cache/storage-limit").optInt("max_cache_size_gb",-1);
    }
    public int setCacheStorageLimitGb(String full,int gb)throws Exception{
        if(gb<1||gb>10000)throw new IllegalArgumentException("Укажите от 1 до 10000 ГБ");
        api.put(base(full)+"cache/storage-limit",new JSONObject().put("max_cache_size_gb",gb));
        api.invalidatePrefix(base(full)+"cache/storage-limit");
        return loadCacheStorageLimitGb(full);
    }
    public int loadCacheRetentionDays(String full)throws Exception{
        return api.getJson(base(full)+"cache/retention-limit")
                .optInt("max_cache_retention_days",-1);
    }
    public int setCacheRetentionDays(String full,int days)throws Exception{
        if(days<1||days>400)throw new IllegalArgumentException("Укажите от 1 до 400 дней");
        api.put(base(full)+"cache/retention-limit",new JSONObject().put("max_cache_retention_days",days));
        api.invalidatePrefix(base(full)+"cache/retention-limit");
        return loadCacheRetentionDays(full);
    }

    public Policy loadPolicy(String full){
        String raw=prefs.getString(key(full),"");
        if(raw.isEmpty())return new Policy(3,7,0);
        try{
            JSONObject j=new JSONObject(raw);
            return new Policy(j.optInt("keep_latest",3),j.optInt("min_age_days",7),
                    j.optInt("soft_limit_mb",0));
        }catch(Exception e){return new Policy(3,7,0);}
    }
    public void savePolicy(String full,Policy policy)throws Exception{
        JSONObject j=new JSONObject().put("keep_latest",policy.keepLatest)
                .put("min_age_days",policy.minAgeDays)
                .put("soft_limit_mb",policy.softLimitMb);
        if(!prefs.edit().putString(key(full),j.toString()).commit())
            throw new IllegalStateException("Не удалось сохранить правило на устройстве");
    }
    /**
     * Enumerates all artifacts, not only those attached to recent workflow runs.
     * Caller can request further pages when GitHub reports more results.
     */
    public Page listArtifacts(String full,int maxPages)throws Exception{
        if(maxPages<1||maxPages>100)throw new IllegalArgumentException("Недопустимое число страниц");
        List<Artifact> artifacts=new ArrayList<>();
        long total=0,bytes=0;
        for(int page=1;page<=maxPages;page++){
            JSONObject j=api.getJson(base(full)+"artifacts?per_page=100&page="+page);
            if(page==1)total=j.optLong("total_count",0);
            org.json.JSONArray a=j.optJSONArray("artifacts");
            if(a==null)throw new IllegalStateException("GitHub не вернул список артефактов");
            for(int i=0;i<a.length();i++){
                JSONObject x=a.optJSONObject(i);
                if(x==null)continue;
                Artifact art=new Artifact(x);
                artifacts.add(art);bytes+=art.sizeBytes;
            }
            if(a.length()<100)break;
        }
        artifacts.sort(Comparator.comparing((Artifact a)->a.createdAt).reversed());
        return new Page(artifacts,total,bytes,total>artifacts.size());
    }
    public List<Artifact> previewCleanup(Page page,Policy policy){
        if(page.incomplete)throw new IllegalStateException(
                "Список неполный: нельзя безопасно рассчитать правило удаления");
        List<Artifact> result=new ArrayList<>();
        List<Artifact> sorted=new ArrayList<>(page.artifacts);
        sorted.sort(Comparator.comparing((Artifact a)->a.createdAt).reversed());
        long now=System.currentTimeMillis();
        long bytes=page.visibleBytes;
        long threshold=(long)policy.softLimitMb*1024L*1024L;
        for(int i=0;i<sorted.size();i++){
            Artifact art=sorted.get(i);
            if(i<policy.keepLatest)continue;
            long age;
            try{age=now-Instant.parse(art.createdAt).toEpochMilli();}
            catch(Exception error){continue;} // Unknown age must never be auto-selected.
            if(age<policy.minAgeDays*86400000L)continue;
            // Zero threshold: delete all eligible old items. Otherwise prune to the soft target.
            if(policy.softLimitMb==0||bytes>threshold){
                result.add(art);
                bytes-=art.sizeBytes;
            }
        }
        return result;
    }
    public void deleteArtifact(String full,long artifactId)throws Exception{
        if(artifactId<=0)throw new IllegalArgumentException("Некорректный ID артефакта");
        api.delete(base(full)+"artifacts/"+artifactId);
        api.invalidatePrefix(base(full)+"artifacts");
    }
}
