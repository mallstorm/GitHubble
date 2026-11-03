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
        assertTrue(ProjectRuntime.allowed("recent_build_runs",true,false));
        assertTrue(ProjectRuntime.allowed("build_run_diagnostics",true,false));
        assertFalse(ProjectRuntime.allowed("build_run_diagnostics",false,true));
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
    @Test public void modelMayRequestMoreThanThreeToolsWithoutCrashing()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("runtime-batch").toFile());
        String id=hub.create("account","Alpha").getString("id");
        hub.saveFile("account",id,"src/App.java","class App {}");
        AtomicInteger completions=new AtomicInteger();
        String answer=ProjectRuntime.analyze(prompt->{
            if(completions.incrementAndGet()==1){
                org.json.JSONArray tools=new org.json.JSONArray();
                for(int i=0;i<11;i++)
                    tools.put(new org.json.JSONObject().put("name","list_files")
                        .put("args",new org.json.JSONObject()));
                return new org.json.JSONObject().put("tools",tools).toString();
            }
            assertTrue(prompt.contains("\"deferred\":5"));
            assertTrue(prompt.contains("src/App.java"));
            return "{\"answer\":\"Проект просмотрен частично\"}";
        },null,hub,"account","","main",id,"Изучи исходники","",false,true);
        assertEquals("Проект просмотрен частично",answer);
        assertEquals(2,completions.get());
    }

    @Test public void toolBudgetExhaustionReturnsUsefulAnswerNotException()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("runtime-budget").toFile());
        String id=hub.create("account","Alpha").getString("id");
        AtomicInteger calls=new AtomicInteger();
        String reply=ProjectRuntime.analyze(prompt->{
            calls.incrementAndGet();
            if(prompt.contains("TOOL EXECUTION BUDGET EXHAUSTED"))
                return "{\"answer\":\"Проверена только часть файлов\"}";
            return "{\"tools\":[{\"name\":\"list_files\",\"args\":{}}]}";
        },null,hub,"account","","main",id,"Просмотреть весь проект","",false,true);
        assertEquals("Проверена только часть файлов",reply);
        assertTrue(calls.get()>4);
    }

    @Test(expected=SecurityException.class)
    public void unauthorizedToolStillRejectedWithinLargeBatch()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("runtime-safety").toFile());
        String id=hub.create("account","Alpha").getString("id");
        ProjectRuntime.analyze(prompt->{
            org.json.JSONArray tools=new org.json.JSONArray();
            tools.put(new org.json.JSONObject().put("name","list_files"));
            tools.put(new org.json.JSONObject().put("name","write_file"));
            for(int i=0;i<10;i++)tools.put(new org.json.JSONObject().put("name","read_file"));
            return new org.json.JSONObject().put("tools",tools).toString();
        },null,hub,"account","","main",id,"Напиши код","",false,true);
    }

    @Test public void deniedReadErrorsDoNotAbortOtherwiseValidBatch()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("runtime-errors").toFile());
        String id=hub.create("account","Alpha").getString("id");
        AtomicInteger count=new AtomicInteger();
        String answer=ProjectRuntime.analyze(prompt->{
            if(count.incrementAndGet()==1)
                return "{\"tools\":[{\"name\":\"read_file\",\"args\":{\"path\":\"missing.java\"}},"+
                    "{\"name\":\"list_files\",\"args\":{}}]}";
            assertTrue(prompt.contains("\"error\""));
            return "{\"answer\":\"Файл отсутствует\"}";
        },null,hub,"account","","main",id,"Проверь файл","",false,true);
        assertEquals("Файл отсутствует",answer);
    }

}
