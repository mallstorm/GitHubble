package app.githubble.mobile.repository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Portable, integrity-checked ZIP containing *GitHub metadata*.
 * Git object history, LFS objects and release binaries are handled separately
 * by existing Mirror Manager, never misrepresented as contents of this archive.
 */
public final class RepositoryBackupArchive {
    private RepositoryBackupArchive() {}
    public static final String FORMAT = "githubble-repository-metadata-v1";
    public static final int MAX_ARCHIVE_BYTES = 24 * 1024 * 1024;
    public static final int MAX_ENTRY_BYTES = 12 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 36 * 1024 * 1024;
    public static final String[] SECTIONS = {
            "repository", "branches", "tags", "labels", "milestones",
            "issues", "issue_comments", "pulls", "pull_comments", "releases"
    };

    public static final class Archive {
        public final JSONObject manifest;
        public final Map<String,JSONObject> sections;
        Archive(JSONObject manifest,Map<String,JSONObject> sections) {
            this.manifest=manifest; this.sections=Collections.unmodifiableMap(sections);
        }
        public JSONObject section(String name) {
            JSONObject value=sections.get(name);
            if(value==null)throw new IllegalStateException("Отсутствует раздел "+name);
            return value;
        }
        public JSONArray list(String name) {
            JSONArray a=section(name).optJSONArray("items");
            if(a==null)throw new IllegalStateException("Нарушен список "+name);
            return a;
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder str=new StringBuilder();
        for(byte b:digest)str.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return str.toString();
    }

    static String file(String section) {
        for(String allowed:SECTIONS)if(allowed.equals(section))return section+".json";
        throw new IllegalArgumentException("Недопустимый раздел архива");
    }

    /** Written sections are JSON objects; manifest binds each to SHA-256 and byte length. */
    public static void write(JSONObject source, OutputStream output) throws Exception {
        if(source==null||output==null)throw new IllegalArgumentException("Нет данных для архива");
        String repo=source.optString("repository","");
        app.githubble.mobile.mirror.MirrorPolicy.requireRepository(repo);
        JSONObject data=source.optJSONObject("sections");
        if(data==null)throw new IllegalArgumentException("Нет разделов резервной копии");
        JSONObject manifest=new JSONObject().put("format",FORMAT)
                .put("repository",repo)
                .put("private",source.optBoolean("private",true))
                .put("created_at",source.optString("created_at",""))
                .put("default_branch",source.optString("default_branch",""))
                .put("default_sha",source.optString("default_sha",""))
                .put("scope","metadata_only");
        if(!manifest.optString("default_sha").matches("[0-9a-f]{40}"))
            throw new SecurityException("Не подтверждён исходный Git SHA");
        JSONObject hashes=new JSONObject();
        Map<String,byte[]> bytes=new LinkedHashMap<>();
        long all=0;
        for(String section:SECTIONS) {
            JSONObject value=data.optJSONObject(section);
            if(value==null)throw new IllegalArgumentException("Отсутствует раздел "+section);
            if(!"repository".equals(section)&&value.optJSONArray("items")==null)
                throw new IllegalArgumentException("Отсутствует список "+section);
            byte[] raw=value.toString().getBytes(StandardCharsets.UTF_8);
            if(raw.length>MAX_ENTRY_BYTES||(all+=raw.length)>MAX_TOTAL_BYTES)
                throw new IllegalStateException("Метаданные превышают ограничение 36 МиБ");
            bytes.put(file(section),raw);
            hashes.put(file(section),new JSONObject().put("sha256",sha256(raw))
                    .put("bytes",raw.length));
        }
        manifest.put("entries",hashes);
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            zip.setLevel(6);
            for(Map.Entry<String,byte[]> e:bytes.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("manifest.json"));
            byte[] m=manifest.toString().getBytes(StandardCharsets.UTF_8);
            zip.write(m);zip.closeEntry();
            zip.finish();
        }
    }

    /** Rejects zip slip, duplicates, unknown files, truncated ZIP and decompression bombs. */
    public static Archive read(InputStream input) throws Exception {
        if(input==null)throw new IllegalArgumentException("Архив не выбран");
        Map<String,byte[]> raw=new LinkedHashMap<>();
        Set<String> allow=new HashSet<>();
        for(String s:SECTIONS)allow.add(file(s));
        allow.add("manifest.json");
        long total=0;
        try(ZipInputStream zip=new ZipInputStream(input)) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();
                if(entry.isDirectory()||!allow.contains(name)||raw.containsKey(name))
                    throw new SecurityException("Неизвестная или повторяющаяся запись ZIP: "+name);
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                byte[] chunk=new byte[8192];int n;
                while((n=zip.read(chunk))!=-1) {
                    if(buffer.size()+n>MAX_ENTRY_BYTES||(total+=n)>MAX_TOTAL_BYTES)
                        throw new SecurityException("Слишком большой архив GitHubble");
                    buffer.write(chunk,0,n);
                }
                raw.put(name,buffer.toByteArray());zip.closeEntry();
            }
        }
        if(raw.size()!=SECTIONS.length+1||!raw.containsKey("manifest.json"))
            throw new SecurityException("Архив GitHubble неполный");
        JSONObject manifest=new JSONObject(new String(raw.get("manifest.json"),StandardCharsets.UTF_8));
        if(!FORMAT.equals(manifest.optString("format"))
                ||!"metadata_only".equals(manifest.optString("scope")))
            throw new SecurityException("Неизвестный формат резервной копии");
        app.githubble.mobile.mirror.MirrorPolicy.requireRepository(manifest.optString("repository",""));
        if(!manifest.optString("default_sha").matches("[0-9a-f]{40}"))
            throw new SecurityException("Отсутствует SHA исходного снимка");
        JSONObject hashes=manifest.optJSONObject("entries");
        if(hashes==null||hashes.length()!=SECTIONS.length)
            throw new SecurityException("Некорректный перечень контрольных сумм");
        Map<String,JSONObject> sections=new LinkedHashMap<>();
        for(String name:SECTIONS) {
            String filename=file(name);
            JSONObject checksum=hashes.optJSONObject(filename);
            byte[] body=raw.get(filename);
            if(checksum==null||body==null
                    ||checksum.optInt("bytes",-1)!=body.length
                    ||!sha256(body).equals(checksum.optString("sha256","")))
                throw new SecurityException("Повреждён раздел архива: "+filename);
            JSONObject value=new JSONObject(new String(body,StandardCharsets.UTF_8));
            if(!"repository".equals(name)&&value.optJSONArray("items")==null)
                throw new SecurityException("Отсутствуют данные раздела: "+name);
            sections.put(name,value);
        }
        return new Archive(manifest,sections);
    }
}
