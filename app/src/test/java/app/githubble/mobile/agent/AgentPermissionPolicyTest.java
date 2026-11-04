package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
public final class AgentPermissionPolicyTest {
    @Test public void planningOverridesEveryAuthority(){
        for(int level=0;level<4;level++)assertFalse(AgentPermissionPolicy.mayWrite(level,true));
    }
    @Test public void permissionsSeparateLocalRemoteAndSensitiveActions(){
        assertFalse(AgentPermissionPolicy.mayWrite(0,false));
        assertTrue(AgentPermissionPolicy.needsReview(1,false,false));
        assertFalse(AgentPermissionPolicy.needsReview(2,false,false));
        assertTrue(AgentPermissionPolicy.needsReview(2,true,false));
        assertFalse(AgentPermissionPolicy.needsReview(3,true,false));
        assertTrue(AgentPermissionPolicy.needsReview(3,true,true));
        assertEquals(1,AgentPermissionPolicy.normalize(99));
    }
}
