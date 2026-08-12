package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONObject;

/** Profile/follow use-cases extracted from the Activity. */
public final class ProfileController {
    private final GitHubApiClient api;
    public ProfileController(GitHubApiClient api){this.api=api;}
    public JSONObject current()throws Exception{return api.getJson("https://api.github.com/user");}
    public JSONObject publicProfile(String login)throws Exception{return api.getJson("https://api.github.com/users/"+login);}
    public boolean isFollowing(String login){try{api.getText("https://api.github.com/user/following/"+login);return true;}catch(Exception e){return false;}}
    public void setFollowing(String login,boolean enabled)throws Exception{String url="https://api.github.com/user/following/"+login;if(enabled)api.putEmpty(url);else api.delete(url);api.invalidate(url);api.invalidate("https://api.github.com/users/"+login);}
}
