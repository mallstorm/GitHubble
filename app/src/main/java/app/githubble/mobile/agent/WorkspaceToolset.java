package app.githubble.mobile.agent;

import java.util.*;
import org.json.*;

/** One task-scoped file surface for local, SAF and lazy GitHub snapshots. */
public final class WorkspaceToolset {
    public final WorkspaceSource source;
    public final ChangeStore changes;
    public WorkspaceToolset(WorkspaceSource source,ChangeStore changes){this.source=source;this.changes=changes;}
    public static boolean readable(String path){try{ChangeStore.path(path);return true;}catch(Exception denied){return false;}}
    public List<String> visiblePaths()throws Exception {List<String> paths=new ArrayList<>();for(String path:changes.paths(source))if(readable(path))paths.add(path);return paths;}
    public WorkspaceSource.Snapshot snapshot(String path)throws Exception{return changes.snapshot(source,path);}
    public JSONObject list(int offset,int limit,String prefix)throws Exception {
        if(offset<0||limit<1||limit>200)throw new IllegalArgumentException("Пагинация: offset >= 0, limit 1–200");
        List<String> all=changes.paths(source),paths=new ArrayList<>();for(String path:all)if(readable(path))paths.add(path);int omitted=all.size()-paths.size();if(prefix!=null&&!prefix.isEmpty())paths.removeIf(p->!p.startsWith(prefix));
        JSONArray page=new JSONArray();int end=Math.min(paths.size(),offset+limit);for(int i=Math.min(offset,paths.size());i<end;i++)page.put(paths.get(i));
        return new JSONObject().put("files",page).put("total",paths.size()).put("omitted_protected_paths",omitted).put("next_offset",end<paths.size()?end:JSONObject.NULL);
    }
    public JSONObject read(String path,int first,int count)throws Exception {
        if(first<1||count<1||count>500)throw new IllegalArgumentException("Диапазон: от строки 1, до 500 строк");
        WorkspaceSource.Snapshot snapshot=snapshot(path);
        if(snapshot.text==null)return new JSONObject().put("path",path).put("exists",false).put("sha","missing");
        String[] lines=snapshot.text.split("\n",-1);StringBuilder body=new StringBuilder();int end=Math.min(lines.length,first-1+count),last=first-1;
        for(int i=first-1;i<end;i++){String line=lines[i];if(body.length()+line.length()>20000)break;body.append(line);if(i+1<lines.length)body.append('\n');last=i+1;}
        String safe=app.githubble.mobile.memory.MemoryPolicy.INSTANCE.redactSecrets(body.toString());
        return new JSONObject().put("path",path).put("exists",true).put("sha",snapshot.sha).put("origin_sha",snapshot.originSha)
            .put("start_line",first).put("end_line",last).put("total_lines",lines.length).put("truncated",last<lines.length).put("content",safe);
    }
    public JSONObject search(String query,int offset,String prefix)throws Exception {
        if(query==null||query.length()<2||query.length()>120||offset<0)throw new IllegalArgumentException("Некорректный поиск");
        List<String> paths=visiblePaths();if(prefix!=null&&!prefix.isEmpty())paths.removeIf(p->!p.startsWith(prefix));
        JSONArray hits=new JSONArray();int end=Math.min(paths.size(),offset+32);JSONArray skipped=new JSONArray();
        for(int i=Math.min(offset,paths.size());i<end;i++){
            String path=paths.get(i);WorkspaceSource.Snapshot snapshot;
            try{snapshot=snapshot(path);}catch(java.io.IOException unreadable){skipped.put(path);continue;}
            if(snapshot.text==null)continue;String[] lines=snapshot.text.split("\n",-1);
            for(int n=0;n<lines.length&&hits.length()<80;n++)if(lines[n].toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                hits.put(new JSONObject().put("path",path).put("line",n+1).put("preview",app.githubble.mobile.memory.MemoryPolicy.INSTANCE.redactSecrets(lines[n].substring(0,Math.min(220,lines[n].length())))));
        }
        return new JSONObject().put("hits",hits).put("scanned_files",Math.max(0,end-offset)).put("skipped_files",skipped).put("partial",end<paths.size()).put("next_offset",end<paths.size()?end:JSONObject.NULL);
    }
    public JSONObject projectMap()throws Exception {
        List<String> paths=visiblePaths();Map<String,Integer> folders=new TreeMap<>();for(String path:paths)folders.merge(path.contains("/")?path.substring(0,path.indexOf('/')):"/",1,Integer::sum);
        return new JSONObject().put("source",source.identity()).put("files",paths.size()).put("top_level",new JSONObject(folders)).put("created_directories",new JSONArray(changes.directories(source).stream().filter(WorkspaceToolset::readable).collect(java.util.stream.Collectors.toList()))).put("import",source.importReport());
    }
    public JSONObject write(JSONArray files,String summary)throws Exception {
        JSONArray request=new JSONArray();for(int i=0;i<files.length();i++){
            JSONObject f=files.getJSONObject(i);String path=f.getString("path");WorkspaceSource.Snapshot current=snapshot(path);
            String expected=f.optString("expected_sha",current.text==null?"missing":"");
            request.put(new JSONObject().put("path",path).put("expected_sha",expected).put("content",f.getString("content")));
        }return changes.propose(source,summary,request);
    }
    public JSONObject replace(String path,String expected,String oldText,String newText)throws Exception {
        if(oldText==null||oldText.isEmpty())throw new IllegalArgumentException("old_text не может быть пустым");
        WorkspaceSource.Snapshot current=snapshot(path);if(current.text==null||!current.sha.equals(expected))throw new ChangeStore.Conflict("Файл изменился или не существует");
        int at=current.text.indexOf(oldText);if(at<0||current.text.indexOf(oldText,at+oldText.length())>=0)throw new ChangeStore.Conflict("Точная замена требует ровно одного совпадения");
        return write(new JSONArray().put(new JSONObject().put("path",path).put("expected_sha",expected)
            .put("content",current.text.substring(0,at)+newText+current.text.substring(at+oldText.length()))),"Точная замена: "+path);
    }
    public JSONObject patch(String path,String expected,String patch)throws Exception {
        WorkspaceSource.Snapshot current=snapshot(path);if(!current.sha.equals(expected))throw new ChangeStore.Conflict("SHA patch не совпадает");
        String after=UnifiedPatch.apply(path,current.text,patch);
        return changes.propose(source,"Unified patch: "+path,new JSONArray().put(new JSONObject().put("path",path).put("expected_sha",expected).put("content",after)));
    }
    public JSONObject delete(String path,String expected,boolean directory)throws Exception {
        ChangeStore.path(path);JSONArray entries=new JSONArray();List<String> paths=directory?under(path):Collections.singletonList(path);
        for(String file:paths){WorkspaceSource.Snapshot s=snapshot(file);if(s.text==null)throw new IllegalArgumentException("Файл не найден: "+file);
            if(!directory&&!s.sha.equals(expected))throw new ChangeStore.Conflict("SHA удаления не совпадает");
            entries.put(new JSONObject().put("path",file).put("expected_sha",s.sha).put("content",JSONObject.NULL).put("group",path));}
        if(directory)entries.put(new JSONObject().put("path",path).put("directory",true).put("directory_present",false).put("expected_sha",expected).put("group",path));
        if(directory&&!directoryRevision(path).equals(expected))throw new ChangeStore.Conflict("Карта каталога изменилась");
        return changes.propose(source,"Удаление: "+path,entries);
    }
    private List<String> under(String path)throws Exception {
        List<String> files=new ArrayList<>();for(String file:changes.paths(source))if(file.startsWith(path+"/")){ChangeStore.path(file);files.add(file);}
        if(files.isEmpty()&&!changes.directories(source).contains(path))throw new IllegalArgumentException("Каталог не найден");if(files.size()>100)throw new IllegalArgumentException("Каталог больше 100 файлов; выберите меньшую область");return files;
    }
    public String directoryRevision(String path)throws Exception {String sha=changes.directorySha(source,path);if(sha.equals("missing"))throw new IllegalArgumentException("Каталог не найден");return sha;}
    public JSONObject move(String from,String to,String expected,boolean directory)throws Exception {
        ChangeStore.path(from);ChangeStore.path(to);if(to.startsWith(from+"/")||from.equals(to))throw new IllegalArgumentException("Некорректное перемещение");
        JSONArray entries=new JSONArray();List<String> paths=directory?under(from):Collections.singletonList(from);Set<String> all=new HashSet<>(changes.paths(source));
        if(directory&&!directoryRevision(from).equals(expected))throw new ChangeStore.Conflict("Карта каталога изменилась");
        for(String path:paths){WorkspaceSource.Snapshot old=snapshot(path);if(old.text==null||!directory&&!old.sha.equals(expected))throw new ChangeStore.Conflict("SHA перемещения не совпадает");
            String target=directory?to+path.substring(from.length()):to;ChangeStore.path(target);
            if(all.contains(target)||all.stream().anyMatch(p->p.startsWith(target+"/")))throw new ChangeStore.Conflict("Путь назначения занят: "+target);
            entries.put(new JSONObject().put("path",path).put("expected_sha",old.sha).put("content",JSONObject.NULL).put("group",from));
            entries.put(new JSONObject().put("path",target).put("expected_sha","missing").put("content",old.text).put("mode",old.mode).put("group",from));
        }
        if(directory){
            entries.put(new JSONObject().put("path",from).put("directory",true).put("directory_present",false).put("expected_sha",expected).put("group",from));
            entries.put(new JSONObject().put("path",to).put("directory",true).put("directory_present",true).put("expected_sha","missing").put("group",from));
        }
        return changes.propose(source,"Перемещение: "+from+" → "+to,entries);
    }
    public String diff(JSONObject set)throws Exception {
        StringBuilder out=new StringBuilder(set.optString("summary")+"\n");JSONArray entries=set.getJSONArray("entries");
        for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);out.append("\n").append(e.getString("state")).append("\n")
            .append(TextEdits.diff(e.getString("path"),e.isNull("before")?null:e.getString("before"),e.isNull("after")?null:e.getString("after")));}
        return out.toString();
    }
}
