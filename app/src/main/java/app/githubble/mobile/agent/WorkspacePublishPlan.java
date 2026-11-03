package app.githubble.mobile.agent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Immutable text snapshot prepared for one confirmed GitHub tree commit.
 * Separate from AI-generated AgentFilePlan (12-file per-proposal limit).
 * It does not use git clone or copy .git metadata.
 */
public final class WorkspacePublishPlan {
    public static final int MAX_FILES=180,MAX_FILE_BYTES=128*1024;
    public static final int MAX_TOTAL_BYTES=8*1024*1024;
    public final String workspaceId;
    public final int skipped,bytes;
    private final JSONArray entries;
    private final LinkedHashMap<String,String> hashes;
    private final String manifestHash;

    private WorkspacePublishPlan(String id,JSONArray files,LinkedHashMap<String,String> hashes,
            String manifestHash,int skipped,int bytes){
        this.workspaceId=id;this.entries=files;this.hashes=hashes;
        this.manifestHash=manifestHash;this.skipped=skipped;this.bytes=bytes;
    }

    public static WorkspacePublishPlan capture(WorkspaceHub hub,String account,String id)throws Exception{
        JSONArray available=hub.files(account,id);
        final String manifest=hashManifest(available);
        JSONArray uploaded=new JSONArray();
        LinkedHashMap<String,String> hashes=new LinkedHashMap<>();
        int excluded=0,bytes=0;
        for(int i=0;i<available.length();i++){
            JSONObject candidate=available.getJSONObject(i);
            String path=candidate.optString("path","");
            long size=candidate.optLong("size",-1);
            if(!safePath(path)||size<0||size>MAX_FILE_BYTES
                ||uploaded.length()>=MAX_FILES||bytes+size>MAX_TOTAL_BYTES){
                excluded++;continue;
            }
            File file=hub.file(account,id,path);
            if(!file.isFile()){excluded++;continue;}
            String content;
            try{content=hub.readFile(account,id,path);}
            catch(Exception failed){excluded++;continue;}
            byte[] utf8=content.getBytes(StandardCharsets.UTF_8);
            if(utf8.length==0||utf8.length>MAX_FILE_BYTES
                ||utf8.length+bytes>MAX_TOTAL_BYTES
                ||content.indexOf('\u0000')>=0||content.indexOf('\uFFFD')>=0
                ||looksLikeSecret(content)){
                excluded++;continue;
            }
            uploaded.put(new JSONObject().put("path",path).put("content",content)
                .put("mode","100644").put("type","blob"));
            hashes.put(path,sha256(content));
            bytes+=utf8.length;
        }
        if(uploaded.length()==0)throw new IllegalArgumentException(
            "Нет доступных безопасных текстовых исходников для публикации");
        return new WorkspacePublishPlan(id,uploaded,hashes,manifest,excluded,bytes);
    }

    public int fileCount(){return entries.length();}
    public JSONArray treeEntries() throws org.json.JSONException {
        return new JSONArray(entries.toString());
    }
    public String preview(String repo,String branch){
        StringBuilder result=new StringBuilder("Публикация Workspace → "+repo+
            " / "+branch+"\n\nИсходников: "+fileCount()+
            "\nОбъём: "+bytes+" байт\nПропущено: "+skipped+
            "\n\nGitHub обновится одним коммитом после подтверждения.\n"+
            "Состояние ветки и всех локальных файлов будет проверено заново.\n\nФайлы:\n");
        for(int i=0;i<entries.length();i++){
            String path=entries.optJSONObject(i).optString("path","");
            if(i<70)result.append("• ").append(path).append('\n');
        }
        if(fileCount()>70)result.append("… ещё ").append(fileCount()-70).append(" файлов\n");
        if(skipped>0)result.append("\n⚠ Публикация будет частичной: некоторые файлы исключены.\n");
        result.append("\nПроверка секретов эвристическая: перед публикацией "+
            "проверьте содержимое и видимость репозитория.");
        result.append("\nGitHub Actions могут запуститься после обновления ветки.");
        return result.toString();
    }

    public void verifyCurrent(WorkspaceHub hub,String account)throws Exception{
        if(!manifestHash.equals(hashManifest(hub.files(account,workspaceId))))
            throw new IllegalStateException("Список файлов Workspace изменился; подготовьте публикацию повторно");
        for(Map.Entry<String,String> file:hashes.entrySet()){
            File current=hub.file(account,workspaceId,file.getKey());
            if(!current.isFile()||!file.getValue().equals(
                    sha256(hub.readFile(account,workspaceId,file.getKey()))))
                throw new IllegalStateException("Исходник изменился: "+file.getKey()+
                    ". Публикацию необходимо подготовить повторно.");
        }
    }

    public static boolean safePath(String path){
        if(!WorkspaceChangeReview.writablePath(path))return false;
        String lower=path.toLowerCase(Locale.ROOT);
        if(lower.equals("docs/context.md"))return false; // private planning context
        for(String part:lower.split("/")){
            if(part.equals("local.properties")||part.equals("gradle.properties")||
                part.equals("firebase.json")||part.equals("google-services.json")||
                part.equals("id_rsa")||part.equals("id_ed25519"))return false;
        }
        return true;
    }

    private static boolean looksLikeSecret(String value){
        return value.matches("(?s).*(?:-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"+
            "|github_pat_[A-Za-z0-9_]{10,}|gh[pousr]_[A-Za-z0-9_]{18,}"+
            "|sk-[A-Za-z0-9_-]{32,}).*");
    }

    private static String hashManifest(JSONArray files)throws Exception{
        List<String> paths=new ArrayList<>();
        for(int i=0;i<files.length();i++){
            JSONObject candidate=files.optJSONObject(i);
            if(candidate!=null)paths.add(candidate.optString("path",""));
        }
        Collections.sort(paths);
        return sha256(String.join("\n",paths));
    }

    private static String sha256(String value)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder(64);
        for(byte part:hash)result.append(String.format(Locale.ROOT,"%02x",part&255));
        return result.toString();
    }
}
