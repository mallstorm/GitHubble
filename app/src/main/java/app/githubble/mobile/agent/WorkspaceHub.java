package app.githubble.mobile.agent;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Repository-independent local projects with bounded, traversal-safe filesystem/ZIP operations. */
public final class WorkspaceHub {
    private final File base;
    private final Context context;
    public WorkspaceHub(Context c){context=c.getApplicationContext();base=new File(c.getFilesDir(),"git-agent-workspaces");}
    public WorkspaceHub(File isolatedRoot){context=null;base=isolatedRoot;}
    private File account(String owner)throws Exception{
        String hash=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8)));
        File f=new File(base,hash);if(!f.exists()&&!f.mkdirs())throw new IOException("Не создать папку проектов");return f;
    }
    public File root(String owner,String id)throws Exception{
        if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Некорректный Workspace ID");
        File f=new File(account(owner),id);if(!f.isDirectory())throw new IOException("Workspace не найден");return f;
    }
    public synchronized JSONObject create(String owner,String name)throws Exception{
        if(name.trim().isEmpty())throw new IOException("Укажите название проекта");
        String id=UUID.randomUUID().toString();File dir=new File(account(owner),id);if(!dir.mkdir())throw new IOException("Не создать Workspace");
        JSONObject j=new JSONObject().put("id",id).put("name",name.trim()).put("state","CREATED").put("instructions","").put("created",System.currentTimeMillis());
        write(new File(dir,".workspace.json"),j.toString());
        saveFile(owner,id,"README.md","# "+name.trim()+"\n");saveFile(owner,id,"docs/CONTEXT.md","# Контекст проекта\n");return j;
    }
    public synchronized JSONArray list(String owner)throws Exception{
        JSONArray a=new JSONArray();File[] dirs=account(owner).listFiles();if(dirs!=null)for(File f:dirs){File meta=new File(f,".workspace.json");if(meta.isFile())try{a.put(new JSONObject(read(meta,64*1024)));}catch(Exception ignored){}}return a;
    }
    public JSONObject info(String owner,String id)throws Exception{return new JSONObject(read(new File(root(owner,id),".workspace.json"),64*1024));}

    /** The last GitHub commit whose source was imported or published from this Workspace. */
    public synchronized void markRemoteRevision(String owner,String id,String repo,String branch,
            String sha)throws Exception{
        AgentGitWorkspace.validateRepository(repo,branch);
        if(sha==null||!sha.matches("[a-fA-F0-9]{40}"))
            throw new IllegalArgumentException("Некорректный SHA GitHub");
        JSONObject metadata=info(owner,id);
        metadata.put("source_repository",repo);
        metadata.put("source_branch",branch);
        metadata.put("source_sha",sha.toLowerCase(java.util.Locale.ROOT));
        metadata.put("source_checked_at",System.currentTimeMillis());
        write(new File(root(owner,id),".workspace.json"),metadata.toString());
    }

    public void requireRemoteRevision(String owner,String id,String repo,String branch,
            String expectedSha)throws Exception{
        JSONObject metadata=info(owner,id);
        if(!repo.equals(metadata.optString("source_repository"))
            ||!branch.equals(metadata.optString("source_branch"))
            ||!expectedSha.equalsIgnoreCase(metadata.optString("source_sha")))
            throw new IllegalStateException("Локальный Workspace не соответствует HEAD "+
                "сбойной сборки. Импортируйте актуальную ветку GitHub или "+
                "опубликуйте изменения Workspace и проверьте новый запуск.");
    }
    public void configure(String owner,String id,String name,String state,String instructions)throws Exception{
        JSONObject j=info(owner,id);j.put("name",name.trim()).put("state",state).put("instructions",instructions.substring(0,Math.min(12000,instructions.length())));
        write(new File(root(owner,id),".workspace.json"),j.toString());
    }
    public File file(String owner,String id,String path)throws Exception{
        File r=root(owner,id);if(path==null||path.isBlank()||path.startsWith("/")||path.contains("\\")||path.indexOf('\0')>=0)throw new IOException("Некорректный путь");
        for(String part:path.split("/"))if(part.equals("..")||part.equals(".")||part.equals(".git")||part.isEmpty())throw new IOException("Некорректный путь");
        File f=new File(r,path);String canonical=f.getCanonicalPath();
        if(!canonical.startsWith(r.getCanonicalPath()+File.separator)||f.getName().equals(".workspace.json"))throw new IOException("Путь вне Workspace");return f;
    }
    public void saveFile(String owner,String id,String path,String text)throws Exception{
        file(owner,id,path);if(context!=null){WorkspaceEngine.write(context,root(owner,id),id,path,text);return;}
        if(text.getBytes(StandardCharsets.UTF_8).length>1024*1024)throw new IOException("Текст больше 1 МБ");write(file(owner,id,path),text);
    }
    public String readFile(String owner,String id,String path)throws Exception{file(owner,id,path);return context==null?read(file(owner,id,path),1024*1024):WorkspaceEngine.read(context,root(owner,id),id,path);}
    public JSONArray files(String owner,String id)throws Exception{if(context!=null)return WorkspaceEngine.files(context,root(owner,id),id);JSONArray a=new JSONArray();walk(root(owner,id),"",a,0);return a;}
    private void walk(File dir,String prefix,JSONArray a,int depth)throws Exception{
        if(depth>20||a.length()>2000)return;File[] files=dir.listFiles();if(files==null)return;Arrays.sort(files,Comparator.comparing(File::getName));
        for(File f:files){if(f.getName().equals(".workspace.json"))continue;String p=prefix+f.getName();if(f.isDirectory())walk(f,p+"/",a,depth+1);else a.put(new JSONObject().put("path",p).put("size",f.length()));}
    }
    public String context(String owner,String id)throws Exception{
        if(id.isEmpty())return "";JSONObject meta=info(owner,id);StringBuilder s=new StringBuilder("\nWORKSPACE: "+meta.optString("name")+"\nСостояние: "+meta.optString("state")+"\nИнструкции проекта: "+meta.optString("instructions")+"\nФайлы:\n");
        JSONArray files=files(owner,id);for(int i=0;i<files.length()&&i<120;i++)s.append(files.getJSONObject(i).optString("path")).append('\n');
        for(String p:new String[]{"docs/CONTEXT.md","README.md"}){try{String text=readFile(owner,id,p);s.append('\n').append(p).append(":\n").append(text.substring(0,Math.min(12000,text.length())));}catch(Exception ignored){}}
        return s.substring(0,Math.min(18000,s.length()));
    }
    public void importZip(String owner,String id,InputStream source)throws Exception{
        File dest=root(owner,id);int entries=0,total=0;
        try(ZipInputStream z=new ZipInputStream(source)){ZipEntry e;byte[] buf=new byte[8192];while((e=z.getNextEntry())!=null){
            if(++entries>2000)throw new IOException("Слишком много файлов");String path=e.getName();if(path.endsWith(".workspace.json"))continue;
            if(e.isDirectory()){File d=file(owner,id,path);if(!d.exists()&&!d.mkdirs())throw new IOException("Не создать папку");continue;}
            File f=file(owner,id,path);File parent=f.getParentFile();if(!parent.exists()&&!parent.mkdirs())throw new IOException("Не создать папку");
            File tmp=File.createTempFile("import-",".tmp",parent);boolean ok=false;try(OutputStream out=new FileOutputStream(tmp)){int n,size=0;while((n=z.read(buf))!=-1){size+=n;total+=n;if(size>8*1024*1024||total>64*1024*1024)throw new IOException("Архив слишком большой");out.write(buf,0,n);}ok=true;}finally{if(!ok)tmp.delete();}
            if(!tmp.renameTo(f)){tmp.delete();throw new IOException("Не сохранить файл");}
        }}
    }
    public void exportZip(String owner,String id,OutputStream target)throws Exception{
        if(context!=null){WorkspaceEngine.exportZip(context,root(owner,id),id,target);return;}
        JSONArray entries=files(owner,id);try(ZipOutputStream z=new ZipOutputStream(target)) {for(int i=0;i<entries.length();i++){
            String path=entries.getJSONObject(i).getString("path");z.putNextEntry(new ZipEntry(path));try(InputStream in=new FileInputStream(file(owner,id,path))){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)z.write(buf,0,n);}z.closeEntry();}}
    }
    private static void write(File f,String text)throws Exception{
        File parent=f.getParentFile();if(!parent.exists()&&!parent.mkdirs())throw new IOException("Не создать папку");
        File temp=File.createTempFile("write-",".tmp",parent);try(OutputStream out=new FileOutputStream(temp)){out.write(text.getBytes(StandardCharsets.UTF_8));}
        if(!temp.renameTo(f)){temp.delete();throw new IOException("Не сохранить файл");}
    }
    private static String read(File f,int max)throws Exception{if(f.length()>max)throw new IOException("Файл слишком большой: "+f.getName());try(InputStream in=new FileInputStream(f)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("Файл слишком большой");out.write(buf,0,n);}return out.toString("UTF-8");}}
}
