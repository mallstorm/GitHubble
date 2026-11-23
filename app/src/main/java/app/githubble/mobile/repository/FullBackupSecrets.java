package app.githubble.mobile.repository;

import app.githubble.mobile.mirror.MirrorSecrets;
import app.githubble.mobile.network.GitHubApiClient;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONObject;

/** A one-time sealed credential, never stored in the portable archive or workflow inputs. */
public final class FullBackupSecrets {
    private FullBackupSecrets(){}
    private static final String API="https://api.github.com/repos/";
    public static String name(String id) {
        if(id==null||!id.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Некорректный ID восстановления");
        return "GITHUBBLE_FULL_RESTORE_"+id.toUpperCase(Locale.ROOT);
    }
    public static void put(GitHubApiClient api,String repository,String id,String token)throws Exception {
        if(token==null||token.isEmpty())throw new SecurityException("Требуется GitHub OAuth-токен");
        JSONObject publicKey=MirrorSecrets.publicKey(api,repository);
        byte[] raw=token.getBytes(StandardCharsets.UTF_8);
        try{
            JSONObject message=new JSONObject()
                    .put("key_id",publicKey.getString("key_id"))
                    .put("encrypted_value",MirrorSecrets.encryptForGitHub(raw,publicKey.getString("key")));
            api.putJson(API+repository+"/actions/secrets/"+name(id),message);
        }finally{Arrays.fill(raw,(byte)0);}
    }
    public static void delete(GitHubApiClient api,String repository,String id)throws Exception {
        try{api.delete(API+repository+"/actions/secrets/"+name(id));}
        catch(GitHubApiClient.ApiException e){if(e.statusCode!=404)throw e;}
    }
}
