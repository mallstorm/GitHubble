package app.githubble.mobile.mirror;

import app.githubble.mobile.network.GitHubApiClient;
import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** OneShot per-run encrypted credentials in source repository Actions secrets. No keys on disk. */
public final class MirrorSecrets {
    private static final String API="https://api.github.com/repos/";
    private static final String PREFIX="GITHUBBLE_ONESHOT_DST_";
    private MirrorSecrets(){}

    public static String name(String requestId){
        if(requestId==null || !requestId.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid opaque OneShot request ID");
        return PREFIX+requestId.toUpperCase(java.util.Locale.ROOT);
    }

    public static String encryptForGitHub(byte[] message,String publicKeyB64){
        byte[] pk=Base64.getDecoder().decode(publicKeyB64);
        if(pk.length!=32)throw new IllegalArgumentException("GitHub supplied invalid secret public key");
        // libsodium crypto_box_seal is the sealed-box format required by GitHub's API.
        byte[] cipher=new byte[message.length+48];
        try{
            LazySodiumAndroid sodium=new LazySodiumAndroid(new SodiumAndroid());
            if(!sodium.cryptoBoxSeal(cipher,message,message.length,pk))
                throw new SecurityException("Libsodium sealed-box encryption failed");
            return Base64.getEncoder().encodeToString(cipher);
        } finally {
            Arrays.fill(pk,(byte)0);
            Arrays.fill(cipher,(byte)0);
        }
    }

    /** Requires source account Secrets:write. Destination OAuth token is read only from secure store. */
    public static void put(GitHubApiClient source,String repo,String requestId,String destToken)throws Exception{
        if(destToken==null||destToken.isEmpty())
            throw new IllegalStateException("Целевой GitHub-аккаунт необходимо авторизовать повторно");
        JSONObject key=source.getJsonFresh(API+repo+"/actions/secrets/public-key");
        String id=key.optString("key_id","");
        String value=key.optString("key","");
        if(id.isEmpty()||value.isEmpty())throw new SecurityException("Не удалось получить GitHub Actions public key");
        byte[] plaintext=destToken.getBytes(StandardCharsets.UTF_8);
        try{
            JSONObject payload=new JSONObject()
                    .put("key_id",id)
                    .put("encrypted_value",encryptForGitHub(plaintext,value));
            source.putJson(API+repo+"/actions/secrets/"+name(requestId),payload);
        }finally{Arrays.fill(plaintext,(byte)0);}
    }

    public static void delete(GitHubApiClient source,String repo,String requestId)throws Exception{
        try{source.delete(API+repo+"/actions/secrets/"+name(requestId));}
        catch(GitHubApiClient.ApiException e){if(e.statusCode!=404)throw e;}
    }

    /** Only clean up secrets belonging to our OneShot integration, never arbitrary user secrets. */
    public static int sweep(GitHubApiClient source,String repo,long nowEpochMillis)throws Exception{
        int deleted=0;
        for(int page=1;page<=10;page++){
            JSONObject result=source.getJsonFresh(API+repo+"/actions/secrets?per_page=100&page="+page);
            JSONArray list=result.optJSONArray("secrets");
            if(list==null)break;
            for(int i=0;i<list.length();i++){
                JSONObject item=list.optJSONObject(i);if(item==null)continue;
                String n=item.optString("name","");
                if(!n.matches("GITHUBBLE_ONESHOT_DST_[0-9A-F]{32}"))continue;
                String stamp=item.optString("updated_at",item.optString("created_at",""));
                long changed;
                try{changed=java.time.Instant.parse(stamp).toEpochMilli();}
                catch(Exception ignored){continue;}
                // A run may be queued or retrying. Do not destroy credentials prematurely.
                if(nowEpochMillis-changed<6L*60*60*1000)continue;
                try{source.delete(API+repo+"/actions/secrets/"+n);deleted++;}
                catch(GitHubApiClient.ApiException ex){if(ex.statusCode!=404)throw ex;}
            }
            if(list.length()<100)break;
        }
        return deleted;
    }
}
