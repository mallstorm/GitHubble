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
    @Test public void deletesOnlySelectedWorkspaceAndItsPrivateCheckpoints()throws Exception{
        File root=Files.createTempDirectory("workspace-delete").toFile();
        WorkspaceHub hub=new WorkspaceHub(root);
        String selected=hub.create("A","agent-sandbox").getString("id");
        String retained=hub.create("A","Important project").getString("id");
        String other=hub.create("B","Other account").getString("id");
        hub.saveFile("A",selected,"src/one.txt","remove");
        hub.saveFile("A",retained,"src/two.txt","preserve");
        File selectedRoot=hub.root("A",selected);
        File checkpointRoot=new File(selectedRoot.getParentFile(),selected+"-checkpoints");
        assertTrue(checkpointRoot.mkdirs());
        Files.writeString(new File(checkpointRoot,"snapshot.zip").toPath(),"checkpoint");
        hub.deleteLocal("A",selected);
        assertFalse(selectedRoot.exists());
        assertFalse(checkpointRoot.exists());
        assertEquals("preserve",hub.readFile("A",retained,"src/two.txt"));
        assertEquals(other,hub.info("B",other).getString("id"));
        try{hub.deleteLocal("B",selected);fail("Cross-account deletion allowed");}
        catch(IOException expected){}
    }
    @Test public void deleteWorkspaceDoesNotFollowSymlinks()throws Exception{
        File root=Files.createTempDirectory("workspace-symlink").toFile();
        File outside=Files.createTempDirectory("workspace-outside").toFile();
        File important=new File(outside,"keep.txt");
        Files.writeString(important.toPath(),"preserved");
        WorkspaceHub hub=new WorkspaceHub(root);
        String id=hub.create("A","Project").getString("id");
        Files.createSymbolicLink(new File(hub.root("A",id),"outside-link").toPath(),outside.toPath());
        hub.deleteLocal("A",id);
        assertTrue("External file was deleted via symlink",important.isFile());
        assertEquals("preserved",Files.readString(important.toPath()));
    }
}
