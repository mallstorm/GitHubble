package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
import java.nio.file.*;

final class Stage2TestSupport {
    static final class Source implements WorkspaceSource {
        final Map<String,String> files=new TreeMap<>();final Map<String,String> modes=new HashMap<>();int reads;
        public Snapshot snapshot(String path){reads++;return new Snapshot(files.get(path),null,modes.getOrDefault(path,"100644"));}
        public List<String> paths(){return new ArrayList<>(files.keySet());}
        public String identity(){return "fixture";}
    }
    static ChangeStore changes(String task)throws Exception{return new ChangeStore(Files.createTempDirectory("changes").toFile(),"a","project",task);}
    static JSONObject edit(String path,String before,String after)throws Exception{return new JSONObject().put("path",path).put("expected_sha",GitObjectId.blob(before)).put("content",after==null?JSONObject.NULL:after);}
    static final class Journal implements DeltaPublisher.Journal {
        JSONObject value=new JSONObject();final List<String> states=new ArrayList<>();
        public JSONObject read(){return UnifiedAgentStore.copy(value);}
        public void write(JSONObject v){value=UnifiedAgentStore.copy(v);states.add(value.optString("state"));}
    }
    static final class Git implements GitDataApi {
        final Map<String,String> refs=new HashMap<>(),commits=new HashMap<>(),blobs=new HashMap<>();
        final Map<String,Map<String,String>> trees=new HashMap<>();final Map<String,String> modes=new HashMap<>();
        final List<String> mutations=new ArrayList<>(),blobReads=new ArrayList<>();JSONArray lastEntries;JSONObject lastCommit;int sequence=16,patches;boolean timeout;
        String branch="hubble/task-12345678",head="1".repeat(40),tree="a".repeat(40);
        Git(){refs.put(branch,head);refs.put("main",head);commits.put(head,tree);trees.put(tree,new TreeMap<>());}
        void file(String path,String text){String sha=GitObjectId.blob(text);blobs.put(sha,text);trees.get(tree).put(path,sha);}
        void advance(String path,String text){Map<String,String> next=new TreeMap<>(trees.get(commits.get(refs.get(branch))));String sha=GitObjectId.blob(text);blobs.put(sha,text);next.put(path,sha);String t=id(),h=id();trees.put(t,next);commits.put(h,t);refs.put(branch,h);}
        String id(){return String.format(java.util.Locale.ROOT,"%040x",sequence++);}
        String tail(String url,String marker){return java.net.URLDecoder.decode(url.substring(url.indexOf(marker)+marker.length()),java.nio.charset.StandardCharsets.UTF_8);}
        public JSONObject get(String url)throws Exception {
            if(url.contains("/git/ref/heads/")){String branch=tail(url,"/git/ref/heads/");String sha=refs.get(branch);if(sha==null)throw new app.githubble.mobile.network.GitHubApiClient.ApiException(404,"missing",0);return new JSONObject().put("object",new JSONObject().put("sha",sha));}
            if(url.contains("/branches/"))return new JSONObject().put("protected",false);
            if(url.contains("/git/commits/"))return new JSONObject().put("tree",new JSONObject().put("sha",commits.get(tail(url,"/git/commits/"))));
            if(url.contains("/git/trees/")){String key=tail(url,"/git/trees/").split("\\?",2)[0];JSONArray entries=new JSONArray();for(Map.Entry<String,String> e:trees.get(key).entrySet())entries.put(new JSONObject().put("path",e.getKey()).put("sha",e.getValue()).put("type","blob").put("mode",modes.getOrDefault(e.getKey(),"100644")).put("size",blobs.containsKey(e.getValue())?blobs.get(e.getValue()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length:100));return new JSONObject().put("tree",entries);}
            if(url.contains("/git/blobs/")){String sha=tail(url,"/git/blobs/");blobReads.add(sha);return new JSONObject().put("encoding","base64").put("content",java.util.Base64.getEncoder().encodeToString(blobs.get(sha).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
            throw new AssertionError(url);
        }
        public JSONObject post(String url,JSONObject body)throws Exception {
            mutations.add(url);
            if(url.endsWith("/git/blobs")){String text=body.getString("content"),sha=GitObjectId.blob(text);blobs.put(sha,text);return new JSONObject().put("sha",sha);}
            if(url.endsWith("/git/trees")){lastEntries=new JSONArray(body.getJSONArray("tree").toString());Map<String,String> t=new TreeMap<>(trees.get(body.getString("base_tree")));
                for(int i=0;i<lastEntries.length();i++){JSONObject e=lastEntries.getJSONObject(i);if(e.isNull("sha"))t.remove(e.getString("path"));else{t.put(e.getString("path"),e.getString("sha"));modes.put(e.getString("path"),e.getString("mode"));}}String id=id();trees.put(id,t);return new JSONObject().put("sha",id);}
            if(url.endsWith("/git/commits")){lastCommit=UnifiedAgentStore.copy(body);String id=id();commits.put(id,body.getString("tree"));return new JSONObject().put("sha",id);}
            if(url.endsWith("/git/refs")){String name=body.getString("ref").substring("refs/heads/".length());refs.put(name,body.getString("sha"));return new JSONObject().put("object",new JSONObject().put("sha",body.getString("sha")));}
            throw new AssertionError(url);
        }
        public JSONObject patch(String url,JSONObject body)throws Exception {
            assertFalse(body.getBoolean("force"));patches++;String b=tail(url,"/git/refs/heads/");refs.put(b,body.getString("sha"));if(timeout)throw new java.io.IOException("socket lost after ref accepted");return new JSONObject().put("object",new JSONObject().put("sha",body.getString("sha")));
        }
    }
}
