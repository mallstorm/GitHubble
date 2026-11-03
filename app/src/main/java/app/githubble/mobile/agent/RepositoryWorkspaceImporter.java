package app.githubble.mobile.agent;

import android.util.Base64;
import app.githubble.mobile.network.GitHubApiClient;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Local, read-only GitHub import. Never runs git, executes repository code or
 * writes to GitHub. Binary files and likely secret-bearing files are excluded.
 *
 * This is a bounded text-source snapshot, not an unrestricted git clone.
 */
public final class RepositoryWorkspaceImporter {
    public static final int MAX_FILES = 180;
    public static final int MAX_FILE_BYTES = 128 * 1024;
    public static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;
    private RepositoryWorkspaceImporter() {}

    /** Deterministic dispatcher: command asks for a local source import, not AI prose. */
    public static boolean isImportIntent(String message){
        if(message==null)return false;
        String text=message.toLowerCase(Locale.ROOT);
        return text.contains("/workspace import") ||
            ((text.contains("репозитор")||text.contains("github")
                ||text.contains("гитхаб")||text.contains("гите"))
             && (text.contains("воркспейс")||text.contains("workspace")
                ||text.contains("клонир")||text.contains("импортир")
                ||text.contains("локальн")||text.contains("сливай")));
    }

    private static final class Source {
        final String path, body;
        final int size;
        Source(String path,String body,int size) {
            this.path=path;this.body=body;this.size=size;
        }
    }

    public static boolean allowedPath(String path) {
        if(path==null || path.isEmpty()) return false;
        try { AgentFilePlan.validatePath(path); }
        catch(IllegalArgumentException invalid){ return false; }
        String lower=path.toLowerCase(Locale.ROOT);
        if(lower.startsWith(".") && !lower.startsWith(".github/workflows/"))return false;
        if(lower.contains("/.") && !lower.startsWith(".github/workflows/"))return false;
        for(String part:lower.split("/")) {
            if(part.equals(".env") || part.startsWith(".env.") || part.equals(".npmrc")
                    || part.equals(".pypirc") || part.equals("id_rsa") || part.equals("id_ed25519")
                    || part.contains("secret") || part.contains("password")
                    || part.contains("credential") || part.contains("token")
                    || part.contains("keystore"))return false;
        }
        return !(lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".p12")
                || lower.endsWith(".p8") || lower.endsWith(".jks")
                || lower.endsWith(".der") || lower.endsWith(".mobileprovision")
                || lower.endsWith(".asc") || lower.endsWith(".gpg"));
    }

    private static String utf8(byte[] bytes)throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static boolean containsCredential(String source){
        return source.matches("(?s).*(?:-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
            +"|github_pat_[A-Za-z0-9_]{10,}|gh[pousr]_[A-Za-z0-9_]{18,}"
            +"|sk-[A-Za-z0-9_-]{32,}).*");
    }

    public static JSONObject copy(GitHubApiClient github,Runnable requireAccount,
            WorkspaceHub workspace,String account,String repo,String branch)throws Exception {
        AgentGitWorkspace.validateRepository(repo,branch);
        requireAccount.run();
        String base="https://api.github.com/repos/"+repo;
        JSONObject ref=github.getJson(base+"/git/ref/heads/"+branch);
        String head=ref.getJSONObject("object").getString("sha");
        if(!head.matches("[a-fA-F0-9]{40}"))throw new IOException("GitHub не вернул HEAD");
        JSONObject commit=github.getJson(base+"/git/commits/"+head);
        String tree=commit.getJSONObject("tree").getString("sha");
        if(!tree.matches("[a-fA-F0-9]{40}"))throw new IOException("GitHub не вернул дерево");
        JSONObject listing=github.getJson(base+"/git/trees/"+tree+"?recursive=1");
        if(listing.optBoolean("truncated"))
            throw new IOException("GitHub вернул неполное дерево; импорт отменён");
        JSONArray files=listing.getJSONArray("tree");
        List<Source> staged=new ArrayList<>();
        int eligible=0,skipped=0,total=0;
        for(int i=0;i<files.length();i++){
            if(Thread.currentThread().isInterrupted())
                throw new InterruptedException("Импорт отменён");
            JSONObject file=files.optJSONObject(i);
            if(file==null || !"blob".equals(file.optString("type")))continue;
            String path=file.optString("path"),sha=file.optString("sha");
            int size=file.optInt("size",-1);
            if(!allowedPath(path) || "120000".equals(file.optString("mode"))
                    || !sha.matches("[a-fA-F0-9]{40}") || size<0){
                skipped++;continue;
            }
            eligible++;
            if(size>MAX_FILE_BYTES || staged.size()>=MAX_FILES
                    || total+size>MAX_TOTAL_BYTES){skipped++;continue;}
            requireAccount.run();
            JSONObject blob=github.getJson(base+"/git/blobs/"+sha);
            if(!"base64".equals(blob.optString("encoding"))){skipped++;continue;}
            byte[] bytes=Base64.decode(blob.optString("content").replace("\n",""),Base64.DEFAULT);
            if(bytes.length>MAX_FILE_BYTES || total+bytes.length>MAX_TOTAL_BYTES){
                skipped++;continue;
            }
            String source;
            try{source=utf8(bytes);}
            catch(CharacterCodingException binary){skipped++;continue;}
            if(source.indexOf('\u0000')>=0 || containsCredential(source)){skipped++;continue;}
            staged.add(new Source(path,source,bytes.length));
            total+=bytes.length;
        }
        if(staged.isEmpty())
            throw new IOException("Нет подходящих текстовых файлов в репозитории");
        requireAccount.run();
        String name=repo.substring(repo.indexOf('/')+1);
        JSONObject created=workspace.create(account,name+" (GitHub)");
        String id=created.getString("id");
        int imported=0;
        try{
            for(Source file:staged){
                requireAccount.run();
                workspace.saveFile(account,id,file.path,file.body);
                imported++;
            }
            workspace.configure(account,id,name,"READY",
                "Локальный снимок GitHub: "+repo+" @ "+branch+" (HEAD "+head+"). "+
                "Исходный репозиторий не изменён. Импортировано "+imported+
                " текстовых файлов из "+eligible+" подходящих. Пропущено "+skipped+
                ". Это не полный git clone: без .git, бинарных файлов и секретов.");
        }catch(Exception error){
            workspace.configure(account,id,name,"IMPORT_PARTIAL",
                "Незавершённый импорт GitHub "+repo+": "+imported+" файлов. "+error.getMessage());
            throw error;
        }
        return new JSONObject().put("workspace_id",id).put("repository",repo)
            .put("branch",branch).put("head",head).put("imported",imported)
            .put("eligible",eligible).put("skipped",skipped).put("bytes",total)
            .put("partial",skipped>0);
    }
}
