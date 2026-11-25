package app.githubble.mobile.agent;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AgentQuotaTest {
    @Test public void standardPresetIsTheDefaultWithoutInit(){
        assertEquals(12,AgentQuota.rounds());
        assertEquals(100,AgentQuota.tools());
        assertEquals(180_000L,AgentQuota.tokens());
        assertEquals(0,AgentQuota.currentPreset());
    }
    @Test public void valuesAreClampedAndDescribed(){
        try{
            AgentQuota.set(0,-5,1);
            assertEquals(1,AgentQuota.rounds());assertEquals(1,AgentQuota.tools());assertEquals(20_000L,AgentQuota.tokens());
            AgentQuota.set(40,300,600_000L);
            assertEquals(1,AgentQuota.currentPreset());
            assertEquals("40 раундов · 300 инструментов · 600k токенов",AgentQuota.describe());
            AgentQuota.set(7,70,1_500_000L);
            assertEquals(-1,AgentQuota.currentPreset());
            assertEquals("1.5 млн",AgentQuota.format(1_500_000L));
        }finally{AgentQuota.set(12,100,180_000L);}
    }
    @Test public void budgetUsesTheQuotaInsteadOfFixedWindows()throws Exception{
        try{
            AgentQuota.set(40,300,600_000L);
            AgentBudget budget=new AgentBudget(new org.json.JSONObject().put("rounds",30).put("roundWindowStart",0).put("maxRounds",40),v->{});
            budget.round(100); // the old fixed 25-round window would have stopped here
            assertEquals(31,budget.rounds());
        }finally{AgentQuota.set(12,100,180_000L);}
    }
}
