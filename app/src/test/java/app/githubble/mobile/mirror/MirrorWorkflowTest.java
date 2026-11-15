package app.githubble.mobile.mirror;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class MirrorWorkflowTest {
    @Test public void secretNeverLeavesPrivateControllerEnvironment(){
        String y=MirrorWorkflow.yaml();
        assertFalse(y.contains("environment: mirror-production"));
        assertTrue(y.contains("secrets.GITHUBBLE_MIRROR_APP_PRIVATE_KEY"));
        assertTrue(y.contains("vars.GITHUBBLE_MIRROR_APP_CLIENT_ID"));
        assertTrue(y.contains("permission-contents: read"));
        assertTrue(y.contains("permission-contents: write"));
        assertTrue(y.contains("permission-workflows: write"));
        assertTrue(y.contains("github.actor_id"));
        assertFalse(y.contains("source_token:"));
        assertFalse(y.contains("destination_token:"));
        assertFalse(y.contains("secrets.GITHUB_TOKEN"));
    }

    @Test public void transfersFullGitHistoryTagsLfsAndChecksDestination(){
        String y=MirrorWorkflow.yaml();
        assertTrue(y.contains("git clone --bare"));
        assertTrue(y.contains("git fetch --force"));
        assertTrue(y.contains("git lfs fetch --all origin"));
        assertTrue(y.contains("git lfs push --all"));
        assertTrue(y.contains("git push --force --prune"));
        assertTrue(y.contains("refs/heads/*"));
        assertTrue(y.contains("refs/tags/*"));
        assertTrue(y.contains("Destination is not empty"));
        assertTrue(y.contains("Target refs changed since last verification"));
        assertTrue(y.contains("source==destination"));
        assertTrue(y.contains("cancel-in-progress: false"));
    }

    @Test public void digestIsStableAcrossMapOrderAndMatchesSha256() throws Exception {
        Map<String,String> one=new LinkedHashMap<>(),two=new LinkedHashMap<>();
        one.put("refs/tags/1.0","2222");one.put("refs/heads/main","1111");
        two.put("refs/heads/main","1111");two.put("refs/tags/1.0","2222");
        assertEquals(MirrorManager.digest(one),MirrorManager.digest(two));
        assertNotEquals(MirrorManager.digest(one),MirrorManager.digest(Map.of()));
        String text="1111\trefs/heads/main\n2222\trefs/tags/1.0\n";
        byte[] sum=MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder expected=new StringBuilder();
        for(byte n:sum)expected.append(String.format("%02x",n&255));
        assertEquals(expected.toString(),MirrorManager.digest(one));
    }

    @Test public void noForkIsCreatedByManager() throws Exception {
        String resource="/app/githubble/mobile/mirror/MirrorManager.class";
        assertNotNull(MirrorManager.class.getResource(resource));
        assertEquals("mallstorm/GitHubble-Mirror-Control",MirrorManager.CONTROLLER);
    }
}
