package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
import java.nio.file.*;

public class ZipballImporterTest {
    private byte[] zip(Map<String,byte[]> files)throws Exception{java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)){for(Map.Entry<String,byte[]> e:files.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}return bytes.toByteArray();}
    private JSONObject extract(byte[] bytes,java.io.File target,ZipballImporter.Limits limits)throws Exception{return ZipballImporter.extract(new java.io.ByteArrayInputStream(bytes),target,"1".repeat(40),limits,()->{});}
    @Test public void partialImportRecordsBinaryAndSecretReasonsAtPinnedSha()throws Exception {
        java.io.File target=Files.createTempDirectory("zip").resolve("snapshot").toFile();Map<String,byte[]> files=new LinkedHashMap<>();files.put("repo/src/Main.java","class Main {}".getBytes());files.put("repo/image.bin",new byte[]{0,1,2});files.put("repo/.env","KEY=private".getBytes());
        JSONObject report=extract(zip(files),target,new ZipballImporter.Limits());assertEquals(1,report.getInt("imported"));assertEquals(2,report.getInt("skipped"));assertTrue(report.getBoolean("partial"));assertEquals("1".repeat(40),report.getString("source_sha"));assertEquals("class Main {}",new String(Files.readAllBytes(target.toPath().resolve("src/Main.java")),java.nio.charset.StandardCharsets.UTF_8));assertTrue(new java.io.File(target,".hubble-import.json").isFile());
    }
    @Test public void zipSlipAndAbsolutePathsLeaveNoInstalledSnapshot()throws Exception {
        for(String path:new String[]{"repo/../../escape","/absolute","../escape","repo/dir/../escape"}){java.io.File target=Files.createTempDirectory("zip-safe").resolve("snapshot").toFile();try{extract(zip(Map.of(path,"x".getBytes())),target,new ZipballImporter.Limits());fail(path);}catch(java.io.IOException expected){}assertFalse(target.exists());}
    }
    @Test public void unixSymlinkIsRejectedFromCentralDirectory()throws Exception {
        byte[] bytes=zip(Map.of("repo/link","../../outside".getBytes()));for(int i=0;i+46<bytes.length;i++)if(bytes[i]==0x50&&bytes[i+1]==0x4b&&bytes[i+2]==1&&bytes[i+3]==2){int mode=0120777;bytes[i+38]=0;bytes[i+39]=0;bytes[i+40]=(byte)mode;bytes[i+41]=(byte)(mode>>>8);}
        java.io.File target=Files.createTempDirectory("zip-link").resolve("snapshot").toFile();try{extract(bytes,target,new ZipballImporter.Limits());fail();}catch(java.io.IOException expected){}assertFalse(target.exists());
    }
    @Test public void expansionAndDownloadLimitsAbortWithoutPartialInstall()throws Exception {
        byte[] bytes=zip(Map.of("repo/large","x".repeat(100000).getBytes()));for(boolean compressed:new boolean[]{true,false}){
            ZipballImporter.Limits limits=new ZipballImporter.Limits();if(compressed)limits.compressed=10;else limits.expanded=1000;
            java.io.File target=Files.createTempDirectory("zip-bomb").resolve("snapshot").toFile();try{extract(bytes,target,limits);fail();}catch(java.io.IOException expected){}assertFalse(target.exists());}
    }
    @Test public void existingDestinationCannotBeOverwritten()throws Exception {
        java.io.File target=Files.createTempDirectory("zip-existing").toFile();Files.write(target.toPath().resolve("keep"),"user".getBytes());try{extract(zip(Map.of("repo/new","x".getBytes())),target,new ZipballImporter.Limits());fail();}catch(java.io.IOException expected){}assertEquals("user",new String(Files.readAllBytes(target.toPath().resolve("keep")),java.nio.charset.StandardCharsets.UTF_8));
    }
}
