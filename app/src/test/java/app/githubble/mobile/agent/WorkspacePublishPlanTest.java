package app.githubble.mobile.agent;

import org.junit.Test;
import java.nio.file.Files;
import static org.junit.Assert.*;

public final class WorkspacePublishPlanTest {
    @Test public void publishesMoreThanTwelveFilesAsOnePlan()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("publish").toFile());
        String id=hub.create("acct","Alpha").getString("id");
        for(int i=0;i<19;i++)hub.saveFile("acct",id,"src/File"+i+".java","class File"+i+" {}");
        WorkspacePublishPlan snap=WorkspacePublishPlan.capture(hub,"acct",id);
        assertTrue(snap.fileCount()>12);
        assertTrue(snap.preview("owner/repo","main").contains("исходников")||
            snap.preview("owner/repo","main").contains("Исходников"));
        assertEquals(snap.fileCount(),snap.treeEntries().length());
    }
    @Test(expected=IllegalStateException.class)
    public void preventsPublishingStaleLocalFile()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("publish-stale").toFile());
        String id=hub.create("acct","Alpha").getString("id");
        hub.saveFile("acct",id,"src/App.kt","class App");
        WorkspacePublishPlan snap=WorkspacePublishPlan.capture(hub,"acct",id);
        hub.saveFile("acct",id,"src/App.kt","class App2");
        snap.verifyCurrent(hub,"acct");
    }
    @Test(expected=IllegalStateException.class)
    public void detectsNewFilesAfterApproval()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("publish-added").toFile());
        String id=hub.create("acct","Alpha").getString("id");
        WorkspacePublishPlan snap=WorkspacePublishPlan.capture(hub,"acct",id);
        hub.saveFile("acct",id,"src/New.java","class New {}");
        snap.verifyCurrent(hub,"acct");
    }
    @Test public void excludesLikelySecretsAndLocalFiles()throws Exception{
        assertFalse(WorkspacePublishPlan.safePath(".env"));
        assertFalse(WorkspacePublishPlan.safePath("local.properties"));
        assertFalse(WorkspacePublishPlan.safePath("gradle.properties"));
        assertFalse(WorkspacePublishPlan.safePath("docs/CONTEXT.md"));
        assertFalse(WorkspacePublishPlan.safePath("config/credentials.json"));
        assertTrue(WorkspacePublishPlan.safePath(".gitignore"));
        assertTrue(WorkspacePublishPlan.safePath("src/Main.java"));
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("publish-filter").toFile());
        String id=hub.create("acct","Alpha").getString("id");
        hub.saveFile("acct",id,".env","SECRET=123");
        hub.saveFile("acct",id,"src/Main.java","class Main {}");
        WorkspacePublishPlan snap=WorkspacePublishPlan.capture(hub,"acct",id);
        assertTrue(snap.skipped>=1);
        assertFalse(snap.treeEntries().toString().contains("SECRET=123"));
    }
}
