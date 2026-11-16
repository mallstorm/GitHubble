package app.githubble.mobile.mirror;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pair registry separate from the OneShot run history.
 *
 * A pair is undirected. GitHub account login (not the selected UI account) determines
 * ownership. No GitHub tokens or credentials are stored here.
 *
 * This registry survives clearing run history. After app data removal users can safely
 * recover a pair by verifying the repos' common commit, without privileged GitHub APIs.
 */
public final class MirrorLinkRegistry {
    private static final String PREFS="githubble_mirror_links_v1";
    private static final String KEY="links";
    private final SharedPreferences prefs;
    private final Object lock=new Object();

    public MirrorLinkRegistry(Context context){
        prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    public static final class Link {
        public String id="",left="",right="",original="",copyJobId="";
        public long createdAt,updatedAt;
        public JSONObject json(){
            JSONObject j=new JSONObject();
            try{
                j.put("id",id);j.put("left",left);j.put("right",right);
                j.put("original",original);j.put("copyJobId",copyJobId);
                j.put("createdAt",createdAt);j.put("updatedAt",updatedAt);
            }catch(Exception ignored){}
            return j;
        }
        public static Link fromJson(JSONObject j){
            Link link=new Link();
            link.id=j.optString("id","");
            link.left=j.optString("left","");
            link.right=j.optString("right","");
            link.original=j.optString("original","");
            link.copyJobId=j.optString("copyJobId","");
            link.createdAt=j.optLong("createdAt");
            link.updatedAt=j.optLong("updatedAt");return link;
        }
        public boolean contains(String repo){
            return left.equalsIgnoreCase(repo)||right.equalsIgnoreCase(repo);
        }
        public String partner(String repo){
            if(left.equalsIgnoreCase(repo))return right;
            if(right.equalsIgnoreCase(repo))return left;
            throw new IllegalArgumentException("Репозиторий не входит в связанную пару");
        }
    }

    public static String pairId(String repoA,String repoB){
        MirrorPolicy.requireDifferent(repoA,repoB);
        String a=repoA.toLowerCase(Locale.ROOT),b=repoB.toLowerCase(Locale.ROOT);
        if(a.compareTo(b)>0){String t=a;a=b;b=t;}
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] value=digest.digest((a+"\n"+b).getBytes(StandardCharsets.UTF_8));
            StringBuilder id=new StringBuilder("pair_");
            for(int i=0;i<16;i++)id.append(String.format(Locale.ROOT,"%02x",value[i]&255));
            return id.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }

    public List<Link> list(){
        synchronized(lock){
            List<Link> items=new ArrayList<>();
            try{
                JSONArray stored=new JSONArray(prefs.getString(KEY,"[]"));
                for(int i=0;i<stored.length();i++){
                    JSONObject item=stored.optJSONObject(i);
                    if(item!=null){
                        Link link=Link.fromJson(item);
                        if(pairId(link.left,link.right).equals(link.id))items.add(link);
                    }
                }
            }catch(Exception ignored){}
            items.sort((a,b)->Long.compare(b.updatedAt,a.updatedAt));
            return items;
        }
    }
    public List<Link> forRepository(String repo){
        if(repo==null||repo.isEmpty())return Collections.emptyList();
        List<Link> found=new ArrayList<>();
        for(Link l:list())if(l.contains(repo))found.add(l);
        return found;
    }
    public Link find(String id){
        if(id==null||id.isEmpty())return null;
        for(Link l:list())if(l.id.equals(id))return l;
        return null;
    }
    public Link findPair(String a,String b){return find(pairId(a,b));}

    /** Upsert never changes the founding source and keeps a stable, direction-free ID. */
    public Link remember(String repoA,String repoB,String original,String copyJobId){
        String id=pairId(repoA,repoB);
        if(!repoA.equalsIgnoreCase(original)&&!repoB.equalsIgnoreCase(original))
            throw new IllegalArgumentException("Исходный репозиторий не входит в пару");
        synchronized(lock){
            List<Link> items=list();
            Link found=null;
            for(Link link:items)if(id.equals(link.id)){found=link;break;}
            if(found==null){
                found=new Link();found.id=id;
                if(repoA.compareToIgnoreCase(repoB)<0){found.left=repoA;found.right=repoB;}
                else{found.left=repoB;found.right=repoA;}
                found.original=original;
                found.createdAt=System.currentTimeMillis();
                items.add(found);
            }
            if(found.copyJobId.isEmpty()&&copyJobId!=null)found.copyJobId=copyJobId;
            found.updatedAt=System.currentTimeMillis();
            JSONArray arr=new JSONArray();
            for(Link item:items)arr.put(item.json());
            if(!prefs.edit().putString(KEY,arr.toString()).commit())
                throw new IllegalStateException("Не удалось сохранить реестр связанных репозиториев");
            return found;
        }
    }
}
