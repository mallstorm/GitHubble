package app.githubble.mobile.mirror;

import static org.junit.Assert.*;
import org.junit.Test;

public class MirrorSetupStatusTest {
    @Test public void mustVerifyAllRequiredMetadataBeforeReady() {
        MirrorManager.SetupStatus s=new MirrorManager.SetupStatus();
        assertFalse(s.verifiedReady());
        s.controllerAccountConnected=true;
        s.connectedAccounts=2;
        s.controller=MirrorManager.SetupStatus.PRESENT;
        s.workflow=MirrorManager.SetupStatus.PRESENT;
        s.secret=MirrorManager.SetupStatus.PRESENT;
        assertFalse("Client ID has not been verified",s.verifiedReady());
        s.variable=MirrorManager.SetupStatus.PRESENT;
        assertTrue(s.verifiedReady());
        s.secret=MirrorManager.SetupStatus.UNKNOWN;
        assertFalse("Unknown secret metadata cannot be marked ready",s.verifiedReady());
        s.secret=MirrorManager.SetupStatus.PRESENT;
        s.connectedAccounts=1;
        assertFalse("Second GitHub account is required",s.verifiedReady());
    }
}
