package app.githubble.mobile.agent;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class ProjectRuntimeTest {
    @Test public void limitsReadToolsToExplicitScopes(){
        assertTrue(ProjectRuntime.allowed("list_files",false,true));
        assertTrue(ProjectRuntime.allowed("repository_info",true,false));
        assertFalse(ProjectRuntime.allowed("list_files",true,false));
        assertFalse(ProjectRuntime.allowed("repository_info",false,true));
        assertFalse(ProjectRuntime.allowed("write_file",true,true));
        assertFalse(ProjectRuntime.allowed("delete_repository",true,true));
        assertFalse(ProjectRuntime.allowed("commit",true,true));
    }
    @Test public void modelSeesResultsFromLocalWorkspace()throws Exception{
        File tmp=Files.createTempDirectory("runtime-workspace").toFile();
        WorkspaceHub hub=new WorkspaceHub(tmp);
        String id=hub.create("account","Alpha").getString("id");
        hub.saveFile("account",id,"src/Main.java","class Main {}");
        AtomicInteger turns=new AtomicInteger();
        String answer=ProjectRuntime.analyze(prompt->{
            if(turns.incrementAndGet()==1)
                return "{\"tools\":[{\"name\":\"list_files\",\"args\":{}}]}";
            assertTrue(prompt.contains("src/Main.java"));
            return "{\"answer\":\"Файлы проверены\"}";
        },null,hub,"account","","main",id,"Какие есть файлы?","",false,true);
        assertEquals(2,turns.get());
        assertEquals("Файлы проверены",answer);
    }
    @Test public void deniedGithubMetadataIsNotIncludedInFileContext()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("runtime-private").toFile());
        String id=hub.create("account","Alpha").getString("id");
        String answer=ProjectRuntime.analyze(prompt->{
            assertFalse(prompt.contains("owner/private-secret-repo"));
            return "{\"answer\":\"ok\"}";
        },null,hub,"account","owner/private-secret-repo","secretBranch",id,
            "Какие файлы?","",false,true);
        assertEquals("ok",answer);
    }
    @Test(expected=SecurityException.class)
    public void toolLoopRejectsUnconfirmedWrite()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("runtime-deny").toFile());
        String id=hub.create("account","Alpha").getString("id");
        ProjectRuntime.analyze(p->"{\"tools\":[{\"name\":\"write_file\",\"args\":{}}]}",
            null,hub,"account","","main",id,"Редактируй","",false,true);
    }
}
