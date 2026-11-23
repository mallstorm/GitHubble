package app.githubble.mobile.repository;

import app.githubble.mobile.mirror.MirrorPolicy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/**
 * Streaming preflight of a portable GitHubble full-repository Actions artifact.
 * Reads all payloads, compares sizes and SHA-256 with the archive manifest and
 * rejects missing/extra entries, traversal, duplicates and excessive size.
 *
 * SHA-256 ensures consistency, NOT identity or authenticity of the ZIP creator.
 */
public final class PortableRepositoryArchive {
    private PortableRepositoryArchive() {}
    public static final String FORMAT="githubble-full-repository-v1";
    public static final long MAX_ENTRY=2_000_000_000L;
    public static final long MAX_TOTAL=2_000_000_000L;
    public static final int MAX_FILES=100000;
    public static final int MAX_MANIFEST=8*1024*1024;

    public interface Progress {void step(int percentage,String name);}
    public static final class Inspection {
        public final JSONObject manifest;
        public final String source,branch,sha;
        public final int files,refs;
        public final boolean releases;
        public final long unpackedBytes;
        Inspection(JSONObject m,int fileCount,int refCount,long bytes) {
            manifest=m;source=m.optString("source","");
            branch=m.optString("default_branch","");
            sha=m.optString("default_sha","");
            files=fileCount;refs=refCount;
            releases=m.optBoolean("release_binaries");
            unpackedBytes=bytes;
        }
    }

    static boolean safeEntry(String path) {
        if(path==null||path.isEmpty()||path.length()>300||path.startsWith("/")||
                path.startsWith("\\")||path.contains("\\")||path.contains("//")||
                path.contains("\0")||path.contains(":")||path.endsWith("/"))
            return false;
        for(String item:path.split("/"))
            if(item.isEmpty()||item.equals(".")||item.equals(".."))return false;
        return true;
    }

    static String hash(byte[] bytes) throws Exception {
        byte[] value=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text=new StringBuilder();
        for(byte b:value)text.append(String.format(Locale.ROOT,"%02x",b&255));
        return text.toString();
    }

    private static String hex(byte[] digest) {
        StringBuilder value=new StringBuilder();
        for(byte b:digest)value.append(String.format(Locale.ROOT,"%02x",b&255));
        return value.toString();
    }

    /** Returns a validated manifest only after the full ZIP passes. */
    public static Inspection inspect(InputStream archive,Progress progress)throws Exception {
        if(archive==null)throw new IllegalArgumentException("Не удалось открыть ZIP");
        Map<String,String> digests=new HashMap<>();
        Map<String,Long> sizes=new HashMap<>();
        Set<String> seen=new HashSet<>();
        JSONObject manifest=null;
        long total=0L;int count=0,refs=0;
        try(ZipInputStream zip=new ZipInputStream(archive)) {
            ZipEntry entry;
            byte[] chunk=new byte[64*1024];
            while((entry=zip.getNextEntry())!=null) {
                if(entry.isDirectory())continue;
                String name=entry.getName();
                if(!safeEntry(name)||!seen.add(name)||++count>MAX_FILES)
                    throw new SecurityException("Некорректное имя или повтор записи ZIP");
                if(!name.equals("manifest.json")&&!name.equals("metadata.json")
                        &&!name.equals("repository.bundle")&&!name.equals("git-refs.txt")
                        &&!name.startsWith("lfs/objects/")
                        &&!name.startsWith("release-assets/"))
                    throw new SecurityException("Неизвестная запись архива: "+name);
                if(progress!=null && (count==1||count%20==0))
                    progress.step(10,"Сверка информации · "+name);
                long current=0;
                MessageDigest sha=MessageDigest.getInstance("SHA-256");
                ByteArrayOutputStream small=name.equals("manifest.json")||name.equals("git-refs.txt")
                        ?new ByteArrayOutputStream():null;
                int n;
                while((n=zip.read(chunk))!=-1) {
                    if(n==0)continue;
                    current+=n;total+=n;
                    if(current>MAX_ENTRY||total>MAX_TOTAL)
                        throw new SecurityException("Архив больше лимита 2 ГБ");
                    if(small!=null&&current>MAX_MANIFEST)
                        throw new SecurityException("Служебная запись слишком большая");
                    sha.update(chunk,0,n);
                    if(small!=null)small.write(chunk,0,n);
                }
                if(name.equals("manifest.json"))
                    manifest=new JSONObject(new String(small.toByteArray(),StandardCharsets.UTF_8));
                if(name.equals("git-refs.txt")) {
                    String content=new String(small.toByteArray(),StandardCharsets.UTF_8);
                    if(content.length()>MAX_MANIFEST)throw new SecurityException("Список refs превышен");
                    refs=content.isEmpty()?0:content.split("\n").length;
                }
                sizes.put(name,current);digests.put(name,hex(sha.digest()));
                zip.closeEntry();
            }
        }
        if(progress!=null)progress.step(60,"Проверка контрольных сумм SHA-256");
        if(manifest==null||!FORMAT.equals(manifest.optString("format"))
                ||!manifest.optBoolean("complete",false))
            throw new SecurityException("Неизвестный или незавершённый архив GitHubble");
        MirrorPolicy.requireRepository(manifest.optString("source",""));
        if(!manifest.optString("default_sha","").matches("[0-9a-f]{40}")
                ||manifest.optString("default_branch","").isEmpty())
            throw new SecurityException("Неизвестная исходная ревизия Git");
        JSONObject entries=manifest.optJSONObject("files");
        if(entries==null||entries.length()!=count-1||!sizes.containsKey("repository.bundle")
                ||!sizes.containsKey("metadata.json")||!sizes.containsKey("git-refs.txt"))
            throw new SecurityException("Архив неполный: отсутствуют обязательные разделы");
        if(manifest.optBoolean("release_binaries")&& !sizes.containsKey("metadata.json"))
            throw new SecurityException("Отсутствуют данные о релизах");
        for(String path:sizes.keySet()){
            if("manifest.json".equals(path))continue;
            JSONObject expected=entries.optJSONObject(path);
            if(expected==null||expected.optLong("bytes",-1)!=sizes.get(path)
                    ||!digests.get(path).equals(expected.optString("sha256","")))
                throw new SecurityException("Повреждён или подменён файл архива: "+path);
        }
        if(progress!=null)progress.step(100,"ZIP проверен · контрольные суммы совпадают");
        return new Inspection(manifest,count,refs,total);
    }
}
