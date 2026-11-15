package app.githubble.mobile.agent;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class GitHubAccountSummaryTest {
    @Test public void omittedPrivateCountIsUnknownInsteadOfZero()throws Exception {
        JSONObject result=GitHubAgentBridge.accountSummary(new JSONObject().put("login","mallstorm").put("id",142712475).put("public_repos",4));
        assertTrue(result.has("private_repos"));assertTrue(result.isNull("private_repos"));
        assertEquals("UNKNOWN",result.getString("private_repos_status"));assertEquals(4,result.getInt("public_repos"));
        assertTrue(result.getString("repository_count_note").contains("list_repositories"));
    }
    @Test public void genuineProfileZeroRemainsZeroButDoesNotClaimInventory()throws Exception {
        JSONObject result=GitHubAgentBridge.accountSummary(new JSONObject().put("total_private_repos",0));
        assertEquals(0,result.getInt("private_repos"));assertEquals("REPORTED_BY_PROFILE",result.getString("private_repos_status"));
        assertEquals("account_profile_not_accessible_repository_inventory",result.getString("counts_scope"));
    }
    @Test public void reportedPrivateCountIsPreserved()throws Exception {
        assertEquals(22,GitHubAgentBridge.accountSummary(new JSONObject().put("total_private_repos",22)).getInt("private_repos"));
    }
    @Test public void nullAndInvalidCountsNeverBecomeZero()throws Exception {
        for(Object value:new Object[]{JSONObject.NULL,-1,"unknown"}){
            JSONObject result=GitHubAgentBridge.accountSummary(new JSONObject().put("total_private_repos",value));
            assertTrue(result.isNull("private_repos"));assertEquals("UNKNOWN",result.getString("private_repos_status"));
        }
    }
}
