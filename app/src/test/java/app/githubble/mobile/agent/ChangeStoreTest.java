package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
import java.nio.file.*;

public class ChangeStoreTest {
    @Test public void approvalAndApplyStayInOneTaskAndNeverWriteBase()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();source.files.put("a.java","old\n");java.io.File root=Files.createTempDirectory("tasks").toFile();
        ChangeStore a=new ChangeStore(root,"account","project","task-a"),b=new ChangeStore(root,"account","project","task-b");
        JSONObject set=a.propose(source,"edit",new JSONArray().put(Stage2TestSupport.edit("a.java","old\n","new\n")));
        assertEquals("old\n",a.snapshot(source,"a.java").text);a.approve(set.getString("id"),null);a.apply(set.getString("id"),source);
        assertEquals("new\n",a.snapshot(source,"a.java").text);assertEquals("old\n",b.snapshot(source,"a.java").text);assertEquals("old\n",source.files.get("a.java"));
        a=new ChangeStore(root,"account","project","task-a");assertEquals("new\n",a.snapshot(source,"a.java").text);a.rollback(set.getString("id"));assertEquals("old\n",a.snapshot(source,"a.java").text);
    }
    @Test public void staleBaseBlocksApply()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();source.files.put("file","one");ChangeStore store=Stage2TestSupport.changes("task");
        JSONObject set=store.propose(source,"edit",new JSONArray().put(Stage2TestSupport.edit("file","one","two")));store.approve(set.getString("id"),null);source.files.put("file","user edit");
        try{store.apply(set.getString("id"),source);fail();}catch(ChangeStore.Conflict expected){}assertEquals("user edit",source.files.get("file"));assertEquals("CONFLICT",store.get(set.getString("id")).getString("state"));
    }
    @Test public void perFileApprovalNeverPublishesOtherPendingFiles()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();ChangeStore store=Stage2TestSupport.changes("task");
        JSONObject set=store.propose(source,"two",new JSONArray().put(Stage2TestSupport.edit("a",null,"a")).put(Stage2TestSupport.edit("b",null,"b")));
        store.approve(set.getString("id"),Set.of("a"));store.apply(set.getString("id"),source);assertEquals(1,store.delta(source).length());assertNull(store.snapshot(source,"b").text);
    }
    @Test public void selectedHunkAppliesOnlyItsLines()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();source.files.put("a","one\nkeep\nthree\n");ChangeStore store=Stage2TestSupport.changes("task");
        JSONObject set=store.propose(source,"edit",new JSONArray().put(Stage2TestSupport.edit("a",source.files.get("a"),"ONE\nkeep\nTHREE\n")));
        store.approveHunks(set.getString("id"),"a",Set.of(0),source);assertEquals("ONE\nkeep\nthree\n",store.snapshot(source,"a").text);
    }
    @Test public void checkpointAndPublicationFreezePreventRacingChanges()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();ChangeStore store=Stage2TestSupport.changes("task");JSONObject set=store.propose(source,"add",new JSONArray().put(Stage2TestSupport.edit("a",null,"one")));
        store.approve(set.getString("id"),null);store.apply(set.getString("id"),source);assertFalse(store.get(set.getString("id")).getString("checkpoint").isEmpty());store.freeze(source,store.fingerprint(source));
        try{store.rollback(set.getString("id"));fail();}catch(ChangeStore.Conflict expected){}
        store.published("1".repeat(40));assertEquals("PUBLISHED",store.get(set.getString("id")).getString("state"));
    }
    @Test public void rejectingOneRenameSideRejectsItsWholeGroup()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();source.files.put("a","data");WorkspaceToolset tools=new WorkspaceToolset(source,Stage2TestSupport.changes("task"));
        JSONObject set=tools.move("a","b",GitObjectId.blob("data"),false);tools.changes.reject(set.getString("id"),Set.of("b"));JSONArray entries=tools.changes.get(set.getString("id")).getJSONArray("entries");
        assertEquals("REJECTED",entries.getJSONObject(0).getString("state"));assertEquals("REJECTED",entries.getJSONObject(1).getString("state"));
    }
    @Test public void oldPublicationEvidenceCannotMarkLaterEditsPublished()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();source.files.put("a","one");ChangeStore store=Stage2TestSupport.changes("task");
        JSONObject first=store.propose(source,"first",new JSONArray().put(Stage2TestSupport.edit("a","one","two")));store.approve(first.getString("id"),null);store.apply(first.getString("id"),source);
        String firstFingerprint=store.fingerprint(source);store.freeze(source,firstFingerprint);store.published("1".repeat(40),firstFingerprint);source.files.put("a","two");
        JSONObject later=store.propose(source,"later",new JSONArray().put(Stage2TestSupport.edit("a","two","three")));store.approve(later.getString("id"),null);store.apply(later.getString("id"),source);
        try{store.published("1".repeat(40),firstFingerprint);fail();}catch(ChangeStore.Conflict expected){}
        assertEquals("three",store.snapshot(source,"a").text);assertEquals("LOCAL_APPLIED",store.get(later.getString("id")).getString("state"));
        store.freeze(source,store.fingerprint(source));try{store.published("1".repeat(40),firstFingerprint);fail();}catch(ChangeStore.Conflict expected){}
    }
}
