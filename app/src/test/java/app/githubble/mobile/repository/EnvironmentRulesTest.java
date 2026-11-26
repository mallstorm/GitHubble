package app.githubble.mobile.repository;

import app.githubble.mobile.repository.EnvironmentRules.Policy;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class EnvironmentRulesTest {
    @Test public void policyIsReadFromTheEnvironment()throws Exception{
        Policy p=EnvironmentRules.parse(new JSONObject("{\"name\":\"prod\",\"protection_rules\":[{\"type\":\"wait_timer\",\"wait_timer\":30},{\"type\":\"required_reviewers\",\"prevent_self_review\":true,\"reviewers\":[{\"type\":\"User\",\"reviewer\":{\"id\":7,\"login\":\"octo\"}},{\"type\":\"Team\",\"reviewer\":{\"id\":9,\"slug\":\"core\"}}]},{\"type\":\"branch_policy\"}],\"deployment_branch_policy\":{\"protected_branches\":false,\"custom_branch_policies\":true}}"));
        assertEquals(30,p.waitMinutes);assertTrue(p.preventSelfReview);assertEquals(2,p.reviewers.size());
        assertEquals("octo",p.reviewers.get(0).label);assertEquals("Team",p.reviewers.get(1).type);assertEquals(EnvironmentRules.CUSTOM,p.branches);
        assertEquals(EnvironmentRules.ALL,EnvironmentRules.parse(new JSONObject("{\"name\":\"x\"}")).branches);
        assertEquals(EnvironmentRules.PROTECTED,EnvironmentRules.parse(new JSONObject("{\"deployment_branch_policy\":{\"protected_branches\":true,\"custom_branch_policies\":false}}")).branches);
    }
    @Test public void requestBodyMatchesTheApi()throws Exception{
        Policy p=new Policy();p.waitMinutes=15;p.preventSelfReview=true;p.branches=EnvironmentRules.PROTECTED;
        p.reviewers.add(new EnvironmentRules.Reviewer("User",7,"octo"));
        JSONObject body=EnvironmentRules.body(p);
        assertEquals(15,body.getInt("wait_timer"));assertTrue(body.getBoolean("prevent_self_review"));
        assertEquals(7,body.getJSONArray("reviewers").getJSONObject(0).getLong("id"));
        assertTrue(body.getJSONObject("deployment_branch_policy").getBoolean("protected_branches"));
        p.branches=EnvironmentRules.ALL;
        assertTrue(EnvironmentRules.body(p).isNull("deployment_branch_policy"));
    }
    @Test public void limitsAreEnforced(){
        Policy p=new Policy();p.waitMinutes=43201;
        try{EnvironmentRules.body(p);fail();}catch(Exception expected){assertTrue(expected instanceof IllegalArgumentException);}
        p.waitMinutes=0;for(int i=0;i<7;i++)p.reviewers.add(new EnvironmentRules.Reviewer("User",i,"u"+i));
        try{EnvironmentRules.body(p);fail();}catch(Exception expected){assertTrue(expected instanceof IllegalArgumentException);}
    }
    @Test public void branchRulesAreParsed()throws Exception{
        assertEquals(1,EnvironmentRules.parseBranchRules(new JSONObject("{\"branch_policies\":[{\"id\":3,\"name\":\"release/*\",\"type\":\"branch\"},{\"id\":4}]}")).size());
    }
}
