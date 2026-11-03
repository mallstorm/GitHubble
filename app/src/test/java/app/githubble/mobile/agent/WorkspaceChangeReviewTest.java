package app.githubble.mobile.agent;

import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public final class WorkspaceChangeReviewTest {
    private static AgentFilePlan plan(String path,String body)throws Exception{
        return AgentFilePlan.parse(new JSONObject().put("files",new JSONArray()
            .put(new JSONObject().put("path",path).put("content",body))).toString());
    }
    @Test public void showsCreatesModificationsAndNoops()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("review").toFile());
        String id=hub.create("account","Alpha").getString("id");
        WorkspaceChangeReview fresh=WorkspaceChangeReview.capture(hub,"account",id,
            plan("src/New.java","class New {}"),false);
        assertEquals(1,fresh.created);
        assertTrue(fresh.preview().contains("новый"));
        hub.saveFile("account",id,"src/Existing.java","old");
        WorkspaceChangeReview changed=WorkspaceChangeReview.capture(hub,"account",id,
            plan("src/Existing.java","new"),true);
        assertEquals(1,changed.modified);
        WorkspaceChangeReview same=WorkspaceChangeReview.capture(hub,"account",id,
            plan("src/Existing.java","old"),true);
        assertFalse(same.hasChanges());
    }
    @Test(expected=SecurityException.class)
    public void existingFileNeedsReadConsent()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("review-consent").toFile());
        String id=hub.create("account","Alpha").getString("id");
        hub.saveFile("account",id,"src/Existing.java","old");
        WorkspaceChangeReview.capture(hub,"account",id,
            plan("src/Existing.java","overwrite"),false);
    }
    @Test(expected=IllegalStateException.class)
    public void preventsStaleApproval()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("review-stale").toFile());
        String id=hub.create("account","Alpha").getString("id");
        hub.saveFile("account",id,"src/Main.java","before");
        WorkspaceChangeReview review=WorkspaceChangeReview.capture(hub,"account",id,
            plan("src/Main.java","after"),true);
        hub.saveFile("account",id,"src/Main.java","user edit");
        review.verifyCurrent(hub,"account",id);
    }
    @Test(expected=IllegalStateException.class)
    public void preventsConcurrentCreationOfNewFile()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("review-new").toFile());
        String id=hub.create("account","Alpha").getString("id");
        WorkspaceChangeReview review=WorkspaceChangeReview.capture(hub,"account",id,
            plan("src/Main.java","from model"),false);
        hub.saveFile("account",id,"src/Main.java","someone else");
        review.verifyCurrent(hub,"account",id);
    }
    @Test public void secretsAreDisallowed()throws Exception{
        assertFalse(WorkspaceChangeReview.writablePath(".env"));
        assertFalse(WorkspaceChangeReview.writablePath("src/credentials.json"));
        assertFalse(WorkspaceChangeReview.writablePath("keys/private.pem"));
        assertTrue(WorkspaceChangeReview.writablePath("README.md"));
        assertTrue(WorkspaceChangeReview.writablePath(".gitignore"));
        assertTrue(WorkspaceChangeReview.writablePath("src/app.kt"));
    }
}
