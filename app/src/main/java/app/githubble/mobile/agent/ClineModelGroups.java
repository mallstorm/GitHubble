package app.githubble.mobile.agent;
import org.json.*;
import java.util.*;

/** Live recommendation groups are authoritative even when free IDs have no :free suffix. */
public final class ClineModelGroups {
    public static JSONObject classify(Object generic,JSONObject recommended)throws Exception{
        Set<String> all=new LinkedHashSet<>(),free=new LinkedHashSet<>(),pass=new LinkedHashSet<>();
        extract(generic,all);
        JSONObject r=recommended==null?new JSONObject():recommended;
        if(r.optJSONObject("data")!=null)r=r.getJSONObject("data");
        extract(r.opt("free"),free);extract(r.opt("clinePass"),pass);
        for(String id:all){if(isFree(id))free.add(id);else if(id.startsWith("cline-pass/"))pass.add(id);}
        Set<String> billing=new LinkedHashSet<>(all);billing.removeAll(free);billing.removeAll(pass);
        return new JSONObject().put("Free",new JSONArray(free)).put("Pass",new JSONArray(pass)).put("Billing",new JSONArray(billing));
    }
    public static boolean isFree(String id){return id.startsWith("cline-free/")||id.endsWith(":free")||id.toLowerCase(Locale.ROOT).contains("/free/");}
    public static void extract(Object o,Set<String> ids){
        if(o instanceof JSONArray){JSONArray a=(JSONArray)o;for(int i=0;i<a.length();i++)extract(a.opt(i),ids);}
        else if(o instanceof JSONObject){JSONObject j=(JSONObject)o;
            if(j.has("data")||j.has("models")){extract(j.opt("data"),ids);extract(j.opt("models"),ids);return;}
            String id=j.optString("id",j.optString("model",j.optString("name")));
            if(!id.isEmpty()){if(j.optBoolean("supported_in_api",true)&&!"hide".equals(j.optString("visibility")))add(ids,id);return;}
            Iterator<String> keys=j.keys();while(keys.hasNext()){String key=keys.next();if(j.opt(key) instanceof JSONObject)add(ids,key);}
        } else if(o instanceof String)add(ids,(String)o);
    }
    private static void add(Set<String> ids,String id){if(!id.isBlank()&&id.length()<=160&&!id.contains("\n"))ids.add(id);}
}
