package app.githubble.mobile.agent;

import java.nio.file.Files;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

public final class AgentBuildRepairTicketTest {
    private static final String SHA="a".repeat(40);
    private static JSONObject failed()throws Exception{
        return new JSONObject().put("run_id",420L)
            .put("repository","owner/repo").put("branch","main").put("sha",SHA)
            .put("status","completed").put("conclusion","failure")
            .put("failures",new JSONArray().put(new JSONObject().put("job","compile")))
            .put("error_excerpt","src/App.java:17: error: missing symbol");
    }
    @Test public void bindsFailedBuildToHeadAndSelectsMatchingSource()throws Exception{
        AgentBuildRepairTicket ticket=AgentBuildRepairTicket.fromReport(failed(),"owner/repo","main",SHA);
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("repair-source").toFile());
        String id=hub.create("user","Alpha").getString("id");
        hub.saveFile("user",id,"src/App.java","class App {}");
        hub.saveFile("user",id,"src/Unrelated.java","class Unrelated {}");
        String sources=ticket.localSourceContext(hub,"user",id);
        assertTrue(sources.contains("SOURCE FILE: src/App.java"));
        assertFalse(sources.contains("Unrelated.java"));
        assertTrue(ticket.repairTask().contains("RUN 420"));
    }
    @Test(expected=IllegalStateException.class)
    public void blocksOldHead()throws Exception{
        AgentBuildRepairTicket.fromReport(failed(),"owner/repo","main","b".repeat(40));
    }
    @Test(expected=IllegalStateException.class)
    public void blocksOtherBranch()throws Exception{
        AgentBuildRepairTicket.fromReport(failed(),"owner/repo","develop",SHA);
    }
    @Test(expected=IllegalStateException.class)
    public void blocksSuccessfulRun()throws Exception{
        AgentBuildRepairTicket.fromReport(failed().put("conclusion","success"),
            "owner/repo","main",SHA);
    }
    @Test(expected=IllegalStateException.class)
    public void cannotGuessFixForMissingSource()throws Exception{
        AgentBuildRepairTicket ticket=AgentBuildRepairTicket.fromReport(failed(),"owner/repo","main",SHA);
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("repair-no-source").toFile());
        String id=hub.create("user","Alpha").getString("id");
        ticket.localSourceContext(hub,"user",id);
    }
    @Test public void redactsSecretsInDiagnostic()throws Exception{
        JSONObject report=failed().put("error_excerpt",
            "ghp_abcdefghijklmnopqrstuvwx error: compilation failed");
        String preview=AgentBuildRepairTicket.fromReport(report,"owner/repo","main",SHA).preview();
        assertFalse(preview.contains("ghp_abcdefghijklmnopqrstuvwx"));
        assertTrue(preview.contains("[REDACTED]"));
    }
}
