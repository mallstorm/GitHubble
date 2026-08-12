package app.githubble.mobile.offline;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONObject;

/** Executes a replay-safe mutation immediately or stores it for a later safe retry. */
public final class OfflineMutationDispatcher {
    public static final class Result {
        public final boolean queued;
        public final JSONObject response;
        Result(boolean queued, JSONObject response){this.queued=queued;this.response=response;}
    }
    private final Context context;
    private final GitHubApiClient api;
    private final OfflineActionQueue queue;
    public OfflineMutationDispatcher(Context context, GitHubApiClient api, OfflineActionQueue queue){this.context=context.getApplicationContext();this.api=api;this.queue=queue;}

    public Result executeOrQueue(String method,String url,JSONObject body,String entityKey,String summary,String conflictUrl,String versionField,String baseVersion)throws Exception{
        if(isOnline()){
            try{return new Result(false,api.executeJson(method,url,body,true));}
            catch(GitHubApiClient.ApiException e){if(e.statusCode!=408&&e.statusCode!=429&&e.statusCode<500)throw e;}
            catch(java.io.IOException ignored){ }
        }
        OfflineActionQueue.Action action=new OfflineActionQueue.Action();
        action.method=method;action.url=url;action.body=body==null?"{}":body.toString();action.entityKey=entityKey;action.summary=summary;
        action.conflictUrl=conflictUrl==null?"":conflictUrl;action.versionField=versionField==null?"updated_at":versionField;action.baseVersion=baseVersion==null?"":baseVersion;
        queue.enqueue(action);
        JSONObject marker=new JSONObject();marker.put("queued",true);marker.put("summary",summary);return new Result(true,marker);
    }

    public boolean isOnline(){
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);if(cm==null)return true;
        Network network=cm.getActiveNetwork();if(network==null)return false;NetworkCapabilities caps=cm.getNetworkCapabilities(network);
        return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
