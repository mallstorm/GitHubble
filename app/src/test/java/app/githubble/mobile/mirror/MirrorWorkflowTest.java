package app.githubble.mobile.mirror;

import static org.junit.Assert.*;
import org.junit.Test;

public class MirrorWorkflowTest {
    @Test public void oneshotIsSourceLocalAndTemporary(){
        String y=MirrorWorkflow.yaml();
        assertTrue(y.contains("workflow_dispatch:"));
        assertTrue(y.contains("github.repository"));
        assertTrue(y.contains("github.token"));
        assertTrue(y.contains("secrets[format('GITHUBBLE_ONESHOT_DST_{0}', inputs.request_id)]"));
        assertTrue(y.contains("git clone --bare"));
        assertTrue(y.contains("git lfs fetch --all origin"));
        assertTrue(y.contains("git lfs push --all"));
        assertTrue(y.contains("git push "));
        assertTrue(y.contains("refs/heads/*"));
        assertTrue(y.contains("refs/tags/*"));
        assertTrue(y.contains("Destination is not empty"));
        assertTrue(y.contains("git update-ref"));
        assertFalse(y.contains("create-github-app-token"));
        assertFalse(y.contains("GITHUBBLE_MIRROR_APP_PRIVATE_KEY"));
        assertFalse(y.contains("git push --force"));
        assertFalse(y.contains("set -x"));
        assertFalse(y.contains("workflow_call"));
        assertEquals(".github/workflows/githubble-oneshot-0123456789abcdef0123456789abcdef.yml",
                MirrorWorkflow.path("0123456789abcdef0123456789abcdef"));
    }
    @Test public void rejectsUnsafeId() {
        for(String id:new String[]{"../../x","a","","0123","abcdef0123456789abcdef0123456789;"}){
            try{MirrorWorkflow.path(id);fail(id);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void validatesPerRunSecretName(){
        assertEquals("GITHUBBLE_ONESHOT_DST_0123456789ABCDEF0123456789ABCDEF",
                MirrorSecrets.name("0123456789abcdef0123456789abcdef"));
    }
}
