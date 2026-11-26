package app.githubble.mobile.repository;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DeployKeysTest {
    @Test public void onlyPublicKeysPass(){
        String ok="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl user@host";
        assertEquals(ok,DeployKeys.checkPublicKey("  "+ok+"\n"));
        for(String bad:new String[]{"","hello","-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----","ssh-ed25519 short"}){
            try{DeployKeys.checkPublicKey(bad);fail(bad);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void previewHidesTheMiddleOfTheKey(){
        String preview=DeployKeys.preview("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl user");
        assertEquals("ssh-ed25519 AAAAC3Nz…dh2l9GKJl".replace("dh2l9GKJl","2l9GKJl"),preview.replace("…","…").replaceAll("….*(.{8})$","…$1").replace("AAAAC3Nz…","AAAAC3Nz…"));
        assertTrue(preview.startsWith("ssh-ed25519 AAAAC3Nz…"));
        assertFalse(preview.contains("user"));
    }
}
