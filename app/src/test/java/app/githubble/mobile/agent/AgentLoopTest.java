package app.githubble.mobile.agent;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgentLoopTest {
    @Test public void repeatedModelRequestsStopAtPersistedRoundBudget() throws Exception {
        java.util.concurrent.atomic.AtomicInteger modelCalls=new java.util.concurrent.atomic.AtomicInteger(),tools=new java.util.concurrent.atomic.AtomicInteger();
        TaskSession s=new TaskSession("a","p","t",()->{});ToolBroker b=new ToolBroker(s,d->true,(d,a)->true,(n,a)->{tools.incrementAndGet();return "source";},UnifiedAgentTestSupport.journal());b.register(UnifiedAgentTestSupport.read());
        AgentLoop.Outcome out=new AgentLoop().run(new EnvelopeTransport(p->{modelCalls.incrementAndGet();return "{\"tools\":[{\"name\":\"read_file\",\"args\":{\"path\":\"a\"}}]}";}),b,s,UnifiedAgentTestSupport.budget(2),"inspect","",new JSONArray(),h->{},i->{});
        assertTrue(out.budgetExhausted);assertEquals(2,modelCalls.get());assertEquals(2,tools.get());
    }
    @Test public void oversizedBatchExecutesNothingAndExplainsItToModel() throws Exception {
        TaskSession s=new TaskSession("a","p","t",()->{});java.util.concurrent.atomic.AtomicInteger round=new java.util.concurrent.atomic.AtomicInteger();
        ToolBroker b=new ToolBroker(s,d->true,(d,a)->true,(n,a)->{fail();return "";},UnifiedAgentTestSupport.journal());b.register(UnifiedAgentTestSupport.read());
        ModelTransport model=(prompt,schema,history)->{
            if(round.incrementAndGet()==2){assertTrue(history.toString().contains("NOT executed"));return new ModelTransport.Turn("understood",null,null,0,0);}
            JSONArray calls=new JSONArray();for(int i=0;i<9;i++)calls.put(new JSONObject().put("name","read_file").put("args",new JSONObject().put("path","a")));return new ModelTransport.Turn("",calls,null,0,0);
        };
        assertEquals("understood",new AgentLoop().run(model,b,s,UnifiedAgentTestSupport.budget(3),"inspect","",new JSONArray(),h->{},i->{}).answer);
    }
    @Test public void budgetReservationSurvivesRecreation() throws Exception {
        java.util.concurrent.atomic.AtomicReference<JSONObject> saved=new java.util.concurrent.atomic.AtomicReference<>(new JSONObject().put("maxRounds",1));
        new AgentBudget(saved.get(),saved::set).round(100);
        try{new AgentBudget(saved.get(),saved::set).round(100);fail();}catch(AgentBudget.Exhausted expected){}
        assertEquals(1,saved.get().getInt("rounds"));
    }
}
