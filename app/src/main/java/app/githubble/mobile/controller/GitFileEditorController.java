package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;
import android.net.Uri;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** UTF-8 Contents API editor: SHA compare-and-swap, no force overwrite. */
public final class GitFileEditorController {
    public static final int MAX_BYTES=512*1024;
    private final GitHubApiClient api;
    public GitFileEditorController(GitHubApiClient api){this.api=api;}
    public static final class Snapshot {
        public final String sha;
        public final String text;
        Snapshot(String sha,String text){this.sha=sha;this.text=text;}
    }
    private static String pathUrl(String repo,String branch,String path){
        if(!CommitDetailController.validRepository(repo)||branch==null||branch.isEmpty()
                ||path==null||path.isEmpty()||path.startsWith("/")||path.contains("../")
                ||path.endsWith("/..")||path.indexOf('\0')>=0)
            throw new IllegalArgumentException("Некорректный репозиторий, ветка или путь файла");
        return "https://api.github.com/repos/"+repo+"/contents/"+Uri.encode(path,"/")+
                "?ref="+Uri.encode(branch);
    }
    public Snapshot load(String repo,String branch,String path) throws Exception {
        JSONObject file=api.getJson(pathUrl(repo,branch,path));
        if(!"file".equals(file.optString("type","")))
            throw new IllegalArgumentException("Редактировать можно только обычные файлы");
        long size=file.optLong("size",-1L);
        if(size<0L||size>MAX_BYTES)
            throw new IllegalArgumentException("Редактор поддерживает файлы до 512 КБ");
        if(!"base64".equals(file.optString("encoding","")))
            throw new IllegalArgumentException("GitHub не предоставил содержимое файла в base64");
        byte[] bytes=Base64.getMimeDecoder().decode(file.optString("content",""));
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("Файл слишком большой");
        String value=StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        if(value.indexOf('\0')>=0)throw new IllegalArgumentException("Бинарный файл нельзя редактировать как текст");
        String sha=file.optString("sha","");
        if(!CommitDetailController.validSha(sha))
            throw new IllegalArgumentException("GitHub не предоставил SHA файла");
        return new Snapshot(sha,value);
    }
    public JSONObject save(String repo,String branch,String path,Snapshot original,
                           String edited,String message) throws Exception {
        if(original==null||edited==null||message==null||message.trim().isEmpty())
            throw new IllegalArgumentException("Не указан файл, текст или сообщение коммита");
        byte[] bytes=edited.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException(
                "Новый файл превышает ограничение 512 КБ");
        if(edited.equals(original.text))throw new IllegalArgumentException("Нет изменений");
        String url=pathUrl(repo,branch,path);
        api.invalidate(url);
        Snapshot latest=load(repo,branch,path);
        if(!latest.sha.equals(original.sha))
            throw new IllegalStateException("Файл изменён на GitHub. Откройте его заново и повторите правку.");
        JSONObject payload=new JSONObject();
        payload.put("message",message.trim());
        payload.put("content",Base64.getEncoder().encodeToString(bytes));
        payload.put("branch",branch);
        payload.put("sha",original.sha);
        JSONObject result=api.putJson(url.substring(0,url.indexOf("?ref=")),payload);
        api.invalidatePrefix("https://api.github.com/repos/"+repo+"/contents/");
        return result;
    }
}
