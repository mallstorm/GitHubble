package app.githubble.mobile.agent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Private per-task overlay. No operation in this class writes shared project files or SAF. */
public final class ChangeStore {
    public static final Object LOCK=new Object();
    public static final int MAX_FILE_BYTES=2*1024*1024,MAX_BATCH_BYTES=4*1024*1024,MAX_BATCH_FILES=100;
    private final File manifest;
    public final String account,project,task;
    public static final class Conflict extends IOException {public Conflict(String message){super(message);}}
    public ChangeStore(File root,String account,String project,String task)throws Exception {
        if(account==null||account.isBlank()||project==null||project.isBlank()||task==null||task.isBlank())throw new IllegalArgumentException("Нужны Project/Task");
        this.account=account;this.project=project;this.task=task;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        String key=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest((account+"\0"+project+"\0"+task).getBytes(StandardCharsets.UTF_8)));
        File directory=new File(root,key);Files.createDirectories(directory.toPath());
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("Ссылка вместо хранилища изменений");
        manifest=new File(directory,"changes.json");
    }
    public void deleteTaskData()throws IOException {synchronized(LOCK){ZipballImporter.deleteTree(manifest.getParentFile().toPath());}}
    public static void path(String path) {
        AgentFilePlan.validatePath(path);
        if(!WorkspaceChangeReview.writablePath(path)||path.equals(".hubble-import.json")||path.endsWith("/.hubble-import.json")
            ||path.endsWith("gradle.properties"))throw new SecurityException("Защищённый путь: "+path);
    }
    public static void text(String value)throws IOException {
        if(value==null)return;
        if(value.getBytes(StandardCharsets.UTF_8).length>MAX_FILE_BYTES||value.indexOf('\0')>=0)throw new IOException("Текст превышает лимит или содержит бинарные данные");
        if(!value.equals(app.githubble.mobile.memory.MemoryPolicy.INSTANCE.redactSecrets(value)))throw new SecurityException("Секрет в предлагаемом содержимом");
    }
    private JSONObject load()throws Exception {
        if(!manifest.exists())return new JSONObject().put("schema",1).put("account",account).put("project",project).put("task",task).put("sets",new JSONArray()).put("checkpoints",new JSONArray());
        if(Files.isSymbolicLink(manifest.toPath())||manifest.length()>32L*1024*1024)throw new IOException("Некорректное хранилище изменений");
        JSONObject state=new JSONObject(new String(Files.readAllBytes(manifest.toPath()),StandardCharsets.UTF_8));
        if(state.getInt("schema")!=1||!account.equals(state.getString("account"))||!project.equals(state.getString("project"))||!task.equals(state.getString("task")))throw new SecurityException("Контекст изменений не совпадает");
        return state;
    }
    private void save(JSONObject state)throws Exception {
        byte[] bytes=state.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>32*1024*1024)throw new IOException("Хранилище изменений заполнено");
        File temp=File.createTempFile("changes-",".tmp",manifest.getParentFile());
        try{try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
            try{Files.move(temp.toPath(),manifest.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unavailable){Files.move(temp.toPath(),manifest.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp.toPath());}
    }
    public JSONArray list()throws Exception {synchronized(LOCK){return load().getJSONArray("sets");}}
    public JSONObject get(String id)throws Exception {synchronized(LOCK){return UnifiedAgentStore.copy(find(load(),id));}}
    private JSONObject find(JSONObject state,String id)throws Exception {
        JSONArray sets=state.getJSONArray("sets");for(int i=0;i<sets.length();i++)if(id.equals(sets.getJSONObject(i).optString("id")))return sets.getJSONObject(i);
        throw new IllegalArgumentException("ChangeSet не найден");
    }
    private static boolean applied(JSONObject entry){String s=entry.optString("state");return s.equals("LOCAL_APPLIED")||s.equals("PUBLISHED")&&entry.optBoolean("overlayActive",true);}
    private WorkspaceSource.Snapshot effective(JSONObject state,WorkspaceSource source,String path)throws Exception {
        WorkspaceSource.Snapshot base=source.snapshot(path),result=base;JSONArray sets=state.getJSONArray("sets");
        for(int i=0;i<sets.length();i++){
            JSONArray entries=sets.getJSONObject(i).getJSONArray("entries");
            for(int j=0;j<entries.length();j++){
                JSONObject entry=entries.getJSONObject(j);if(entry.optString("kind").equals("DIRECTORY")||!path.equals(entry.getString("path"))||!applied(entry))continue;
                if(!base.sha.equals(entry.getString("sourceSha")))throw new Conflict("Базовый файл изменился вне задачи: "+path);
                result=new WorkspaceSource.Snapshot(entry.isNull("after")?null:entry.getString("after"),"PUBLISHED".equals(entry.getString("state"))?GitObjectId.blob(entry.isNull("after")?null:entry.getString("after")):entry.getString("originSha"),entry.getString("mode"));
            }
        }
        return result;
    }
    public WorkspaceSource.Snapshot snapshot(WorkspaceSource source,String path)throws Exception {path(path);synchronized(LOCK){return effective(load(),source,path);}}
    public List<String> paths(WorkspaceSource source)throws Exception {
        synchronized(LOCK){TreeSet<String> paths=new TreeSet<>(source.paths());JSONArray sets=load().getJSONArray("sets");
            for(int i=0;i<sets.length();i++){JSONArray entries=sets.getJSONObject(i).getJSONArray("entries");for(int j=0;j<entries.length();j++){
                JSONObject e=entries.getJSONObject(j);if(!e.optString("kind").equals("DIRECTORY")&&applied(e)){if(e.isNull("after"))paths.remove(e.getString("path"));else paths.add(e.getString("path"));}}}return new ArrayList<>(paths);}
    }
    /** Each requested entry includes a host-checked expected_sha and nullable proposed content. */
    public JSONObject propose(WorkspaceSource source,String summary,JSONArray requested)throws Exception {
        synchronized(LOCK){JSONObject state=load();mutable(state);if(requested.length()<1||requested.length()>MAX_BATCH_FILES)throw new IllegalArgumentException("Пакет: 1–100 файлов");
            JSONArray entries=new JSONArray();Set<String> seen=new HashSet<>();int bytes=0;
            for(int i=0;i<requested.length();i++){
                JSONObject request=requested.getJSONObject(i);String path=request.getString("path");path(path);
                if(!seen.add(path.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Повтор пути в пакете");
                if(request.optBoolean("directory")){
                    String revision=directorySha(source,path);boolean present=request.optBoolean("directory_present",true);
                    if(paths(source).contains(path)||!request.getString("expected_sha").equals(revision)||present&&!revision.equals("missing")||!present&&revision.equals("missing"))throw new Conflict("Каталог изменился или путь занят: "+path);
                    entries.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("path",path).put("kind","DIRECTORY").put("directoryPresent",present)
                        .put("before",JSONObject.NULL).put("after",JSONObject.NULL).put("beforeSha",revision).put("sourceSha",revision).put("originSha","missing").put("mode","100644").put("group",request.optString("group",path)).put("state","PROPOSED"));continue;
                }
                WorkspaceSource.Snapshot base=source.snapshot(path),before=effective(state,source,path);
                String expected=request.optString("expected_sha","");
                if(!before.sha.equals(expected))throw new Conflict("Нужен актуальный expected_sha из read_file: "+path);
                String after=request.isNull("content")?null:request.getString("content");text(after);
                if(before.text!=null&&!before.text.equals(app.githubble.mobile.memory.MemoryPolicy.INSTANCE.redactSecrets(before.text)))
                    throw new SecurityException("Файл с секретом редактируется вручную: "+path);
                if(after!=null)bytes+=after.getBytes(StandardCharsets.UTF_8).length;
                if(bytes>MAX_BATCH_BYTES)throw new IOException("Пакет больше 4 MiB");
                if(Objects.equals(before.text,after))continue;
                entries.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("path",path)
                    .put("before",before.text==null?JSONObject.NULL:before.text).put("after",after==null?JSONObject.NULL:after)
                    .put("beforeSha",before.sha).put("sourceSha",base.sha).put("originSha",before.originSha).put("mode",request.optString("mode",before.mode))
                    .put("group",request.optString("group",path)).put("state","PROPOSED"));
            }
            TreeSet<String> finalPaths=new TreeSet<>(paths(source));
            for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if(e.optString("kind").equals("DIRECTORY"))continue;if(e.isNull("after"))finalPaths.remove(e.getString("path"));else finalPaths.add(e.getString("path"));}
            for(String candidate:finalPaths){if(directories(source).contains(candidate))throw new Conflict("Путь занят каталогом задачи: "+candidate);int slash=candidate.indexOf('/');while(slash>0){if(finalPaths.contains(candidate.substring(0,slash)))throw new Conflict("Файл перекрывает каталог: "+candidate);slash=candidate.indexOf('/',slash+1);}}
            if(entries.length()==0)throw new IllegalArgumentException("Нет изменений");
            String id=UUID.randomUUID().toString();JSONObject set=new JSONObject().put("id",id).put("taskId",task).put("projectId",project)
                .put("summary",summary==null?"Изменения":summary.substring(0,Math.min(1000,summary.length())))
                .put("createdAt",System.currentTimeMillis()).put("state","PROPOSED").put("entries",entries);
            // Before the first proposal, keep the base manifest as a checkpoint as well.
            if(state.getJSONArray("checkpoints").length()==0)checkpoint(state,"Before first change");
            state.getJSONArray("sets").put(set);save(state);return UnifiedAgentStore.copy(set);
        }
    }
    private String checkpoint(JSONObject state,String label)throws Exception {
        String id=UUID.randomUUID().toString();JSONArray snapshots=new JSONArray();JSONArray sets=state.getJSONArray("sets");
        for(int i=0;i<sets.length();i++)snapshots.put(UnifiedAgentStore.copy(sets.getJSONObject(i)));
        state.getJSONArray("checkpoints").put(new JSONObject().put("id",id).put("label",label).put("sets",snapshots));return id;
    }
    public JSONObject approve(String id,Set<String> paths)throws Exception {
        synchronized(LOCK){JSONObject state=load(),set=find(state,id);mutable(state);JSONArray entries=set.getJSONArray("entries");Set<String> groups=new HashSet<>();
            for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if(paths==null||paths.contains(e.getString("path")))groups.add(e.getString("group"));}
            boolean selected=false;for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if(groups.contains(e.getString("group"))&&"PROPOSED".equals(e.getString("state"))){e.put("state","APPROVED");selected=true;}}
            if(!selected)throw new IllegalStateException("Нет предложенных файлов для подтверждения");
            aggregate(set);save(state);return UnifiedAgentStore.copy(set);
        }
    }
    public JSONObject apply(String id,WorkspaceSource source)throws Exception {
        synchronized(LOCK){JSONObject state=load(),set=find(state,id);mutable(state);JSONArray entries=set.getJSONArray("entries");boolean any=false;
            try{for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if(!"APPROVED".equals(e.getString("state")))continue;any=true;
                String path=e.getString("path");if(e.optString("kind").equals("DIRECTORY")){if(source.paths().contains(path)||!directorySha(source,path).equals(e.getString("beforeSha")))throw new Conflict("Каталог изменился: "+path);continue;}if(!e.getString("beforeSha").equals(effective(state,source,path).sha))throw new Conflict("Файл изменился после preview: "+path);
                if(!e.getString("sourceSha").equals(source.snapshot(path).sha))throw new Conflict("База изменилась: "+path);}
            }catch(Conflict conflict){set.put("state","CONFLICT");save(state);throw conflict;}
            if(!any)throw new IllegalStateException("Нет подтверждённых изменений");
            set.put("checkpoint",checkpoint(state,"Before apply "+id));
            for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if("APPROVED".equals(e.getString("state")))e.put("state","LOCAL_APPLIED");}
            aggregate(set);save(state);return UnifiedAgentStore.copy(set);
        }
    }
    public void reject(String id,Set<String> paths)throws Exception {synchronized(LOCK){JSONObject state=load(),set=find(state,id);mutable(state);JSONArray entries=set.getJSONArray("entries");
        Set<String> groups=new HashSet<>();for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if(paths==null||paths.contains(e.getString("path")))groups.add(e.getString("group"));}
        for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if(groups.contains(e.getString("group"))&&"PROPOSED".equals(e.getString("state")))e.put("state","REJECTED");}
        aggregate(set);save(state);}}
    public void rollback(String id)throws Exception {
        synchronized(LOCK){JSONObject state=load(),set=find(state,id);mutable(state);JSONArray entries=set.getJSONArray("entries");Set<String> touched=new HashSet<>();
            for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if("PUBLISHED".equals(e.getString("state")))throw new Conflict("Опубликованное изменение отменяется новым ChangeSet");if(applied(e))touched.add(e.getString("path"));}
            boolean later=false;JSONArray sets=state.getJSONArray("sets");for(int i=0;i<sets.length();i++){JSONObject next=sets.getJSONObject(i);if(next.getString("id").equals(id)){later=true;continue;}if(!later)continue;
                JSONArray list=next.getJSONArray("entries");for(int j=0;j<list.length();j++)if(applied(list.getJSONObject(j))&&touched.contains(list.getJSONObject(j).getString("path")))throw new Conflict("Сначала отмените более позднее изменение этого файла");}
            for(int i=0;i<entries.length();i++)entries.getJSONObject(i).put("state","ROLLED_BACK");set.put("state","ROLLED_BACK");save(state);
        }
    }
    public JSONObject approveHunks(String id,String path,Set<Integer> selected,WorkspaceSource source)throws Exception {
        synchronized(LOCK){JSONObject state=load(),set=find(state,id);mutable(state);JSONArray entries=set.getJSONArray("entries");JSONObject entry=null;
            for(int i=0;i<entries.length();i++)if(path.equals(entries.getJSONObject(i).getString("path")))entry=entries.getJSONObject(i);
            if(entry==null||!"PROPOSED".equals(entry.getString("state"))||entry.isNull("before")||entry.isNull("after")||!entry.getString("group").equals(path))throw new IllegalArgumentException("По блокам принимается только изменение одного существующего файла");
            String before=entry.getString("before"),after=entry.getString("after");
            if(!effective(state,source,path).sha.equals(entry.getString("beforeSha")))throw new Conflict("Файл изменился после diff");
            String partial=TextEdits.select(before,after,selected);
            JSONObject child=propose(source,"Выбранные блоки: "+set.optString("summary"),new JSONArray().put(new JSONObject().put("path",path).put("expected_sha",entry.getString("beforeSha")).put("content",partial)));
            // Reload because propose saved the child. The original file proposal is explicitly rejected.
            state=load();set=find(state,id);entries=set.getJSONArray("entries");for(int i=0;i<entries.length();i++)if(path.equals(entries.getJSONObject(i).getString("path")))entries.getJSONObject(i).put("state","REJECTED");aggregate(set);save(state);
            approve(child.getString("id"),null);return apply(child.getString("id"),source);
        }
    }
    public JSONArray delta(WorkspaceSource source)throws Exception {
        synchronized(LOCK){JSONObject state=load();LinkedHashMap<String,JSONObject> delta=new LinkedHashMap<>();JSONArray sets=state.getJSONArray("sets");
            for(int i=0;i<sets.length();i++){JSONArray entries=sets.getJSONObject(i).getJSONArray("entries");for(int j=0;j<entries.length();j++){
                JSONObject e=entries.getJSONObject(j);if(e.optString("kind").equals("DIRECTORY"))continue;String path=e.getString("path");
                if("PUBLISHED".equals(e.getString("state"))){delta.remove(path);continue;}
                if(!"LOCAL_APPLIED".equals(e.getString("state")))continue;
                if(!e.getString("sourceSha").equals(source.snapshot(path).sha))throw new Conflict("База изменилась перед публикацией: "+path);
                JSONObject item=delta.get(path);
                if(item==null){item=new JSONObject().put("path",path).put("beforeSha",e.getString("originSha")).put("mode",e.getString("mode"));delta.put(path,item);}
                item.put("after",e.opt("after"));
            }}
            JSONArray result=new JSONArray();for(JSONObject item:delta.values())if(!item.getString("beforeSha").equals(GitObjectId.blob(item.isNull("after")?null:item.getString("after"))))result.put(item);return result;
        }
    }
    public void published(String commit)throws Exception {published(commit,publishingFingerprint());}
    public void published(String commit,String fingerprint)throws Exception {GitObjectId.requireCommit(commit);synchronized(LOCK){JSONObject state=load();
        if(!state.optBoolean("publishing")||fingerprint.isEmpty()||!fingerprint.equals(state.optString("publishingFingerprint")))throw new Conflict("Подтверждение относится к другой публикации; новые изменения сохранены");
        JSONArray sets=state.getJSONArray("sets");
        for(int i=0;i<sets.length();i++){JSONObject set=sets.getJSONObject(i);JSONArray entries=set.getJSONArray("entries");for(int j=0;j<entries.length();j++){JSONObject e=entries.getJSONObject(j);if("LOCAL_APPLIED".equals(e.getString("state"))&&(!e.optString("kind").equals("DIRECTORY")||!e.optBoolean("directoryPresent",true)))e.put("state","PUBLISHED").put("commitSha",commit);if("PUBLISHED".equals(e.getString("state")))e.put("overlayActive",false);}aggregate(set);}state.put("publishing",false);save(state);}}
    public String publishingFingerprint()throws Exception{synchronized(LOCK){JSONObject state=load();return state.optBoolean("publishing")?state.optString("publishingFingerprint"):"";}}
    public void unfreeze()throws Exception{synchronized(LOCK){JSONObject state=load();state.put("publishing",false);save(state);}}
    public java.util.Set<String> directories(WorkspaceSource source)throws Exception {
        synchronized(LOCK){Set<String> directories=new TreeSet<>(source.directories());JSONArray sets=load().getJSONArray("sets");
            for(String path:paths(source)){int slash=path.indexOf('/');while(slash>0){directories.add(path.substring(0,slash));slash=path.indexOf('/',slash+1);}}
            for(int i=0;i<sets.length();i++){JSONArray entries=sets.getJSONObject(i).getJSONArray("entries");for(int j=0;j<entries.length();j++){JSONObject e=entries.getJSONObject(j);if(e.optString("kind").equals("DIRECTORY")&&applied(e)){
                if(e.optBoolean("directoryPresent",true))directories.add(e.getString("path"));else directories.removeIf(dir->dir.equals(e.optString("path"))||dir.startsWith(e.optString("path")+"/"));}}}return directories;}
    }
    public String directorySha(WorkspaceSource source,String path)throws Exception {
        path(path);synchronized(LOCK){if(!directories(source).contains(path))return "missing";
            StringBuilder map=new StringBuilder("directory:"+path+"\n");for(String file:paths(source))if(file.startsWith(path+"/"))map.append(file).append(':').append(snapshot(source,file).sha).append('\n');return GitObjectId.blob(map.toString());}
    }
    public JSONObject proposeDirectory(WorkspaceSource source,String path)throws Exception {
        return propose(source,"Создание каталога: "+path,new JSONArray().put(new JSONObject().put("path",path).put("directory",true).put("directory_present",true).put("expected_sha","missing")));
    }
    private static void mutable(JSONObject state)throws Conflict {if(state.optBoolean("publishing"))throw new Conflict("Публикация выполняется или требует сверки; изменения временно заблокированы");}
    public String fingerprint(WorkspaceSource source)throws Exception {return GitObjectId.blob(delta(source).toString());}
    public void freeze(WorkspaceSource source,String expected)throws Exception {
        synchronized(LOCK){JSONObject state=load();mutable(state);if(!expected.equals(fingerprint(source)))throw new Conflict("Дельта изменилась после preview");state.put("publishing",true).put("publishingFingerprint",expected);save(state);}
    }
    public void verifyFrozen(WorkspaceSource source,String expected)throws Exception {
        synchronized(LOCK){if(!load().optBoolean("publishing")||!expected.equals(fingerprint(source)))throw new Conflict("Публикация потеряла исходный снимок");}
    }
    private static void aggregate(JSONObject set)throws Exception {
        Set<String> states=new HashSet<>();JSONArray entries=set.getJSONArray("entries");for(int i=0;i<entries.length();i++)states.add(entries.getJSONObject(i).getString("state"));
        String value=states.contains("LOCAL_APPLIED")?"LOCAL_APPLIED":states.contains("APPROVED")?"APPROVED":states.contains("PROPOSED")?"PROPOSED":states.contains("PUBLISHED")?"PUBLISHED":states.contains("ROLLED_BACK")?"ROLLED_BACK":"REJECTED";set.put("state",value);
    }
}
