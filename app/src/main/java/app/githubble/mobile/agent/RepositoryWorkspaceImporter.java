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
        AgentGitWorkspace.validateRepository(repo,branch);requireAccount.run();
        GitDataApi api=GitDataApi.bound(github,requireAccount);
        String sha=GitHubWorkspaceSource.head(api,repo,branch);
        java.io.File file=java.io.File.createTempFile("github-snapshot-",".zip");
        try{
            github.downloadZipball(repo,sha,file,new ZipballImporter.Limits().compressed,requireAccount);
            try(java.io.InputStream in=new java.io.FileInputStream(file)){
                JSONObject result=workspace.importSnapshot(account,repo.substring(repo.indexOf('/')+1),repo,branch,sha,in,requireAccount);
                result.put("head",sha).put("files",result.optInt("imported"));return result;
            }
        }finally{java.nio.file.Files.deleteIfExists(file.toPath());}
    }

}
