package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
public class WorkspaceHubTest {
    @Test public void projectsPersistLocallyWithoutRepositoryAndStayAccountScoped()throws Exception{
        File root=Files.createTempDirectory("workspace-test").toFile();WorkspaceHub hub=new WorkspaceHub(root);
        String id=hub.create("A","Local app").getString("id");hub.saveFile("A",id,"src/main.txt","test");
        assertEquals("test",new WorkspaceHub(root).readFile("A",id,"src/main.txt"));assertEquals(0,hub.list("B").length());
        try{hub.root("B",id);fail();}catch(IOException expected){}
        assertTrue(hub.context("A",id).contains("src/main.txt"));
    }
    @Test public void zipTraversalNeverWritesOutsideWorkspace()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("workspace-test").toFile());String id=hub.create("A","Project").getString("id");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(bytes)){z.putNextEntry(new ZipEntry("../escape.txt"));z.write(new byte[]{1});z.closeEntry();}
        try{hub.importZip("A",id,new ByteArrayInputStream(bytes.toByteArray()));fail();}catch(IOException expected){}
        for(String path:new String[]{"../file","a/../file","/tmp/file",".workspace.json",".git/config"}){try{hub.saveFile("A",id,path,"bad");fail(path);}catch(IOException expected){}}
    }
}
