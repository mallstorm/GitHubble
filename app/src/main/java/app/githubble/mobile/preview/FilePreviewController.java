package app.githubble.mobile.preview;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** Loads repository contents safely and classifies them before rendering. */
public final class FilePreviewController {
    public enum Type { TEXT, IMAGE, SVG, PDF, BINARY, TOO_LARGE }
    public static final class Preview {
        public final Type type; public final String name; public final String mime; public final byte[] bytes; public final String text; public final long declaredSize; public final String htmlUrl;
        Preview(Type type,String name,String mime,byte[] bytes,String text,long declaredSize,String htmlUrl){this.type=type;this.name=name;this.mime=mime;this.bytes=bytes;this.text=text;this.declaredSize=declaredSize;this.htmlUrl=htmlUrl;}
    }
    private static final int MAX_INLINE_BYTES=12*1024*1024;
    private final GitHubApiClient api;
    public FilePreviewController(GitHubApiClient api){this.api=api;}

    public Preview loadRemote(String name,String downloadUrl,String htmlUrl)throws Exception{
        byte[] data=download(downloadUrl,MAX_INLINE_BYTES);String mime=mime(name);Type type=classify(name,mime,data);
        String text=(type==Type.TEXT||type==Type.SVG)?new String(data,StandardCharsets.UTF_8):"";
        return new Preview(type,name,mime,data,text,data.length,htmlUrl==null?"":htmlUrl);
    }

    public Preview load(String full,String branch,String path,String name,long declaredSize,String downloadUrl,String htmlUrl)throws Exception{
        if(declaredSize>MAX_INLINE_BYTES)return new Preview(Type.TOO_LARGE,name,mime(name),new byte[0],"",declaredSize,htmlUrl);
        byte[] data=new byte[0];String resolvedHtml=htmlUrl==null?"":htmlUrl;
        try{
            String endpoint="https://api.github.com/repos/"+full+"/contents/"+encodePath(path)+"?ref="+java.net.URLEncoder.encode(branch,"UTF-8");
            JSONObject object=api.getJson(endpoint);resolvedHtml=object.optString("html_url",resolvedHtml);String content=object.optString("content","");
            if(!content.isEmpty())data=Base64.getMimeDecoder().decode(content);
        }catch(Exception ignored){ }
        if(data.length==0&&downloadUrl!=null&&!downloadUrl.isEmpty())data=download(downloadUrl,MAX_INLINE_BYTES);
        String mime=mime(name);Type type=classify(name,mime,data);
        String text=(type==Type.TEXT||type==Type.SVG)?new String(data,StandardCharsets.UTF_8):"";
        return new Preview(type,name,mime,data,text,declaredSize>0?declaredSize:data.length,resolvedHtml);
    }

    private static Type classify(String name,String mime,byte[] data){
        String lower=name==null?"":name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".svg")||mime.contains("svg"))return Type.SVG;
        if(lower.endsWith(".pdf")||mime.equals("application/pdf"))return Type.PDF;
        if(mime.startsWith("image/")||lower.matches(".*\\.(png|jpg|jpeg|webp|gif|bmp)$"))return Type.IMAGE;
        for(byte b:data)if(b==0)return Type.BINARY;
        if(isTextExtension(lower)||looksText(data))return Type.TEXT;
        return Type.BINARY;
    }
    private static boolean isTextExtension(String n){return n.matches(".*\\.(java|kt|kts|xml|json|md|txt|gradle|properties|yml|yaml|toml|sh|ps1|py|js|ts|tsx|jsx|c|cc|cpp|h|hpp|cs|go|rs|php|rb|html|css|scss|sql|ini|cfg|conf|gitignore)$");}
    private static boolean looksText(byte[] data){if(data.length==0)return true;int suspicious=0;int limit=Math.min(data.length,4096);for(int i=0;i<limit;i++){int b=data[i]&255;if(b<9||(b>13&&b<32))suspicious++;}return suspicious<Math.max(2,limit/40);}
    private static String mime(String name){String n=name==null?"":name.toLowerCase(Locale.ROOT);if(n.endsWith(".pdf"))return "application/pdf";if(n.endsWith(".svg"))return "image/svg+xml";if(n.matches(".*\\.(png)$"))return "image/png";if(n.matches(".*\\.(jpg|jpeg)$"))return "image/jpeg";if(n.endsWith(".webp"))return "image/webp";if(n.endsWith(".gif"))return "image/gif";return isTextExtension(n)?"text/plain":"application/octet-stream";}
    private static byte[] download(String url,int limit)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent","GitHubble-Android/0.27");try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n,total=0;while((n=in.read(buffer))!=-1){total+=n;if(total>limit)throw new IllegalStateException("Файл слишком большой для встроенного просмотра");out.write(buffer,0,n);}return out.toByteArray();}}
    private static String encodePath(String path){String[] parts=(path==null?"":path).split("/");StringBuilder out=new StringBuilder();for(String part:parts){if(out.length()>0)out.append('/');try{out.append(java.net.URLEncoder.encode(part,"UTF-8").replace("+","%20"));}catch(Exception e){out.append(part);}}return out.toString();}
}
