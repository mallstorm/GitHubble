package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONObject;

/** Profile/follow use-cases extracted from the Activity. */
public final class ProfileController {
    private final GitHubApiClient api;
    public ProfileController(GitHubApiClient api){this.api=api;}
    public JSONObject current()throws Exception{return api.getJson("https://api.github.com/user");}
    public JSONObject publicProfile(String login)throws Exception{return api.getJson("https://api.github.com/users/"+login);}
    /** GitHub REST /user: only documented editable fields, never login, avatar or password. */
    public static JSONObject editablePatch(JSONObject original,JSONObject draft)throws Exception{
        if(original==null||draft==null)throw new IllegalArgumentException("Данные профиля отсутствуют");
        JSONObject patch=new JSONObject();
        String[] fields={"name","email","blog","twitter_username","company","location","bio"};
        for(String key:fields){
            if(!draft.has(key))continue;
            String proposed=draft.isNull(key)?"":draft.optString(key,"");
            String previous=original.isNull(key)?"":original.optString(key,"");
            if(!proposed.equals(previous))patch.put(key,proposed);
        }
        if(draft.has("hireable")&&draft.optBoolean("hireable",false)!=original.optBoolean("hireable",false))
            patch.put("hireable",draft.optBoolean("hireable",false));
        return patch;
    }
    public JSONObject updateEditable(JSONObject patch)throws Exception{
        if(patch==null||patch.length()==0)throw new IllegalArgumentException("Нет изменений для сохранения");
        JSONObject updated=api.patchJson("https://api.github.com/user",patch);
        api.invalidate("https://api.github.com/user");
        if(!updated.has("login"))return current();
        return updated;
    }

    /** null means unknown (network/auth/permission error); only HTTP 404 means false. */
    public Boolean isFollowing(String login){try{api.getText("https://api.github.com/user/following/"+login);return true;}catch(GitHubApiClient.ApiException e){return e.statusCode==404?Boolean.FALSE:null;}catch(Exception e){return null;}}
    public void setFollowing(String login,boolean enabled)throws Exception{String url="https://api.github.com/user/following/"+login;if(enabled)api.putEmpty(url);else api.delete(url);api.invalidate(url);api.invalidate("https://api.github.com/users/"+login);}
}
