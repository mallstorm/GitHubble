package app.githubble.mobile.agent;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgentControllerTest {
    @Test public void slashCommandsBecomeEngineeringIntents(){
        assertTrue(AgentController.intent("/build").startsWith("Build the linked project"));
        assertTrue(AgentController.intent("/repair 123").contains("123"));
        assertTrue(AgentController.intent("/workspace push").contains("Never publish to main"));
        assertTrue(AgentController.intent("/edit fix parser").contains("fix parser"));
        assertEquals("Обсудим архитектуру",AgentController.intent("Обсудим архитектуру"));
    }
    @Test public void pinnedContextChangesCancelScope(){
        TaskSession s=new TaskSession("a","project","task",()->{throw new java.util.concurrent.CancellationException("account changed");});
        try{s.check();fail();}catch(java.util.concurrent.CancellationException expected){}
        assertEquals("project",s.projectId);
    }
    @Test public void actualControllerPersistsBudgetAndRequiresReviewInsteadOfClaimingCompletion() throws Exception {
        UnifiedAgentStore store=new UnifiedAgentStore(new UnifiedAgentTestSupport.Memory());
        ProjectRegistry projects=new ProjectRegistry(store);TaskRepository tasks=new TaskRepository(store);
        String project=projects.create("a","Demo","","main").getString("projectId");projects.modelAccess("a",project,true);
        String task=tasks.create("a",project,"Inspect", "").getString("taskId");TaskSession session=new TaskSession("a",project,task,()->{});
        ToolBroker broker=new ToolBroker(session,d->true,(d,a)->true,(n,a)->"source",UnifiedAgentTestSupport.journal());broker.register(UnifiedAgentTestSupport.read());
        java.util.concurrent.atomic.AtomicInteger rounds=new java.util.concurrent.atomic.AtomicInteger();
        AgentLoop.Outcome outcome=new AgentController(tasks,projects).executeLoop(session,"Inspect", "",
            new EnvelopeTransport(prompt->rounds.incrementAndGet()==1?"{\"tools\":[{\"name\":\"read_file\",\"args\":{\"path\":\"a.java\"}}]}":"{\"answer\":\"Read source\"}"),broker,n->{});
        assertEquals("Read source",outcome.answer);JSONObject saved=tasks.get("a",task);
        assertEquals("REVIEW",saved.getString("state"));assertEquals(2,saved.getJSONObject("budget").getInt("rounds"));
        assertEquals(1,saved.getJSONObject("budget").getInt("tools"));assertTrue(saved.getJSONArray("loopHistory").toString().contains("source"));
    }
    @Test public void controllerNeverCallsModelWithoutProjectConsent() throws Exception {
        UnifiedAgentStore store=new UnifiedAgentStore(new UnifiedAgentTestSupport.Memory());ProjectRegistry projects=new ProjectRegistry(store);TaskRepository tasks=new TaskRepository(store);
        String project=projects.create("a","Demo","owner/private","main").getString("projectId");String task=tasks.create("a",project,"Inspect", "").getString("taskId");TaskSession session=new TaskSession("a",project,task,()->{});
        ToolBroker broker=new ToolBroker(session,d->true,(d,a)->true,(n,a)->{fail();return "";},UnifiedAgentTestSupport.journal());
        try{new AgentController(tasks,projects).executeLoop(session,"Inspect", "",(prompt,tools,history)->{fail("private context reached model");return null;},broker,n->{});fail();}
        catch(SecurityException expected){}assertEquals(0,tasks.get("a",task).getJSONObject("budget").getInt("rounds"));
    }
}
