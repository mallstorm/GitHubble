package app.githubble.mobile.agent;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProjectRegistryMigrationTest {
    private JSONObject binding(String workspace,String repo) throws Exception {return new JSONObject().put("account_id","alice").put("name","Demo").put("workspace_id",workspace).put("repository",repo).put("branch","main");}
    @Test public void repeatedMigrationUnifiesWorkspaceAndPreservesEveryTask() throws Exception {
        UnifiedAgentTestSupport.Memory memory=new UnifiedAgentTestSupport.Memory();UnifiedAgentStore store=new UnifiedAgentStore(memory);
        String prefix=UnifiedAgentStore.key("alice")+"_";java.util.Map<String,Object> old=new java.util.HashMap<>();
        old.put(prefix+"project_chat00001",binding("workspace-one","a/repo").toString());
        old.put(prefix+"project_chat00002",binding("workspace-one","a/repo").toString());
        old.put(prefix+"thread_chat00001","[{\"body\":\"do not lose\"}]");
        ProjectRegistry projects=new ProjectRegistry(store);store.backup("alice",old);projects.migrate("alice",old);projects.migrate("alice",old);
        assertEquals(1,projects.list("alice").length());assertEquals(2,new TaskRepository(store).list("alice",null).length());
        assertFalse(projects.list("alice").getJSONObject(0).getBoolean("modelAccess"));
        assertTrue(memory.data.get(UnifiedAgentStore.key("alice")+"_legacy_backup").contains("do not lose"));
        assertEquals(0,projects.list("bob").length());
    }
    @Test public void ambiguousRepositoryDoesNotMergeDistinctWorkspaces() throws Exception {
        UnifiedAgentStore store=new UnifiedAgentStore(new UnifiedAgentTestSupport.Memory());ProjectRegistry p=new ProjectRegistry(store);
        String prefix=UnifiedAgentStore.key("alice")+"_";java.util.Map<String,Object> old=new java.util.HashMap<>();
        old.put(prefix+"project_chat00001",binding("one","a/repo").toString());
        old.put(prefix+"project_chat00002",binding("two","a/repo").toString());
        old.put(prefix+"project_chat00003",binding("","a/repo").toString());p.migrate("alice",old);
        assertEquals(3,p.list("alice").length());
    }
    @Test public void unambiguousRepositoryAndWorkspaceMatchRegardlessOfThreadOrdering() throws Exception {
        UnifiedAgentStore store=new UnifiedAgentStore(new UnifiedAgentTestSupport.Memory());ProjectRegistry p=new ProjectRegistry(store);
        String prefix=UnifiedAgentStore.key("alice")+"_";java.util.Map<String,Object> old=new java.util.HashMap<>();
        old.put(prefix+"project_chat00001",binding("","a/repo").toString());
        old.put(prefix+"project_chat00002",binding("one","a/repo").toString());p.migrate("alice",old);
        assertEquals(1,p.list("alice").length());assertEquals("one",p.list("alice").getJSONObject(0).getString("workspaceId"));
    }
    @Test public void metadataBackupCannotBeOverwritten() {
        UnifiedAgentTestSupport.Memory memory=new UnifiedAgentTestSupport.Memory();UnifiedAgentStore s=new UnifiedAgentStore(memory);
        String key=UnifiedAgentStore.key("alice")+"_project";s.backup("alice",java.util.Map.of(key,"original"));s.backup("alice",java.util.Map.of(key,"changed"));
        assertTrue(memory.data.get(UnifiedAgentStore.key("alice")+"_legacy_backup").contains("original"));
    }
    @Test(expected=IllegalStateException.class) public void corruptStateFailsInsteadOfSilentlyLosingTasks(){
        UnifiedAgentTestSupport.Memory m=new UnifiedAgentTestSupport.Memory();m.data.put(UnifiedAgentStore.key("alice"),"broken");new ProjectRegistry(new UnifiedAgentStore(m)).list("alice");
    }
}
