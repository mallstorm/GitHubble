package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import app.githubble.mobile.agent.GatewayPolicy.Risk;
import org.junit.Test;

public class GatewayPolicyTest {
    private static Risk r(String m,String p){return GatewayPolicy.classify(m,p,null).risk;}

    @Test public void readsAreFreeAndHostIsPinned(){
        assertEquals(Risk.READ,r("GET","/repos/o/r/actions/caches"));
        assertEquals(Risk.READ,r("get","https://api.github.com/users/me/settings/billing/usage"));
        assertEquals("/repos/o/r?x=1",GatewayPolicy.classify("GET","https://api.github.com/repos/o/r?x=1").path);
        assertEquals(Risk.BLOCKED,r("GET","https://evil.example/repos/o/r"));
        assertEquals(Risk.BLOCKED,r("GET","repos/o/r"));
    }
    @Test public void traversalAndSmugglingAreBlocked(){
        for(String p:new String[]{"/repos/o/../x","/repos/o/%2e%2e/x","/repos//o","/repos/o/r#x","/repos/o/r x","/repos/o\\r","/repos/o%2fr/x","/repos/o/r\n/x"})
            assertEquals(p,Risk.BLOCKED,r("GET",p));
    }
    @Test public void onlyAllowListedRootsAndMethods(){
        assertEquals(Risk.BLOCKED,r("GET","/app/installations"));
        assertEquals(Risk.BLOCKED,r("POST","/applications/x/token"));
        assertEquals(Risk.BLOCKED,r("POST","/login/oauth/access_token"));
        assertEquals(Risk.BLOCKED,r("TRACE","/repos/o/r"));
        assertEquals(Risk.BLOCKED,r("POST","/repos/o/r/actions/runners/registration-token"));
    }
    @Test public void reversibleWritesFollowPermissionLevel(){
        assertEquals(Risk.REVERSIBLE,r("POST","/repos/o/r/issues"));
        assertEquals(Risk.REVERSIBLE,r("POST","/repos/o/r/actions/runs/5/cancel"));
        assertEquals(Risk.REVERSIBLE,r("POST","/repos/o/r/actions/runs/5/rerun"));
        assertEquals(Risk.REVERSIBLE,r("PATCH","/repos/o/r"));
    }
    @Test public void deletionsAlwaysConfirm(){
        assertEquals(Risk.DELETE,r("DELETE","/repos/o/r/actions/caches/12"));
        assertEquals(Risk.DELETE,r("DELETE","/repos/o/r/actions/artifacts/12"));
        assertEquals(Risk.DELETE,r("DELETE","/repos/o/r/actions/runs/12"));
        assertEquals(Risk.DELETE,r("DELETE","/repos/o/r/git/refs/heads/feature"));
        assertEquals(Risk.DELETE,r("DELETE","/repos/o/r/releases/9"));
    }
    @Test public void irreversibleNeedsTypedName(){
        GatewayPolicy.Decision del=GatewayPolicy.classify("DELETE","/repos/Owner/Repo");
        assertEquals(Risk.IRREVERSIBLE,del.risk);assertEquals("Owner/Repo",del.confirm);
        assertEquals(Risk.IRREVERSIBLE,r("POST","/repos/o/r/transfer"));
        assertEquals(Risk.IRREVERSIBLE,GatewayPolicy.classify("PATCH","/repos/o/r","{\"private\":false}").risk);
        assertEquals(Risk.IRREVERSIBLE,GatewayPolicy.classify("PATCH","/repos/o/r","{ \"archived\" : true }").risk);
        assertEquals(Risk.REVERSIBLE,GatewayPolicy.classify("PATCH","/repos/o/r","{\"description\":\"x\"}").risk);
        assertEquals(Risk.IRREVERSIBLE,GatewayPolicy.classify("PATCH","/repos/o/r/git/refs/heads/main","{\"sha\":\"a\",\"force\":true}").risk);
        assertEquals(Risk.REVERSIBLE,GatewayPolicy.classify("PATCH","/repos/o/r/git/refs/heads/dev","{\"sha\":\"a\"}").risk);
        assertEquals(Risk.IRREVERSIBLE,r("DELETE","/repos/o/r/git/refs/heads/main"));
        assertEquals(Risk.IRREVERSIBLE,r("PUT","/repos/o/r/collaborators/bob"));
        assertEquals(Risk.IRREVERSIBLE,r("POST","/repos/o/r/hooks"));
        assertEquals(Risk.IRREVERSIBLE,r("PUT","/repos/o/r/branches/main/protection"));
        assertEquals(Risk.IRREVERSIBLE,r("PUT","/repos/o/r/actions/permissions"));
        assertEquals(Risk.IRREVERSIBLE,r("DELETE","/repos/o/r/actions/secrets/NAME"));
        assertEquals(Risk.IRREVERSIBLE,r("PUT","/user/installations/1/repositories/2"));
    }
    @Test public void secretsKeysAndBillingWritesAreBlocked(){
        assertEquals(Risk.BLOCKED,r("PUT","/repos/o/r/actions/secrets/NAME"));
        assertEquals(Risk.BLOCKED,r("POST","/user/keys"));
        assertEquals(Risk.BLOCKED,r("POST","/user/gpg_keys"));
        assertEquals(Risk.BLOCKED,r("PATCH","/users/me/settings/billing/budgets"));
    }
    @Test public void rawFileAndLogReadsGoThroughDedicatedTools(){
        assertEquals(Risk.BLOCKED,r("GET","/repos/o/r/contents/.env"));
        assertEquals(Risk.BLOCKED,r("GET","/repos/o/r/actions/runs/1/logs"));
        assertEquals(Risk.BLOCKED,r("GET","/repos/o/r/actions/artifacts/1/zip"));
        assertEquals(Risk.BLOCKED,r("GET","/repos/o/r/git/blobs/abc"));
    }
    @Test public void typedConfirmationIsExactButCaseInsensitive(){
        assertTrue(GatewayPolicy.confirmationMatches("Owner/Repo"," owner/repo "));
        assertFalse(GatewayPolicy.confirmationMatches("Owner/Repo","owner/repo2"));
        assertFalse(GatewayPolicy.confirmationMatches("","")); assertFalse(GatewayPolicy.confirmationMatches("a",null));
    }
}
