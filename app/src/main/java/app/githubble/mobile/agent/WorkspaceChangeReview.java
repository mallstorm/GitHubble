package app.githubble.mobile.agent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Immutable review of an AI plan. Captures old file states after generation;
 * rejects stale approvals and protects local content from silent overwrites.
 */
public final class WorkspaceChangeReview {
    private static final String MISSING = "missing";
    private static final int MAX_FILES = AgentFilePlan.MAX_FILES;
    private final LinkedHashMap<String,String> expected = new LinkedHashMap<>();
    private final LinkedHashMap<String,String> proposed = new LinkedHashMap<>();
    public final JSONArray operations = new JSONArray();
    public final int created;
    public final int modified;
    public final int unchanged;
    public final String summary;

    private WorkspaceChangeReview(int created,int modified,int unchanged,String summary){
        this.created=created;this.modified=modified;this.unchanged=unchanged;
        this.summary=summary;
    }

    public static boolean writablePath(String path){
        try{AgentFilePlan.validatePath(path);}
        catch(IllegalArgumentException invalid){return false;}
        String name=path.toLowerCase(Locale.ROOT);
        if(name.equals(".workspace.json")||name.endsWith("/.workspace.json")
            ||name.startsWith(".git/")||name.startsWith(".agent/")
            ||name.startsWith(".github/")&&!name.startsWith(".github/workflows/")
            ||name.contains("/.git/")||name.contains("/.agent/"))return false;
        for(String segment:name.split("/")){
            if(segment.equals(".env")||segment.startsWith(".env.")
                ||segment.contains("secret")||segment.contains("credential")
                ||segment.contains("password")||segment.contains("keystore")
                ||segment.contains("token")||segment.contains("api_key")
                ||segment.contains("apikey")||segment.equals(".npmrc")||segment.equals(".pypirc")
                ||segment.equals("id_rsa")||segment.equals("id_ed25519"))return false;
        }
        return !(name.endsWith(".pem")||name.endsWith(".key")
            ||name.endsWith(".p12")||name.endsWith(".pfx")
            ||name.endsWith(".jks")||name.endsWith(".asc")
            ||name.endsWith(".gpg")||name.endsWith(".mobileprovision")
            ||name.endsWith(".der")||name.endsWith(".p8"));
    }

    public static WorkspaceChangeReview capture(WorkspaceHub hub,String owner,
            String workspaceId,AgentFilePlan plan,boolean mayReplace)throws Exception{
        if(hub==null||plan==null)throw new IllegalArgumentException("Нет Workspace или плана");
        if(plan.files.length()>MAX_FILES)throw new IllegalArgumentException("Слишком много файлов");
        int added=0,changed=0,same=0;
        JSONArray operations=new JSONArray();
        StringBuilder details=new StringBuilder();
        LinkedHashMap<String,String> expected=new LinkedHashMap<>();
        LinkedHashMap<String,String> proposed=new LinkedHashMap<>();
        for(int i=0;i<plan.files.length();i++){
            JSONObject entry=plan.files.getJSONObject(i);
            String path=entry.getString("path"),text=entry.getString("content");
            if(!writablePath(path))throw new SecurityException("Защищённый путь: "+path);
            if(containsCredential(text))
                throw new SecurityException("Похоже, модель добавила секретный ключ: "+path);
            File file=hub.file(owner,workspaceId,path);
            boolean present=file.isFile();
            if(file.exists()&&!present)throw new IllegalArgumentException("Путь занят каталогом: "+path);
            if(present&&!mayReplace)
                throw new SecurityException("Изменение существующих файлов требует разрешения на чтение Workspace: "+path);
            String old=present?hub.readFile(owner,workspaceId,path):null;
            String before=present?sha256(old):MISSING;
            String after=sha256(text);
            expected.put(path,before);
            proposed.put(path,after);
            if(!present){added++;details.append("+ новый: ");}
            else if(before.equals(after)){same++;details.append("= без изменений: ");}
            else{changed++;details.append("~ изменится: ");}
            FileChangeStats diff=FileChangeStats.between(old,text);
            operations.put(new JSONObject().put("path",path)
                .put("kind",!present?"create":before.equals(after)?"unchanged":"edit")
                .put("added",diff.added).put("removed",diff.removed)
                .put("approximate",diff.approximate));
            details.append(path).append("  ").append(diff.approximate?"≈":"")
                .append("+").append(diff.added).append(" -").append(diff.removed).append('\n');
            if(path.startsWith(".github/workflows/"))
                details.append("  ⚠ Файл GitHub Actions: проверяйте содержимое до публикации\n");
        }
        WorkspaceChangeReview result=new WorkspaceChangeReview(added,changed,same,
            "Новых файлов: "+added+"\nИзменяемых: "+changed+
            "\nБез изменений: "+same+"\n\n"+details);
        for(int i=0;i<operations.length();i++)result.operations.put(operations.getJSONObject(i));
        result.expected.putAll(expected);
        result.proposed.putAll(proposed);
        return result;
    }

    public boolean hasChanges(){return created+modified>0;}
    public String preview(){return summary+
        "\nПроверка неизменности файлов выполняется повторно непосредственно перед записью.";}

    public void verifyCurrent(WorkspaceHub hub,String owner,String workspaceId)throws Exception{
        for(Map.Entry<String,String> state:expected.entrySet()){
            String path=state.getKey();
            File file=hub.file(owner,workspaceId,path);
            if(file.exists()&&!file.isFile())
                throw new IllegalStateException("Путь теперь каталог: "+path);
            String current=file.isFile()?sha256(hub.readFile(owner,workspaceId,path)):MISSING;
            if(!current.equals(state.getValue()))
                throw new IllegalStateException(
                    "Файл изменился после подготовки плана: "+path+
                    ". Повторите генерацию, чтобы избежать потери изменений.");
        }
    }

    public static AgentExecutionEngine.Applied applyApproved(android.content.Context context,
            WorkspaceHub hub,String owner,String workspaceId,AgentFilePlan plan,
            WorkspaceChangeReview review)throws Exception{
        if(review==null||!review.hasChanges())
            throw new IllegalArgumentException("Нет изменений для применения");
        synchronized(AgentExecutionEngine.class){
            review.verifyCurrent(hub,owner,workspaceId);
            return AgentExecutionEngine.applyApproved(context,hub,owner,workspaceId,plan);
        }
    }

    private static String sha256(String text)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder(64);
        for(byte b:digest)result.append(String.format(Locale.ROOT,"%02x",b&255));
        return result.toString();
    }
    private static boolean containsCredential(String source){
        return source.matches("(?s).*(?:-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
            +"|github_pat_[A-Za-z0-9_]{10,}|gh[pousr]_[A-Za-z0-9_]{18,}"
            +"|sk-[A-Za-z0-9_-]{32,}).*");
    }
}

