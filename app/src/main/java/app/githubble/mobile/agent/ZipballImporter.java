package app.githubble.mobile.agent;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Bounded stream-to-disk import. Central attributes reject links before any extraction. */
public final class ZipballImporter {
    private ZipballImporter() {}
    public static final class Limits {
        public long compressed=32L*1024*1024,text=32L*1024*1024,expanded=64L*1024*1024,perFile=1024*1024;
        public int files=10000;
        void validate(){if(compressed<1||compressed>200L*1024*1024||files<1||files>20000||text<1||text>150L*1024*1024
            ||expanded<1||expanded>256L*1024*1024||perFile<1||perFile>2L*1024*1024)throw new IllegalArgumentException("Небезопасные лимиты импорта");}
    }
    public static JSONObject extract(InputStream source,File destination,String commit,Limits limits,Runnable guard)throws Exception {
        GitObjectId.requireCommit(commit);limits.validate();if(destination.exists())throw new IOException("Импорт не перезаписывает существующий каталог");
        File parent=destination.getAbsoluteFile().getParentFile();Files.createDirectories(parent.toPath());
        File archive=File.createTempFile("zipball-",".zip",parent);Path staging=Files.createTempDirectory(parent.toPath(),"snapshot-");boolean installed=false;
        try{
            long compressed=0;byte[] buffer=new byte[16384];
            try(OutputStream out=new FileOutputStream(archive)){int n;while((n=source.read(buffer))!=-1){guard.run();compressed+=n;if(compressed>limits.compressed)throw new IOException("Zipball больше лимита загрузки");out.write(buffer,0,n);}}
            inspectCentral(archive,limits.files);
            JSONArray map=new JSONArray();long textBytes=0,expanded=0;int imported=0,skipped=0;
            try(ZipFile zip=new ZipFile(archive,StandardCharsets.UTF_8)){
                List<? extends ZipEntry> entries=Collections.list(zip.entries());if(entries.size()>limits.files)throw new IOException("Слишком много записей ZIP");
                String prefix=null;for(ZipEntry e:entries){String path=e.getName();int slash=path.indexOf('/');if(slash<1)throw new IOException("Zipball должен иметь один корневой каталог");String root=path.substring(0,slash+1);if(root.equals("../")||root.equals("./")||!root.matches("[A-Za-z0-9_.-]+/"))throw new IOException("Недопустимый корень zipball");if(prefix==null)prefix=root;else if(!prefix.equals(root))throw new IOException("Несколько корней zipball");}
                Set<String> seen=new HashSet<>();
                for(ZipEntry entry:entries){guard.run();String path=entry.getName().substring(prefix==null?0:prefix.length());
                    if(path.isEmpty())continue;if(entry.isDirectory()&&path.endsWith("/"))path=path.substring(0,path.length()-1);
                    validatePath(path);if(!seen.add(path.toLowerCase(Locale.ROOT)))throw new IOException("Повтор/коллизия пути ZIP: "+path);
                    if(entry.isDirectory())continue;
                    String reason="";
                    if(!WorkspaceToolset.readable(path)||path.equals(".workspace.json")||path.equals(".hubble-import.json"))reason="protected_path";
                    if(entry.getSize()<0||entry.getSize()>limits.perFile)reason="file_size";
                    if(entry.getSize()>1024*1024&&entry.getCompressedSize()>0&&entry.getSize()/entry.getCompressedSize()>1000)throw new IOException("Подозрительная степень сжатия ZIP");
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();long count=0;java.util.zip.CRC32 crc=new java.util.zip.CRC32();
                    try(InputStream in=zip.getInputStream(entry)){int n;while((n=in.read(buffer))!=-1){guard.run();count+=n;expanded+=n;crc.update(buffer,0,n);if(expanded>limits.expanded)throw new IOException("ZIP превышает лимит распаковки");
                        if(reason.isEmpty()&&count<=limits.perFile)bytes.write(buffer,0,n);else if(count>limits.perFile)reason="file_size";}}
                    if(crc.getValue()!=entry.getCrc())throw new IOException("CRC ZIP не совпадает");
                    if(count!=entry.getSize())throw new IOException("Размер ZIP не совпадает");
                    byte[] data=bytes.toByteArray();String text="";
                    if(reason.isEmpty()){
                        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();}
                        catch(CharacterCodingException binary){reason="binary";}
                        if(text.indexOf('\0')>=0)reason="binary";
                        if(reason.isEmpty()&&!text.equals(app.githubble.mobile.memory.MemoryPolicy.INSTANCE.redactSecrets(text)))reason="secret_content";
                        if(reason.isEmpty()&&textBytes+data.length>limits.text)reason="text_budget";
                    }
                    JSONObject row=new JSONObject().put("path",path).put("bytes",count);
                    if(reason.isEmpty()){
                        Path target=staging.resolve(path);Files.createDirectories(target.getParent());Files.write(target,data,StandardOpenOption.CREATE_NEW);
                        textBytes+=data.length;imported++;row.put("status","IMPORTED").put("sha",GitObjectId.blob(data));
                    }else{skipped++;row.put("status","SKIPPED").put("reason",reason);}
                    map.put(row);
                }
            }
            JSONObject report=new JSONObject().put("source_sha",commit).put("imported",imported).put("skipped",skipped).put("partial",skipped>0)
                .put("compressed_bytes",compressed).put("text_bytes",textBytes).put("files",map);
            Files.write(staging.resolve(".hubble-import.json"),report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            guard.run();try{Files.move(staging,destination.toPath(),StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unavailable){Files.move(staging,destination.toPath());}
            installed=true;return report;
        }finally{Files.deleteIfExists(archive.toPath());if(!installed)deleteTree(staging);}
    }
    private static void validatePath(String path)throws IOException {
        try{AgentFilePlan.validatePath(path);}catch(IllegalArgumentException invalid){throw new IOException("Zip-slip/недопустимый путь ZIP",invalid);}
        if(path.endsWith("/")||path.contains(":")||path.matches(".*[\\x00-\\x1f].*"))throw new IOException("Некорректный путь ZIP");
    }
    private static long u32(RandomAccessFile file)throws IOException{return Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));}
    private static int u16(RandomAccessFile file)throws IOException{return Short.toUnsignedInt(Short.reverseBytes(file.readShort()));}
    /** ZIP64/encryption and all Unix special files are outside the bounded snapshot format. */
    private static void inspectCentral(File archive,int maxEntries)throws IOException {
        try(RandomAccessFile file=new RandomAccessFile(archive,"r")){
            long end=-1;for(long p=file.length()-22;p>=Math.max(0,file.length()-65557);p--){file.seek(p);if(u32(file)==0x06054b50L){file.seek(p+20);int comment=u16(file);if(p+22+comment==file.length()){end=p;break;}}}
            if(end<0)throw new IOException("Нет конца ZIP");file.seek(end+4);int disk=u16(file),centralDisk=u16(file),localCount=u16(file),count=u16(file);long size=u32(file),offset=u32(file);
            if(disk!=0||centralDisk!=0||count!=localCount||count==65535||count>maxEntries||offset+size!=end)throw new IOException("ZIP64/multi-disk/лимит ZIP");
            file.seek(offset);long centralEnd=offset+size;
            for(int i=0;i<count;i++){
                if(file.getFilePointer()+46>centralEnd||u32(file)!=0x02014b50L)throw new IOException("Повреждён central directory");
                u16(file);u16(file);int flags=u16(file);u16(file);file.skipBytes(16);int name=u16(file),extra=u16(file),comment=u16(file);int startDisk=u16(file);u16(file);long attrs=u32(file);u32(file);
                int unixMode=(int)(attrs>>>16)&0xf000;
                if((flags&1)!=0||startDisk!=0||unixMode!=0&&unixMode!=0x8000&&unixMode!=0x4000)throw new IOException("Ссылка/специальный/зашифрованный файл ZIP");
                long next=file.getFilePointer()+name+extra+comment;if(next>centralEnd)throw new IOException("Повреждён ZIP");file.seek(next);
            }
            if(file.getFilePointer()!=centralEnd)throw new IOException("Число записей ZIP не совпадает");
        }
    }
    static void deleteTree(Path path)throws IOException {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;
        Files.walkFileTree(path,new SimpleFileVisitor<Path>(){
            public FileVisitResult visitFile(Path p,java.nio.file.attribute.BasicFileAttributes a)throws IOException{Files.delete(p);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path p,IOException error)throws IOException{if(error!=null)throw error;Files.delete(p);return FileVisitResult.CONTINUE;}
        });
    }
}
