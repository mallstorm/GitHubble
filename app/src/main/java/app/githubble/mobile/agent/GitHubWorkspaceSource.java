package app.githubble.mobile.agent;

import java.util.*;
import org.json.*;

/** Remote-first, commit-pinned tree; blobs are fetched lazily only for requested safe text paths. */
public final class GitHubWorkspaceSource implements WorkspaceSource {
    private final GitDataApi api;
    public final String repository,commit;
    private final Map<String,JSONObject> tree=new TreeMap<>();
    private final Map<String,Snapshot> cache=new HashMap<>();
    private boolean loaded;
    public GitHubWorkspaceSource(GitDataApi api,String repository,String commit){GitDataApi.base(repository);GitObjectId.requireCommit(commit);this.api=api;this.repository=repository;this.commit=commit;}
    public static String head(GitDataApi api,String repo,String branch)throws Exception {
        AgentGitWorkspace.validateRepository(repo,branch);String sha=api.get(GitDataApi.base(repo)+"/git/ref/heads/"+GitDataApi.encode(branch)).getJSONObject("object").getString("sha");GitObjectId.requireCommit(sha);return sha;
    }
    private synchronized void load()throws Exception {
        if(loaded)return;
        String root=api.get(GitDataApi.base(repository)+"/git/commits/"+commit).getJSONObject("tree").getString("sha");GitObjectId.requireCommit(root);
        JSONObject result=api.get(GitDataApi.base(repository)+"/git/trees/"+root+"?recursive=1");
        if(result.optBoolean("truncated"))throw new java.io.IOException("GitHub вернул частичное дерево; используйте импорт снимка для локального анализа");
        JSONArray rows=result.getJSONArray("tree");if(rows.length()>100000)throw new java.io.IOException("Слишком большое дерево");
        for(int i=0;i<rows.length();i++){JSONObject entry=rows.getJSONObject(i);if("blob".equals(entry.optString("type")))tree.put(entry.getString("path"),entry);}
        loaded=true;
    }
    public synchronized List<String> paths()throws Exception {load();return new ArrayList<>(tree.keySet());}
    public String identity(){return repository+"@"+commit;}
    public synchronized String sha(String path)throws Exception {load();JSONObject entry=tree.get(path);return entry==null?"missing":entry.getString("sha");}
    public synchronized String mode(String path)throws Exception {load();JSONObject e=tree.get(path);return e==null?"100644":e.optString("mode","100644");}
    public synchronized Snapshot snapshot(String path)throws Exception {
        ChangeStore.path(path);load();if(cache.containsKey(path))return cache.get(path);
        JSONObject entry=tree.get(path);if(entry==null)return new Snapshot(null,"missing","100644");
        if(!Arrays.asList("100644","100755").contains(entry.optString("mode")))throw new SecurityException("Не обычный файл: "+path);
        if(entry.optLong("size",Long.MAX_VALUE)>2L*1024*1024)throw new java.io.IOException("Файл больше 2 MiB");
        String sha=entry.getString("sha");GitObjectId.requireCommit(sha);
        JSONObject blob=api.get(GitDataApi.base(repository)+"/git/blobs/"+sha);
        if(!"base64".equals(blob.optString("encoding")))throw new java.io.IOException("Неизвестная кодировка Git blob");
        byte[] bytes=java.util.Base64.getMimeDecoder().decode(blob.getString("content"));
        if(bytes.length>2*1024*1024||!sha.equals(GitObjectId.blob(bytes)))throw new java.io.IOException("SHA blob не совпадает");
        String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if(text.indexOf('\0')>=0)throw new java.io.IOException("Бинарный файл");
        Snapshot value=new Snapshot(text,sha,entry.optString("mode"));cache.put(path,value);return value;
    }
    public String importReport(){return "Remote-first snapshot "+commit+"; only requested blobs are fetched.";}
}
