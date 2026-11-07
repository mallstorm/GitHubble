package app.githubble.mobile.agent;

import org.junit.Test;
import java.nio.file.Files;
import static org.junit.Assert.*;

public final class WorkspaceRemoteRevisionTest {
    @Test public void persistsImportedOrPublishedHead()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("rev").toFile());
        String id=hub.create("user","Alpha").getString("id");
        String sha="a".repeat(40);
        hub.markRemoteRevision("user",id,"owner/repo","feature/alpha",sha);
        hub.requireRemoteRevision("user",id,"owner/repo","feature/alpha",sha);
        assertEquals(sha,hub.info("user",id).getString("source_sha"));
    }
    @Test(expected=IllegalStateException.class)
    public void rejectsUnknownBase()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("rev-miss").toFile());
        String id=hub.create("user","Alpha").getString("id");
        hub.requireRemoteRevision("user",id,"owner/repo","main","a".repeat(40));
    }
    @Test(expected=IllegalStateException.class)
    public void rejectsStaleHead()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("rev-stale").toFile());
        String id=hub.create("user","Alpha").getString("id");
        hub.markRemoteRevision("user",id,"owner/repo","main","a".repeat(40));
        hub.requireRemoteRevision("user",id,"owner/repo","main","b".repeat(40));
    }
    @Test(expected=IllegalStateException.class)
    public void rejectsOtherRepository()throws Exception{
        WorkspaceHub hub=new WorkspaceHub(Files.createTempDirectory("rev-repo").toFile());
        String id=hub.create("user","Alpha").getString("id");
        hub.markRemoteRevision("user",id,"owner/repo","main","a".repeat(40));
        hub.requireRemoteRevision("user",id,"owner/other","main","a".repeat(40));
    }
}
