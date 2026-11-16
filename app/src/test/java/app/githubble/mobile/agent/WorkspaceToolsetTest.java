package app.githubble.mobile.agent;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
import java.nio.file.*;

public class WorkspaceToolsetTest {
    @Test public void paginationAndRangesWorkWithoutReadingWholeRepository()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();for(int i=0;i<5000;i++)source.files.put(String.format("src/File%04d.java",i),"one\ntwo\nthree\n");
        WorkspaceToolset tools=new WorkspaceToolset(source,Stage2TestSupport.changes("task"));assertEquals(100,tools.list(3000,100,"").getJSONArray("files").length());assertEquals(0,source.reads);
        JSONObject range=tools.read("src/File4000.java",2,1);assertEquals("two\n",range.getString("content"));assertTrue(range.getBoolean("truncated"));assertEquals(GitObjectId.blob(source.files.get("src/File4000.java")),range.getString("sha"));
    }
    @Test public void exactReplaceAndPatchAreProposalsAndRejectStaleContext()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();source.files.put("a.java","a\nb\nc\n");WorkspaceToolset tools=new WorkspaceToolset(source,Stage2TestSupport.changes("task"));
        JSONObject set=tools.patch("a.java",GitObjectId.blob(source.files.get("a.java")),"--- a/a.java\n+++ b/a.java\n@@ -2 +2 @@\n-b\n+B\n");
        assertEquals("a\nb\nc\n",tools.snapshot("a.java").text);tools.changes.approve(set.getString("id"),null);tools.changes.apply(set.getString("id"),source);assertEquals("a\nB\nc\n",tools.snapshot("a.java").text);
        try{tools.replace("a.java",GitObjectId.blob("wrong"),"a","A");fail();}catch(ChangeStore.Conflict expected){}
    }
    @Test public void renameAndDeleteAreAtomicOverlayDeltas()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();source.files.put("old","data");source.modes.put("old","100755");WorkspaceToolset tools=new WorkspaceToolset(source,Stage2TestSupport.changes("task"));
        JSONObject set=tools.move("old","new",GitObjectId.blob("data"),false);tools.changes.approve(set.getString("id"),Set.of("new"));tools.changes.apply(set.getString("id"),source);
        assertNull(tools.snapshot("old").text);assertEquals("data",tools.snapshot("new").text);assertEquals(2,tools.changes.delta(source).length());assertEquals("100755",tools.changes.delta(source).getJSONObject(1).getString("mode"));assertEquals("data",source.files.get("old"));
    }
    @Test public void secretsNeverAppearInReadSearchOrMutation()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();source.files.put("notes.txt","api_key = sk-1234567890abcdefghijklmnopqrstuvwx");source.files.put(".env","secret");WorkspaceToolset tools=new WorkspaceToolset(source,Stage2TestSupport.changes("task"));
        assertFalse(tools.read("notes.txt",1,10).toString().contains("sk-123"));assertFalse(tools.search("api",0,"").toString().contains("sk-123"));assertFalse(tools.list(0,100,"").toString().contains(".env"));
        try{tools.write(new JSONArray().put(new JSONObject().put("path","added").put("content",source.files.get("notes.txt"))),"bad");fail();}catch(SecurityException expected){}
    }
    @Test public void lazyIndexReadsOnlyAnExplicitPage()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();for(int i=0;i<2000;i++)source.files.put("src/F"+i+".java","class Name"+i+" {}");WorkspaceToolset tools=new WorkspaceToolset(source,Stage2TestSupport.changes("task"));
        JSONObject result=TaskIndex.query(tools,"find_definition","Name0","src/",0);assertTrue(result.getBoolean("partial"));assertEquals(32,source.reads);assertTrue(result.toString().contains("Name0"));
    }
    @Test public void emptyDirectoriesAndDirectoryRenameDeleteUseTheSameOverlay()throws Exception {
        Stage2TestSupport.Source source=new Stage2TestSupport.Source();WorkspaceToolset tools=new WorkspaceToolset(source,Stage2TestSupport.changes("task"));
        JSONObject mkdir=tools.changes.proposeDirectory(source,"folder");tools.changes.approve(mkdir.getString("id"),null);tools.changes.apply(mkdir.getString("id"),source);assertTrue(tools.changes.directories(source).contains("folder"));
        JSONObject rename=tools.move("folder","renamed",tools.directoryRevision("folder"),true);tools.changes.approve(rename.getString("id"),null);tools.changes.apply(rename.getString("id"),source);
        assertFalse(tools.changes.directories(source).contains("folder"));assertTrue(tools.changes.directories(source).contains("renamed"));
        JSONObject deletion=tools.delete("renamed",tools.directoryRevision("renamed"),true);tools.changes.approve(deletion.getString("id"),null);tools.changes.apply(deletion.getString("id"),source);assertFalse(tools.changes.directories(source).contains("renamed"));assertTrue(source.files.isEmpty());
    }
    @Test public void largeFileSmallEditHasOneSmallHunkAndPreservesFinalNewline()throws Exception {
        String before="unchanged\n".repeat(3000)+"old\n"+"tail\n".repeat(3000),after=before.replace("old\n","new\n");
        List<TextEdits.Hunk> hunks=TextEdits.hunks(before,after);assertEquals(1,hunks.size());assertEquals(1,hunks.get(0).removed);assertEquals(after,TextEdits.select(before,after,Set.of(0)));
        assertEquals("b",UnifiedPatch.apply("a","a","--- a/a\n+++ b/a\n@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+b\n\\ No newline at end of file\n"));
    }
}
