package app.githubble.mobile.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Local audit of requests and token usage actually reported by providers.
 * Do not mislabel these statistics as a provider's remaining subscription quota.
 * No prompts, responses, secrets, or OAuth credentials are persisted.
 */
public final class AiUsageLedger {
    private final SharedPreferences prefs;
    public AiUsageLedger(Context context){
        prefs=context.getApplicationContext().getSharedPreferences("git_agent_usage_v1",Context.MODE_PRIVATE);
    }
    private String scope(String owner,String provider){
        if(owner==null||owner.isBlank()||provider==null||provider.isBlank())
            throw new IllegalArgumentException("Аккаунт или провайдер не выбран");
        return Base64.encodeToString((owner+"\u0000"+provider).getBytes(StandardCharsets.UTF_8),
            Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);
    }
    public synchronized void record(String owner,String provider,String model,
            OmniChatChatClient.Usage reported){
        String id=scope(owner,provider);
        JSONObject state;
        try{state=new JSONObject(prefs.getString(id,"{}"));}catch(Exception e){state=new JSONObject();}
        try{
            state.put("provider",provider)
                .put("calls",state.optLong("calls")+1L)
                .put("last_request",System.currentTimeMillis())
                .put("last_model",model==null?"":model.substring(0,Math.min(180,model.length())));
            if(reported!=null&&reported.reported){
                state.put("metered_calls",state.optLong("metered_calls")+1L)
                    .put("input_tokens",state.optLong("input_tokens")+reported.inputTokens)
                    .put("output_tokens",state.optLong("output_tokens")+reported.outputTokens);
            }
            prefs.edit().putString(id,state.toString()).apply();
        }catch(Exception e){throw new IllegalStateException("Не удалось сохранить локальный Usage",e);}
    }
    public synchronized JSONObject snapshot(String owner,String provider){
        if(owner==null||"guest".equals(owner))return new JSONObject();
        try{return new JSONObject(prefs.getString(scope(owner,provider),"{}"));}
        catch(Exception e){return new JSONObject();}
    }
}
