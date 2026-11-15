package app.githubble.mobile.mirror;
import static org.junit.Assert.*;
import org.junit.Test;
public class MirrorSetupStatusTest {
    @Test public void requiresTwoAccountsNoControllerOrPem(){
        MirrorManager.SetupStatus s=new MirrorManager.SetupStatus();
        assertFalse(s.verifiedReady());
        s.connectedAccounts=2;
        assertTrue(s.verifiedReady());
    }
}
