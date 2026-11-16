package app.githubble.mobile.agent;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskRepositoryTest {
    @Test public void taskDeletionNeverDeletesProjectAndTasksHaveIndependentBudgets() throws Exception {
        UnifiedAgentStore store=new UnifiedAgentStore(new UnifiedAgentTestSupport.Memory());ProjectRegistry p=new ProjectRegistry(store);TaskRepository t=new TaskRepository(store);
        String project=p.create("a","Demo","","main").getString("projectId");
        JSONObject one=t.create("a",project,"one","fix"),two=t.create("a",project,"two","test");
        t.update("a",one.getString("taskId"),v->AgentController.put(v,"state","RUNNING"));
        assertEquals("READY",t.get("a",two.getString("taskId")).getString("state"));
        t.delete("a",one.getString("taskId"));assertEquals(1,p.list("a").length());assertEquals(1,t.list("a",project).length());
        assertEquals("",p.get("a",project).getString("workspaceId"));
    }
    @Test public void dataSurvivesRepositoryRecreationAndNeverCrossesAccounts() throws Exception {
        UnifiedAgentTestSupport.Memory memory=new UnifiedAgentTestSupport.Memory();UnifiedAgentStore store=new UnifiedAgentStore(memory);
        String p=new ProjectRegistry(store).create("a","Demo","","main").getString("projectId");String id=new TaskRepository(store).create("a",p,"goal","saved").getString("taskId");
        assertEquals("saved",new TaskRepository(new UnifiedAgentStore(memory)).get("a",id).getString("goal"));
        try{new TaskRepository(store).get("b",id);fail();}catch(IllegalArgumentException expected){}
    }
    @Test(expected=SecurityException.class) public void identityCannotBeChangedFromUpdateCallback() throws Exception {
        UnifiedAgentStore s=new UnifiedAgentStore(new UnifiedAgentTestSupport.Memory());TaskRepository t=new TaskRepository(s);
        String id=t.create("a","","task","goal").getString("taskId");t.update("a",id,v->AgentController.put(v,"accountKey","b"));
    }
}
