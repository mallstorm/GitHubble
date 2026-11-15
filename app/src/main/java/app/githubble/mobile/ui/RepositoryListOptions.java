package app.githubble.mobile.ui;

import org.json.*;
import java.time.Instant;
import java.util.*;

/** Local view state: ordering never discards the active search or combined filters. */
public final class RepositoryListOptions {
    public String visibility="all",language="",query="",order="updated";
    public boolean favoritesOnly;
    public void clearFilters(){visibility="all";language="";favoritesOnly=false;}
    public List<JSONObject> select(JSONArray source,Set<String> favorites){
        List<JSONObject> out=new ArrayList<>();String needle=query.trim().toLowerCase(Locale.ROOT);
        for(int i=0;i<source.length();i++){
            JSONObject r=source.optJSONObject(i);if(r==null||r.optString("full_name").isEmpty())continue;
            String lang=JsonText.of(r,"language","");
            String haystack=(r.optString("full_name")+" "+JsonText.of(r,"description","")+" "+lang).toLowerCase(Locale.ROOT);
            if(!haystack.contains(needle))continue;
            if(visibility.equals("private")&&!r.optBoolean("private"))continue;
            if(visibility.equals("public")&&r.optBoolean("private"))continue;
            if(favoritesOnly&&!favorites.contains(r.optString("full_name")))continue;
            if(!language.isEmpty()&&!language.equalsIgnoreCase(lang.isEmpty()?"Без языка":lang))continue;
            out.add(r);
        }
        out.sort((a,b)->{
            int c=order.equals("name")?0:order.equals("stars")?Long.compare(b.optLong("stargazers_count"),a.optLong("stargazers_count"))
                :Long.compare(updatedAt(b),updatedAt(a));
            return c!=0?c:a.optString("full_name").compareToIgnoreCase(b.optString("full_name"));
        });return out;
    }
    public static List<String> languages(JSONArray source){
        Set<String> out=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for(int i=0;i<source.length();i++){JSONObject r=source.optJSONObject(i);if(r==null)continue;
            String language=JsonText.of(r,"language","");out.add(language.isEmpty()?"Без языка":language);}
        return new ArrayList<>(out);
    }
    public static long updatedAt(JSONObject r){
        for(String field:new String[]{"updated_at","pushed_at"})try{return Instant.parse(r.getString(field)).toEpochMilli();}catch(Exception missing){}
        return 0;
    }
    public static String sortLabel(String value){return value.equals("name")?"По названию":value.equals("stars")?"По звёздам":"Недавно обновлённые";}
}
