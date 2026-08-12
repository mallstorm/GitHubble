package app.githubble.mobile.diagnostics;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import app.githubble.mobile.BuildConfig;
import app.githubble.mobile.cache.EntityCache;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.tracking.TrackingDatabase;
import app.githubble.mobile.translation.TranslationCache;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exports a privacy-safe diagnostic bundle; tokens, API keys and signing data are never included. */
public final class DiagnosticsExporter {
    private DiagnosticsExporter() {}

    public static final class Result {
        public final Uri uri; public final String displayName;
        Result(Uri uri,String displayName){this.uri=uri;this.displayName=displayName;}
    }

    public static Result export(Context context, SharedPreferences prefs, EntityCache entityCache,
                                TranslationCache translationCache, TrackingDatabase trackingDb,
                                GitHubApiClient api) throws Exception {
        String name="GitHubble-diagnostics-"+System.currentTimeMillis()+".zip";
        Uri uri; OutputStream raw;
        if(Build.VERSION.SDK_INT>=29){
            ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,name);values.put(MediaStore.Downloads.MIME_TYPE,"application/zip");values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/GitHubble");values.put(MediaStore.Downloads.IS_PENDING,1);
            ContentResolver resolver=context.getContentResolver();uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IllegalStateException("Не удалось создать файл диагностики");raw=resolver.openOutputStream(uri);
        }else{
            File dir=context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);if(dir==null)dir=context.getFilesDir();if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Не удалось создать каталог диагностики");File file=new File(dir,name);uri=Uri.fromFile(file);raw=new FileOutputStream(file);
        }
        if(raw==null)throw new IllegalStateException("Не удалось открыть файл диагностики");
        try(ZipOutputStream zip=new ZipOutputStream(raw)){
            put(zip,"report.json",buildReport(context,prefs,entityCache,translationCache,trackingDb,api).toString(2));
            put(zip,"README.txt","GitHubble diagnostic export\nGenerated: "+Instant.now()+"\nNo access tokens, API keys, passwords, cookies or signing keys are included.\n");
        }
        if(Build.VERSION.SDK_INT>=29){ContentValues done=new ContentValues();done.put(MediaStore.Downloads.IS_PENDING,0);context.getContentResolver().update(uri,done,null,null);}
        return new Result(uri,name);
    }

    private static JSONObject buildReport(Context context,SharedPreferences prefs,EntityCache entityCache,TranslationCache translationCache,TrackingDatabase trackingDb,GitHubApiClient api)throws Exception{
        JSONObject root=new JSONObject();root.put("generated_at",Instant.now().toString());root.put("app_version",BuildConfig.VERSION_NAME);root.put("version_code",BuildConfig.VERSION_CODE);root.put("package",BuildConfig.APPLICATION_ID);
        JSONObject device=new JSONObject();device.put("manufacturer",Build.MANUFACTURER);device.put("model",Build.MODEL);device.put("sdk",Build.VERSION.SDK_INT);device.put("release",Build.VERSION.RELEASE);device.put("locale",context.getResources().getConfiguration().getLocales().get(0).toLanguageTag());device.put("font_scale",context.getResources().getConfiguration().fontScale);device.put("smallest_width_dp",context.getResources().getConfiguration().smallestScreenWidthDp);root.put("device",device);
        JSONObject apiInfo=new JSONObject();apiInfo.put("remaining",api.getRemaining());apiInfo.put("reset_epoch_seconds",api.getResetEpochSeconds());root.put("github_rate_limit",apiInfo);
        root.put("entity_cache",entityCache.stats());JSONObject translation=new JSONObject();translation.put("entries",translationCache.count());translation.put("bytes",translationCache.sizeBytes());root.put("translation_cache",translation);
        JSONObject tracking=new JSONObject();tracking.put("developers",trackingDb.getDevelopers(true).size());tracking.put("unread",trackingDb.unreadCount());root.put("tracking",tracking);
        JSONObject safePrefs=new JSONObject();for(Map.Entry<String,?> e:prefs.getAll().entrySet()){String key=e.getKey().toLowerCase();if(key.contains("token")||key.contains("secret")||key.contains("password")||key.contains("api_key")||key.contains("client"))continue;Object value=e.getValue();safePrefs.put(e.getKey(),value==null?JSONObject.NULL:String.valueOf(value));}root.put("preferences",safePrefs);
        return root;
    }
    private static void put(ZipOutputStream zip,String name,String value)throws Exception{ZipEntry entry=new ZipEntry(name);entry.setTime(0L);zip.putNextEntry(entry);zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
}
