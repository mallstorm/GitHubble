package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
import java.nio.file.*;

public class DeltaPublisherTest {
    private JSONObject entry(String path,String before,String after)throws Exception{return new JSONObject().put("path",path).put("beforeSha",GitObjectId.blob(before)).put("after",after==null?JSONObject.NULL:after).put("mode","100644");}
    @Test public void onlyThreeChangedFilesPublishAndThousandsOfOthersSurvive()throws Exception {
        Stage2TestSupport.Git git=new Stage2TestSupport.Git();for(int i=0;i<5000;i++)git.trees.get(git.tree).put("binary-"+i,"f".repeat(40));git.file("a","old");git.file("b","old");
        Stage2TestSupport.Journal journal=new Stage2TestSupport.Journal();JSONArray delta=new JSONArray().put(entry("a","old","new-a")).put(entry("b","old","new-b")).put(entry("c",null,"new-c"));
        JSONObject result=new DeltaPublisher(git,()->{},journal).publish("o/r",git.branch,git.head,delta,"edit");
        assertEquals(3,git.lastEntries.length());assertEquals(3,git.mutations.stream().filter(s->s.endsWith("/git/blobs")).count());assertEquals(5003,git.trees.get(git.commits.get(result.getString("sha"))).size());assertEquals("CONFIRMED",journal.value.getString("state"));
    }
    @Test public void deleteUsesNullShaAndRenamePreservesUntouchedFiles()throws Exception {
        Stage2TestSupport.Git git=new Stage2TestSupport.Git();git.file("old","data");git.file("keep","keep");JSONArray delta=new JSONArray().put(entry("old","data",null)).put(entry("new",null,"data"));
        new DeltaPublisher(git,()->{},new Stage2TestSupport.Journal()).publish("o/r",git.branch,git.head,delta,"rename");assertTrue(git.lastEntries.getJSONObject(0).isNull("sha"));
        Map<String,String> tree=git.trees.get(git.commits.get(git.refs.get(git.branch)));assertFalse(tree.containsKey("old"));assertTrue(tree.containsKey("keep"));assertEquals(GitObjectId.blob("data"),tree.get("new"));
    }
    @Test public void disjointRemoteAdvanceRebasesOntoActualHead()throws Exception {
        Stage2TestSupport.Git git=new Stage2TestSupport.Git();git.file("a","one");git.advance("other","remote");String current=git.refs.get(git.branch);
        JSONObject result=new DeltaPublisher(git,()->{},new Stage2TestSupport.Journal()).publish("o/r",git.branch,git.head,new JSONArray().put(entry("a","one","two")),"edit");
        assertTrue(result.getBoolean("rebased"));assertEquals(current,git.lastCommit.getJSONArray("parents").getString(0));assertTrue(git.trees.get(git.commits.get(git.refs.get(git.branch))).containsKey("other"));
    }
    @Test public void intersectingRemoteChangeStopsBeforeAnyMutation()throws Exception {
        Stage2TestSupport.Git git=new Stage2TestSupport.Git();git.file("a","one");git.advance("a","user");
        try{new DeltaPublisher(git,()->{},new Stage2TestSupport.Journal()).publish("o/r",git.branch,git.head,new JSONArray().put(entry("a","one","two")),"edit");fail();}catch(ChangeStore.Conflict expected){}assertTrue(git.mutations.isEmpty());
    }
    @Test public void unknownRefOutcomeCannotBeRetriedAndCanOnlyBeReadBack()throws Exception {
        Stage2TestSupport.Git git=new Stage2TestSupport.Git();git.timeout=true;Stage2TestSupport.Journal journal=new Stage2TestSupport.Journal();DeltaPublisher publisher=new DeltaPublisher(git,()->{},journal);JSONArray delta=new JSONArray().put(entry("a",null,"new"));
        try{publisher.publish("o/r",git.branch,git.head,delta,"edit");fail();}catch(DeltaPublisher.UnknownOutcome expected){}
        try{publisher.publish("o/r",git.branch,git.head,delta,"changed message");fail();}catch(DeltaPublisher.UnknownOutcome expected){}assertEquals(1,git.patches);
        assertEquals("CONFIRMED",publisher.reconcile().getString("state"));assertEquals(1,git.patches);
    }
    @Test public void protectedMainIsNeverCommitted()throws Exception {
        Stage2TestSupport.Git git=new Stage2TestSupport.Git();try{new DeltaPublisher(git,()->{},new Stage2TestSupport.Journal()).publish("o/r","main",git.head,new JSONArray().put(entry("a",null,"new")),"edit");fail();}catch(SecurityException expected){}assertTrue(git.mutations.isEmpty());
    }
    @Test public void taskBranchCreationIsJournaledAndNeverAdoptsForeignBranch()throws Exception {
        Stage2TestSupport.Git git=new Stage2TestSupport.Git();java.util.concurrent.atomic.AtomicReference<JSONObject> state=new java.util.concurrent.atomic.AtomicReference<>(new JSONObject());
        String branch=TaskBranch.ensure(git,"o/r","main","Fix parser","abcdefgh12345678",state.get(),state::set);assertTrue(branch.startsWith("hubble/"));assertEquals("CONFIRMED",state.get().getString("state"));
        int writes=git.mutations.size();assertEquals(branch,TaskBranch.ensure(git,"o/r","main","Fix parser","abcdefgh12345678",state.get(),state::set));assertEquals(writes,git.mutations.size());
    }
}
