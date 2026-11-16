package app.githubble.mobile.agent;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OverviewPermissionTest {
    @Test public void consentDefaultsOffAndIsScopedToOneProject() throws Exception {
        UnifiedAgentStore s=new UnifiedAgentStore(new UnifiedAgentTestSupport.Memory());ProjectRegistry p=new ProjectRegistry(s);
        String one=p.create("a","one","owner/private","main").getString("projectId"),two=p.create("a","two","","main").getString("projectId");
        assertFalse(p.get("a",one).getBoolean("modelAccess"));p.modelAccess("a",one,true);
        assertTrue(p.get("a",one).getBoolean("modelAccess"));assertFalse(p.get("a",two).getBoolean("modelAccess"));p.modelAccess("a",one,false);assertFalse(p.get("a",one).getBoolean("modelAccess"));
    }
    @Test public void attachingDifferentRepositoryRevokesExistingConsent() throws Exception {
        UnifiedAgentStore s=new UnifiedAgentStore(new UnifiedAgentTestSupport.Memory());ProjectRegistry p=new ProjectRegistry(s);TaskRepository t=new TaskRepository(s);
        String id=p.create("a","demo","owner/one","main").getString("projectId");String task=t.create("a",id,"work","").getString("taskId");p.modelAccess("a",id,true);
        p.bindTask("a",task,"demo","owner/two","main","");assertFalse(p.get("a",id).getBoolean("modelAccess"));
    }
}
