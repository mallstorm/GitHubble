package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Authenticated commit detail and paginated file changes, including private repositories. */
public final class CommitDetailController {
    public static final int PER_PAGE=100;
    public static final int MAX_FILES=300;
    private final GitHubApiClient api;

    public static final class Detail {
        public final JSONObject commit;
        public final JSONArray files;
        public final boolean partial;
        public final String warning;
        public Detail(JSONObject commit,JSONArray files,boolean partial,String warning) {
            this.commit=commit;
            this.files=files;
            this.partial=partial;
            this.warning=warning==null?"":warning;
        }
    }

    public CommitDetailController(GitHubApiClient api) { this.api=api; }

    public static boolean validRepository(String repo) {
        return repo!=null&&repo.length()<=190
                &&repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                &&!repo.contains("..");
    }

    public static boolean validSha(String sha) {
        return sha!=null&&sha.matches("[0-9a-fA-F]{40}");
    }

    public static boolean validFile(JSONObject file) {
        return file!=null&&!file.optString("filename","").isEmpty();
    }

    /** Deduplicates file entries when a paginated response overlaps. */
    public static void appendUnique(JSONArray target,JSONArray page,Set<String> seen) {
        if(page==null)return;
        for(int i=0;i<page.length()&&target.length()<MAX_FILES;i++){
            JSONObject file=page.optJSONObject(i);
            if(!validFile(file))continue;
            String key=file.optString("filename","");
            if(seen.add(key))target.put(file);
        }
    }

    public Detail load(String repo,String sha) throws Exception {
        if(!validRepository(repo)||!validSha(sha))
            throw new IllegalArgumentException("Некорректный репозиторий или SHA коммита");
        String endpoint="https://api.github.com/repos/"+repo+"/commits/"+sha;
        JSONObject first=api.getJson(endpoint+"?per_page="+PER_PAGE+"&page=1");
        JSONArray files=new JSONArray();
        Set<String> seen=new LinkedHashSet<>();
        JSONArray firstFiles=first.optJSONArray("files");
        appendUnique(files,firstFiles,seen);
        boolean partial=false;
        String warning="";
        if(firstFiles==null) {
            partial=true;
            warning="GitHub не вернул список файлов коммита.";
        } else {
            for(int page=2;page<=MAX_FILES/PER_PAGE&&firstFiles.length()==PER_PAGE;page++){
                if(Thread.currentThread().isInterrupted()){
                    partial=true;warning="Загрузка изменений прервана.";break;
                }
                JSONArray batch;
                try{
                    JSONObject additional=api.getJson(endpoint+"?per_page="+PER_PAGE+"&page="+page);
                    batch=additional.optJSONArray("files");
                    if(batch==null){partial=true;warning="GitHub не вернул продолжение списка файлов.";break;}
                }catch(Exception error){
                    partial=true;warning="Дополнительные файлы не загружены: "+error.getMessage();break;
                }
                appendUnique(files,batch,seen);
                if(batch.length()<PER_PAGE)break;
                if(page==MAX_FILES/PER_PAGE) {
                    partial=true;
                    warning="Достигнут лимит "+MAX_FILES+" файлов; полный diff может быть больше.";
                }
            }
        }
        return new Detail(first,files,partial,warning);
    }
}
