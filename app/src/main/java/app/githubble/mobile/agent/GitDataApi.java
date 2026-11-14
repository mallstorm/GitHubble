package app.githubble.mobile.agent;

import org.json.JSONObject;

/** Small injectable Git Data boundary; production clients remain pinned to the GitHub session. */
public interface GitDataApi {
    JSONObject get(String url)throws Exception;
    JSONObject post(String url,JSONObject value)throws Exception;
    JSONObject patch(String url,JSONObject value)throws Exception;
    static GitDataApi bound(app.githubble.mobile.network.GitHubApiClient client,Runnable guard) {
        return new GitDataApi(){
            public JSONObject get(String url)throws Exception{guard.run();return client.getJsonFresh(url);}
            public JSONObject post(String url,JSONObject value)throws Exception{guard.run();return client.postJson(url,value,true);}
            public JSONObject patch(String url,JSONObject value)throws Exception{guard.run();return client.patchJson(url,value);}
        };
    }
    static String base(String repository){AgentGitWorkspace.validateRepository(repository,"main");return "https://api.github.com/repos/"+repository;}
    static String encode(String value){try{return java.net.URLEncoder.encode(value,"UTF-8").replace("+","%20");}catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}}
}
