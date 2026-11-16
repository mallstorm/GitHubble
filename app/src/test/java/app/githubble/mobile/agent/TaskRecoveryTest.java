package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
public class TaskRecoveryTest {
    @Test public void recoveryKeepsUnknownDispatchAndExplicitGrantPreservesItsLedger()throws Exception {
        UnifiedAgentStore s=new UnifiedAgentStore(new UnifiedAgentTestSupport.Memory());TaskRepository tasks=new TaskRepository(s);
        String id=tasks.create("a","","task","build").getString("taskId");
        tasks.update("a",id,t->{AgentController.put(t,"state","BUILDING");AgentController.put(t,"ci",object("{state:'DISPATCH_PENDING',runId:0,workflowId:'7',sha:'abc'}"));});
        tasks.recoverInterrupted("a","");assertEquals("INTERRUPTED",tasks.get("a",id).getString("state"));
        tasks.authorizeContinuation("a",id);assertEquals("UNKNOWN",tasks.get("a",id).getJSONObject("ci").getString("state"));
    }
    @Test public void confirmedContinuationExtendsBudgetWithoutLosingUsage()throws Exception {
        UnifiedAgentStore s=new UnifiedAgentStore(new UnifiedAgentTestSupport.Memory());TaskRepository tasks=new TaskRepository(s);
        String id=tasks.create("a","","task","build").getString("taskId");tasks.update("a",id,t->{JSONObject b=t.optJSONObject("budget");AgentController.put(b,"rounds",25);AgentController.put(b,"tools",100);});
        tasks.authorizeContinuation("a",id);JSONObject b=tasks.get("a",id).getJSONObject("budget");
        AgentBudget budget=new AgentBudget(b,v->{});budget.round(100);budget.tool();assertEquals(26,budget.rounds());assertEquals(25,b.getInt("rounds"));
    }
    private static JSONObject object(String s){try{return new JSONObject(s);}catch(Exception bad){throw new AssertionError(bad);}}
}
