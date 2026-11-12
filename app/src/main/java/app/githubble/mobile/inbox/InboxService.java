package app.githubble.mobile.inbox;

import app.githubble.mobile.network.GitHubApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the Inbox from the official notifications API or, when it is unavailable, from search. */
public final class InboxService {
    private InboxService(){}

    /** True when the status means "this token type cannot use /notifications". */
    public static boolean tokenNotSupported(Exception error){
        if(!(error instanceof GitHubApiClient.ApiException))return false;
        int code=((GitHubApiClient.ApiException)error).statusCode;
        return code==401||code==403||code==404;
    }

    public static List<InboxItem> fetchOfficial(GitHubApiClient client,boolean includeRead) throws Exception{
        JSONArray array=client.getArray("https://api.github.com/notifications?all="+includeRead+"&per_page=50");
        List<InboxItem> items=new ArrayList<>();
        for(int i=0;i<array.length();i++){
            InboxItem item=InboxItem.fromThread(array.optJSONObject(i));
            if(item!=null)items.add(item);
        }
        return items;
    }

    public static void markThreadRead(GitHubApiClient client,String threadId) throws Exception{
        client.patch("https://api.github.com/notifications/threads/"+threadId,null);
    }

    public static void markAllRead(GitHubApiClient client) throws Exception{
        client.put("https://api.github.com/notifications",null);
    }

    public static String[][] searchQueries(String login){
        return new String[][]{
                {"review-requested:"+login+" is:pr is:open","Просят ревью"},
                {"assignee:"+login+" is:open","Назначено вам"},
                {"mentions:"+login+" is:open -author:"+login,"Упоминание"},
                {"author:"+login+" is:open comments:>0","Ваша тема"}};
    }

    public static List<InboxItem> fetchSearch(GitHubApiClient client,String login) throws Exception{
        List<InboxItem> all=new ArrayList<>();
        Exception last=null;int failures=0;
        for(String[] query:searchQueries(login)){
            try{
                JSONObject root=client.getJson("https://api.github.com/search/issues?q="
                        +URLEncoder.encode(query[0],"UTF-8")+"&sort=updated&order=desc&per_page=25");
                JSONArray found=root.optJSONArray("items");
                if(found==null)continue;
                for(int i=0;i<found.length();i++){
                    InboxItem item=InboxItem.fromSearch(found.optJSONObject(i),query[1]);
                    if(item!=null)all.add(item);
                }
            }catch(Exception e){last=e;failures++;}
        }
        if(failures==searchQueries(login).length&&last!=null)throw last;
        return merge(all);
    }

    /** Same issue found by several queries becomes one row with several reasons, newest first. */
    public static List<InboxItem> merge(List<InboxItem> items){
        Map<String,InboxItem> byKey=new LinkedHashMap<>();
        for(InboxItem item:items){
            InboxItem known=byKey.get(item.key);
            if(known==null)byKey.put(item.key,item);else known.reasons.addAll(item.reasons);
        }
        List<InboxItem> merged=new ArrayList<>(byKey.values());
        Collections.sort(merged,new Comparator<InboxItem>(){
            @Override public int compare(InboxItem a,InboxItem b){return b.updatedAt.compareTo(a.updatedAt);}
        });
        return merged;
    }
}
