package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Repository use-cases extracted from the Activity. */
public final class RepositoryController {
    public static final class Overview { public final JSONObject repository; public final String readme; public Overview(JSONObject r,String m){repository=r;readme=m;} }
    private final GitHubApiClient api;
    public RepositoryController(GitHubApiClient api){this.api=api;}
    public Overview loadOverview(String full) throws Exception {
        JSONObject r=api.getJson("https://api.github.com/repos/"+full); String readme="";
        try{JSONObject m=api.getJson("https://api.github.com/repos/"+full+"/readme");readme=new String(Base64.getMimeDecoder().decode(m.getString("content")), StandardCharsets.UTF_8);}catch(Exception ignored){}
        return new Overview(r,readme);
    }
    public JSONObject createPullRequest(String full,String title,String body,String head,String base,boolean draft)throws Exception{JSONObject p=new JSONObject();p.put("title",title);p.put("body",body);p.put("head",head);p.put("base",base);p.put("draft",draft);JSONObject result=api.postJson("https://api.github.com/repos/"+full+"/pulls",p,true);api.invalidatePrefix("https://api.github.com/repos/"+full+"/pulls");return result;}
    public JSONObject createReview(String full,int number,String body,String event)throws Exception{JSONObject p=new JSONObject();p.put("body",body);p.put("event",event);JSONObject result=api.postJson("https://api.github.com/repos/"+full+"/pulls/"+number+"/reviews",p,true);api.invalidatePrefix("https://api.github.com/repos/"+full+"/pulls/"+number);return result;}
    public JSONObject merge(String full,int number,String method,String title,String message,String sha)throws Exception{JSONObject p=new JSONObject();if(method!=null&&!method.isEmpty())p.put("merge_method",method);if(title!=null&&!title.isEmpty())p.put("commit_title",title);if(message!=null&&!message.isEmpty())p.put("commit_message",message);if(sha!=null&&!sha.isEmpty())p.put("sha",sha);JSONObject result=api.putJson("https://api.github.com/repos/"+full+"/pulls/"+number+"/merge",p);api.invalidatePrefix("https://api.github.com/repos/"+full+"/pulls");api.invalidate("https://api.github.com/repos/"+full);return result;}
    public JSONArray labels(String full)throws Exception{return api.getArray("https://api.github.com/repos/"+full+"/labels?per_page=100");}
    public JSONArray assignees(String full)throws Exception{return api.getArray("https://api.github.com/repos/"+full+"/assignees?per_page=100");}
    public JSONArray milestones(String full)throws Exception{return api.getArray("https://api.github.com/repos/"+full+"/milestones?state=open&per_page=100");}
    public JSONObject updateIssueMetadata(String full,int number,JSONArray labels,JSONArray assignees,Integer milestone)throws Exception{JSONObject p=new JSONObject();p.put("labels",labels==null?new JSONArray():labels);p.put("assignees",assignees==null?new JSONArray():assignees);if(milestone==null)p.put("milestone",JSONObject.NULL);else p.put("milestone",milestone);JSONObject result=api.patchJson("https://api.github.com/repos/"+full+"/issues/"+number,p);api.invalidatePrefix("https://api.github.com/repos/"+full+"/issues");api.invalidatePrefix("https://api.github.com/repos/"+full+"/pulls");return result;}
    public boolean isStarred(String full){try{api.getText("https://api.github.com/user/starred/"+full);return true;}catch(Exception e){return false;}}
    public void setStarred(String full,boolean enabled)throws Exception{String url="https://api.github.com/user/starred/"+full;if(enabled)api.putEmpty(url);else api.delete(url);api.invalidate(url);api.invalidate("https://api.github.com/repos/"+full);}
}
