package app.githubble.mobile.build;

import android.app.DownloadManager;
import app.githubble.mobile.agent.core.ArtifactVerifier;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;
import app.githubble.mobile.MainActivity;
import app.githubble.mobile.R;

/** Saves the original ZIP via DownloadManager; extracts bounded APKs into Downloads on Q+. */
public final class BuildDownloadReceiver extends BroadcastReceiver {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()))return;
        long id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1L);
        if(id<0)return;
        String raw=BuildMonitor.prefs(context).getString("build_download_"+id,"");
        if(raw.isEmpty())return; // Never handle an unrelated system download.
        PendingResult pending=goAsync();
        IO.execute(()->{
            try{complete(context.getApplicationContext(),id,raw);}
            finally{pending.finish();}
        });
    }
    private static void complete(Context context,long id,String raw){
        // Keep the transfer metadata until its success/failure is known.
        BuildMonitor.prefs(context).edit().remove("build_download_"+id).apply();
        DownloadManager dm=context.getSystemService(DownloadManager.class);
        if(dm==null)return;
        try{
            JSONObject info=new JSONObject(raw);
            DownloadManager.Query q=new DownloadManager.Query().setFilterById(id);
            try(Cursor cursor=dm.query(q)){
                if(cursor==null||!cursor.moveToFirst())return;
                int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if(status!=DownloadManager.STATUS_SUCCESSFUL){
                    BuildMonitor.downloadFinished(context,info,false);
                    notify(context,"Ошибка загрузки артефакта",info.optString("name",""),null,id);
                    return;
                }
                BuildMonitor.downloadFinished(context,info,true);
            }
            if(Build.VERSION.SDK_INT<29){
                notify(context,"Артефакт скачан",info.optString("name","")+
                        " — откройте ZIP в Загрузках/GitHubble",null,id);
                return;
            }
            int extracted=0;
            try(ParcelFileDescriptor parcel=dm.openDownloadedFile(id);
                InputStream rawStream=new ParcelFileDescriptor.AutoCloseInputStream(parcel);
                ZipInputStream zip=new ZipInputStream(rawStream)){
                ZipEntry entry;
                while((entry=zip.getNextEntry())!=null&&extracted<4){
                    if(entry.isDirectory())continue;
                    String name=entry.getName();
                    String base=name.substring(name.lastIndexOf('/')+1)
                            .replaceAll("[^a-zA-Z0-9._-]","_");
                    if(!base.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"))continue;
                    if(base.isEmpty()||base.length()>140)continue;
                    if(entry.getSize()>200L*1024L*1024L)continue;
                    Extracted file=extractApk(context,zip,base,id);
                    if(file!=null&&file.rejected!=null){
                        notify(context,"APK не прошёл проверку: "+base,file.rejected,null,
                                (int)((id+extracted+100)&0x7fffffff));
                    }else if(file!=null){
                        extracted++;
                        Intent install=new Intent(Intent.ACTION_VIEW);
                        install.setDataAndType(file.uri,"application/vnd.android.package-archive");
                        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|
                                Intent.FLAG_ACTIVITY_NEW_TASK);
                        notify(context,"APK готов: "+base,file.summary+" Нажмите для установки.",install,
                                (int)((id+extracted)&0x7fffffff));
                    }
                    zip.closeEntry();
                }
            }
            if(extracted==0)notify(context,"Артефакт скачан",info.optString("name","")+
                    " — ZIP сохранён в Загрузках/GitHubble",null,id);
        }catch(Exception e){
            notify(context,"Артефакт скачан","ZIP доступен в Загрузках/GitHubble; "+
                    "автоматическое извлечение APK не удалось",null,id);
        }
    }
    /** Result of unpacking one APK: either a published Uri with a verification summary, or a rejection. */
    private static final class Extracted {
        final Uri uri; final String summary; final String rejected;
        Extracted(Uri uri,String summary,String rejected){this.uri=uri;this.summary=summary;this.rejected=rejected;}
    }
    /** The APK is unpacked to private cache, verified, and published to Downloads only if no check failed. */
    private static Extracted extractApk(Context context,ZipInputStream zip,String name,long id)
            throws IOException {
        if(Build.VERSION.SDK_INT<29)return null;
        java.io.File temp=java.io.File.createTempFile("apk-"+id+"-",".apk",context.getCacheDir());
        try{
            long size=0;
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(temp)){
                byte[] buffer=new byte[64*1024];
                int count;
                while((count=zip.read(buffer))!=-1){
                    size+=count;
                    if(size>200L*1024L*1024L)throw new IOException("APK слишком большой");
                    out.write(buffer,0,count);
                }
            }
            ArtifactVerifier.Expectation expectation=new ArtifactVerifier.Expectation();
            expectation.requireSignature=true;
            ArtifactVerifier.Report report=ArtifactVerifier.verify(temp,expectation);
            boolean failed=false,unverified=false;
            for(ArtifactVerifier.Check check:report.checks){
                if(check.status==ArtifactVerifier.Status.FAIL)failed=true;
                else if(check.status==ArtifactVerifier.Status.UNVERIFIED)unverified=true;
            }
            if(failed){
                StringBuilder why=new StringBuilder();
                for(ArtifactVerifier.Check check:report.checks)
                    if(check.status==ArtifactVerifier.Status.FAIL)
                        why.append(check.name).append(": ").append(check.detail).append("; ");
                return new Extracted(null,"",why.toString());
            }
            String digest=report.sha256.length()>=12?report.sha256.substring(0,12):report.sha256;
            String summary=report.identity==null||unverified
                    ?"Не удалось подтвердить пакет и версию (SHA-256 "+digest+"…)."
                    :report.identity.packageName+" "+report.identity.versionName
                        +" ("+report.identity.versionCode+"), SHA-256 "+digest+"…";
            ContentResolver resolver=context.getContentResolver();
            ContentValues values=new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME,id+"-"+name);
            values.put(MediaStore.Downloads.MIME_TYPE,"application/vnd.android.package-archive");
            values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/GitHubble");
            values.put(MediaStore.Downloads.IS_PENDING,1);
            Uri uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if(uri==null)return null;
            try(OutputStream out=resolver.openOutputStream(uri);
                java.io.FileInputStream in=new java.io.FileInputStream(temp)){
                if(out==null)throw new IOException("Не удалось сохранить APK");
                byte[] buffer=new byte[64*1024];
                int count;
                while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            }catch(Exception ex){
                resolver.delete(uri,null,null);
                throw ex instanceof IOException?(IOException)ex:new IOException(ex);
            }
            ContentValues done=new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING,0);
            resolver.update(uri,done,null,null);
            return new Extracted(uri,summary,null);
        }finally{
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }
    private static void notify(Context context,String title,String message,Intent target,long id){
        if(!appNotificationsAllowed(context))return;
        BuildMonitor.createChannel(context);
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(manager==null)return;
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?
                new Notification.Builder(context,BuildMonitor.CHANNEL):
                new Notification.Builder(context);
        if(target==null)target=new Intent(context,MainActivity.class)
                .setAction(BuildMonitor.OPEN_ACTION).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(context,(int)(id&0x7fffffff),target,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        builder.setSmallIcon(R.drawable.ic_repository).setContentTitle(title)
                .setContentText(message).setContentIntent(pi).setAutoCancel(true);
        manager.notify((int)(id&0x7fffffff),builder.build());
    }
    private static boolean appNotificationsAllowed(Context c){
        return Build.VERSION.SDK_INT<33||c.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS)==
                android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
