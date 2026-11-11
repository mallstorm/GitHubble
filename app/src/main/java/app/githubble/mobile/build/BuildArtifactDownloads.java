package app.githubble.mobile.build;

import android.app.DownloadManager;
import android.net.ConnectivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import org.json.JSONObject;
import app.githubble.mobile.security.SecureStore;

/** Resolve the one-minute Actions download URL on demand, never forward OAuth to CDN. */
public final class BuildArtifactDownloads {
    private BuildArtifactDownloads(){}
    private static String filename(String name){
        String cleaned=(name==null?"artifact":name).replaceAll("[^A-Za-z0-9._-]","_");
        if(cleaned.length()>75)cleaned=cleaned.substring(0,75);
        return cleaned.isEmpty()?"artifact":cleaned;
    }
    public static long enqueue(Context context,String repo,JSONObject artifact)throws Exception {
        return enqueue(context,repo,artifact,false);
    }
    public static long enqueue(Context context,String repo,JSONObject artifact,
                               boolean automatic)throws Exception {
        return enqueue(context,repo,artifact,automatic,0L);
    }
    public static long enqueue(Context context,String repo,JSONObject artifact,
                               boolean automatic,long runId)throws Exception {
        return enqueue(context,repo,artifact,automatic,runId,null);
    }
    /** @param expect optional identity the delivered APK must match (package, version_code, version_name) */
    public static long enqueue(Context context,String repo,JSONObject artifact,
                               boolean automatic,long runId,JSONObject expect)throws Exception {
        long id=artifact.optLong("id",0);
        if(id<=0||repo==null||!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Некорректный артефакт GitHub");
        if(automatic){
            ConnectivityManager connectivity=(ConnectivityManager)context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if(connectivity==null||connectivity.getActiveNetworkInfo()==null||
                    !connectivity.getActiveNetworkInfo().isConnected()||
                    connectivity.isActiveNetworkMetered())
                throw new IllegalStateException("Автосохранение ожидает Wi-Fi или другую безлимитную сеть");
        }
        if(artifact.optBoolean("expired",false))throw new IllegalStateException(
                "Срок хранения артефакта истёк");
        String token=new SecureStore(context).get("github_token");
        if(token.isEmpty())throw new IllegalStateException("Требуется вход в GitHub");
        URL api=new URL("https://api.github.com/repos/"+repo+
                "/actions/artifacts/"+id+"/zip");
        HttpURLConnection conn=(HttpURLConnection)api.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept","application/vnd.github+json");
        conn.setRequestProperty("Authorization","Bearer "+token);
        conn.setRequestProperty("User-Agent","GitHubble-Android");
        conn.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
        String signed;
        try{
            int code=conn.getResponseCode();
            if(code==410)throw new IllegalStateException("Артефакт удалён GitHub");
            if(code==401||code==403)throw new IllegalStateException(
                    "Нет доступа к Actions: необходимо разрешение Actions: Read");
            if(code!=302&&code!=301&&code!=307)
                throw new IllegalStateException("GitHub вернул HTTP "+code);
            signed=conn.getHeaderField("Location");
        }finally{conn.disconnect();}
        if(signed==null)throw new IllegalStateException("GitHub не выдал ссылку");
        Uri url=Uri.parse(signed);
        if(!"https".equalsIgnoreCase(url.getScheme())||url.getHost()==null||
                url.getHost().isEmpty()||url.getUserInfo()!=null)
            throw new IllegalStateException("Небезопасная ссылка загрузки");
        // GitHub's signed Actions URL is handed to DownloadManager WITHOUT credentials.
        String local=filename(artifact.optString("name","artifact"))+"-"+id+".zip";
        DownloadManager.Request request=new DownloadManager.Request(url);
        request.setTitle(local);
        request.setDescription(repo+" · GitHub Actions");
        request.setMimeType("application/zip");
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setAllowedOverMetered(!automatic);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "GitHubble/"+local);
        DownloadManager dm=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
        if(dm==null)throw new IllegalStateException("Загрузки Android недоступны");
        long downloadId=dm.enqueue(request);
        SharedPreferences.Editor edit=BuildMonitor.prefs(context).edit();
        JSONObject info=new JSONObject().put("repo",repo).put("name",local)
                .put("artifact_id",id).put("automatic",automatic)
                .put("run_id",runId).put("account",BuildMonitor.account(context));
        if(expect!=null&&expect.length()>0)info.put("expect",expect);
        edit.putString("build_download_"+downloadId,info.toString()).commit();
        return downloadId;
    }
}
