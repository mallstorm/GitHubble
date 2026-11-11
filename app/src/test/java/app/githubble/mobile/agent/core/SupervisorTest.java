package app.githubble.mobile.agent.core;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public class SupervisorTest {
    static final class Fake implements Evidence {
        String head = "a".repeat(40); Boolean commit = true; PullInfo pr = new PullInfo(true, "open", "a".repeat(40), "main");
        RunInfo run = new RunInfo("completed", "success", "a".repeat(40), "feat");
        List<ArtifactInfo> artifacts = new ArrayList<>(Arrays.asList(new ArtifactInfo("app-apk", 1000, false)));
        boolean throwing;
        public String branchHead(String r, String b) throws Exception { if (throwing) throw new java.io.IOException(); return head; }
        public Boolean commitExists(String r, String s) { return commit; }
        public PullInfo pullRequest(String r, int n) { return pr; }
        public RunInfo workflowRun(String r, long id) { return run; }
        public List<ArtifactInfo> artifacts(String r, long id) { return artifacts; }
    }

    private static ArtifactVerifier.Report goodApk() {
        try {
            ArtifactVerifier.Expectation e = new ArtifactVerifier.Expectation(); e.minBytes = 100; e.packageId = "app.demo";
            return ArtifactVerifier.verify(ArtifactVerifierTest.apk(ArtifactVerifierTest.manifest("app.demo", 1, "1", false), true, false), e);
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }

    private static Supervisor.Claims claims() {
        Supervisor.Claims c = new Supervisor.Claims();
        c.repo = "o/r"; c.branch = "feat"; c.commitSha = "a".repeat(40); c.pullNumber = 3; c.runId = 9;
        c.claimsBuildDone = true; c.apkReport = goodApk();
        return c;
    }

    @Test public void fullyEvidencedDeliveryIsAccepted() {
        Supervisor.Verdict v = new Supervisor(new Fake()).verifyDelivery(claims());
        assertTrue(v.summary(), v.accepted());
    }

    @Test public void missingBranchPrAndCommitAreBlocked() {
        Fake f = new Fake(); f.head = "";
        assertFalse(new Supervisor(f).verifyDelivery(claims()).accepted());
        f = new Fake(); f.pr = new Evidence.PullInfo(false, "", "", "");
        Supervisor.Verdict v = new Supervisor(f).verifyDelivery(claims());
        assertFalse(v.accepted()); assertEquals(TaskState.DELIVERING, v.returnTo());
        f = new Fake(); f.head = "b".repeat(40); f.commit = false;
        assertFalse(new Supervisor(f).verifyDelivery(claims()).accepted());
    }

    @Test public void successfulWorkflowWithoutApkIsNotDelivery() {   // spec scenario G
        Fake f = new Fake(); f.artifacts = Collections.emptyList();
        Supervisor.Verdict v = new Supervisor(f).verifyDelivery(claims());
        assertFalse(v.accepted()); assertEquals(TaskState.BUILDING, v.returnTo());
        f = new Fake(); f.artifacts = Arrays.asList(new Evidence.ArtifactInfo("app-apk", 0, false));
        assertFalse(new Supervisor(f).verifyDelivery(claims()).accepted());
        f = new Fake(); f.artifacts = Arrays.asList(new Evidence.ArtifactInfo("app-apk", 10, true));
        assertFalse(new Supervisor(f).verifyDelivery(claims()).accepted());
    }

    @Test public void failedUnfinishedOrForeignRunsAreBlocked() {
        Fake f = new Fake(); f.run = new Evidence.RunInfo("completed", "failure", "a".repeat(40), "feat");
        assertFalse(new Supervisor(f).verifyDelivery(claims()).accepted());
        f = new Fake(); f.run = new Evidence.RunInfo("in_progress", "", "a".repeat(40), "feat");
        assertFalse(new Supervisor(f).verifyDelivery(claims()).accepted());
        f = new Fake(); f.run = new Evidence.RunInfo("completed", "success", "c".repeat(40), "feat");
        assertFalse(new Supervisor(f).verifyDelivery(claims()).accepted());
    }

    @Test public void buildClaimWithoutRunOrApkReportIsBlocked() {
        Supervisor.Claims c = claims(); c.runId = 0;
        assertFalse(new Supervisor(new Fake()).verifyDelivery(c).accepted());
        c = claims(); c.apkReport = null;
        assertFalse(new Supervisor(new Fake()).verifyDelivery(c).accepted());
        c = claims(); c.apkReport = ArtifactVerifier.verify(new java.io.File("/missing"), new ArtifactVerifier.Expectation());
        assertFalse(new Supervisor(new Fake()).verifyDelivery(c).accepted());
    }

    @Test public void unreachableEvidenceNeverCountsAsSuccess() {
        Fake f = new Fake(); f.throwing = true;
        assertFalse(new Supervisor(f).verifyDelivery(claims()).accepted());
    }

    @Test public void changeReviewFlagsPlanDeletionSecretsAndPermissions() {
        Supervisor s = new Supervisor(new Fake());
        Supervisor.Change ok = new Supervisor.Change();
        ok.plannedPaths = new HashSet<>(Arrays.asList("a.kt")); ok.changedPaths.add("a.kt");
        assertTrue(s.reviewChange(ok).accepted());

        Supervisor.Change c = new Supervisor.Change();
        c.plannedPaths = new HashSet<>(Arrays.asList("a.kt")); c.changedPaths.add("b.kt");
        assertFalse(s.reviewChange(c).accepted());

        c = new Supervisor.Change(); c.deletedPaths.add("x.kt");
        assertFalse(s.reviewChange(c).accepted());
        c.allowedDeletions = 1; assertTrue(s.reviewChange(c).accepted());

        c = new Supervisor.Change(); c.addedText.add("val t = \"ghp_" + "A".repeat(36) + "\"");
        assertFalse(s.reviewChange(c).accepted());
        c = new Supervisor.Change(); c.addedText.add("-----BEGIN RSA PRIVATE KEY-----");
        assertFalse(s.reviewChange(c).accepted());
        c = new Supervisor.Change(); c.addedText.add("val x = 1");
        assertTrue(s.reviewChange(c).accepted());

        c = new Supervisor.Change();
        c.permissionsBefore = new HashSet<>(Arrays.asList("contents:read"));
        c.permissionsAfter = new HashSet<>(Arrays.asList("contents:read", "contents:write"));
        assertFalse(s.reviewChange(c).accepted());

        c = new Supervisor.Change(); c.testsFailed = true;
        assertEquals(TaskState.EXECUTING, s.reviewChange(c).returnTo());
        c = new Supervisor.Change(); c.unmetRequirements.add("кнопка Pause");
        assertFalse(s.reviewChange(c).accepted());
    }

    @Test public void workflowEditsAreWarnedNotBlocked() {
        Supervisor.Change c = new Supervisor.Change(); c.changedPaths.add(".github/workflows/android.yml");
        Supervisor.Verdict v = new Supervisor(new Fake()).reviewChange(c);
        assertTrue(v.accepted()); assertEquals(1, v.findings.size());
    }
}
