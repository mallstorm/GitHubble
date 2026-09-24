package app.githubble.mobile.release;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import app.githubble.mobile.security.SecureStore;

/** Streams private or public GitHub release assets without exposing the token to CDN. */
public final class ReleaseAssetDownloads {
    public interface Progress { void onBytes(long transferred,long total); }
    private ReleaseAssetDownloads(){}

    private static String safeName(String name){
        String value=name==null?"release.bin":name.replaceAll("[\\\\/:*?\\\"<>|\\x00-\\x1F]","_").trim();
        if(value.isEmpty()||".".equals(value)||"..".equals(value))value="release.bin";
        return value.length()>150?value.substring(0,150):value;
    }
    public static Uri download(Context context,String repository,long assetId,String filename,
                               Progress progress) throws Exception {
        if(repository==null||!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                ||assetId<=0)throw new IllegalArgumentException("Некорректный файл релиза GitHub");
        String token=new SecureStore(context).get("github_token");
        if(token.isEmpty())throw new IOException("Необходимо войти в GitHub для загрузки релиза.");
        URL url=new URL("https://api.github.com/repos/"+repository+"/releases/assets/"+assetId);
        HttpURLConnection connection=null;
        try{
            for(int redirects=0;redirects<=5;redirects++){
                if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null)
                    throw new IOException("GitHub вернул небезопасную ссылку загрузки.");
                boolean githubApi="api.github.com".equalsIgnoreCase(url.getHost());
                connection=(HttpURLConnection)url.openConnection();
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(90_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept","application/octet-stream");
                connection.setRequestProperty("User-Agent","GitHubble-Android");
                if(githubApi){
                    connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
                    connection.setRequestProperty("Authorization","Bearer "+token);
                }
                int response=connection.getResponseCode();
                if(response==301||response==302||response==303||response==307||response==308){
                    String location=connection.getHeaderField("Location");
                    if(location==null||location.isEmpty())
                        throw new IOException("GitHub не выдал адрес файла релиза.");
                    URL next=new URL(url,location);
                    connection.disconnect();
                    connection=null;
                    url=next;
                    continue;
                }
                if(response==401||response==403)
                    throw new IOException("GitHub запретил скачивание: проверьте доступ к репозиторию (HTTP "+response+").");
                if(response==404)
                    throw new IOException("Файл релиза не найден или недоступен для аккаунта (HTTP 404).");
                if(response<200||response>=300)
                    throw new IOException("GitHub не смог скачать файл релиза (HTTP "+response+").");
                String mime=connection.getContentType();
                if(mime!=null&&mime.toLowerCase(java.util.Locale.ROOT).contains("json"))
                    throw new IOException("GitHub вместо файла вернул JSON. Проверьте разрешение Contents: Read.");
                return save(context,connection.getInputStream(),safeName(filename),
                        connection.getContentLengthLong(),progress);
            }
            throw new IOException("Слишком много перенаправлений GitHub.");
        }finally{if(connection!=null)connection.disconnect();}
    }

    private static Uri save(Context context,InputStream source,String fileName,long length,
                            Progress progress)throws Exception{
        ContentResolver resolver=context.getContentResolver();
        Uri created=null;
        File privateFile=null;
        try(InputStream input=source){
            OutputStream output;
            if(Build.VERSION.SDK_INT>=29){
                ContentValues values=new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME,fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE,
                        fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")?
                                "application/vnd.android.package-archive":"application/octet-stream");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS+"/GitHubble");
                values.put(MediaStore.MediaColumns.IS_PENDING,1);
                created=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                if(created==null)throw new IOException("Не удалось создать файл в Загрузках.");
                output=resolver.openOutputStream(created,"w");
                if(output==null)throw new IOException("Не удалось открыть файл для записи.");
            }else{
                File folder=new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"GitHubble");
                if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Не удалось создать папку загрузок.");
                privateFile=new File(folder,fileName);
                output=new FileOutputStream(privateFile);
            }
            try(OutputStream out=output){
                byte[] bytes=new byte[65536];
                long transferred=0L;
                int count;
                long previous=-1L;
                while((count=input.read(bytes))!=-1){
                    out.write(bytes,0,count);
                    transferred+=count;
                    long percent=length>0L?transferred*100L/length:transferred/(512*1024);
                    if(progress!=null&&percent!=previous){
                        progress.onBytes(transferred,length);
                        previous=percent;
                    }
                }
                out.flush();
            }
            if(Build.VERSION.SDK_INT>=29){
                ContentValues completed=new ContentValues();
                completed.put(MediaStore.MediaColumns.IS_PENDING,0);
                resolver.update(created,completed,null,null);
                return created;
            }
            return Uri.fromFile(privateFile);
        }catch(Exception error){
            if(created!=null)resolver.delete(created,null,null);
            if(privateFile!=null)privateFile.delete();
            throw error;
        }
    }
}
