package app.githubble.mobile.agent.core;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Deterministic simulations of host logic for TZ 17.3 scenarios D, E, F, G. These are NOT live
 * end-to-end runs: no device, GitHub or model is involved. Scenarios A, B, C, H need live services.
 */
public class ScenarioSimulationTest {
    private static final TaskJournal.Clock CLOCK = new TaskJournal.Clock() {
        long t = 100; public long now() { return t++; }
    };

    /** D — the process dies mid-task; after restart the progress is still on disk and not lost. */
    @Test public void scenarioD_recoveryAfterProcessDeath() throws Exception {
        File dir = Files.createTempDirectory("scenario-d").toFile();
        AgentRunJournal run = new AgentRunJournal("run-d", "demo", ExecutionMode.AUTO, dir, CLOCK);
        run.start();
        run.toolBegin("o1", "write_files", "{a}"); run.toolResult("o1", "succeeded", "");
        run.toolBegin("o2", "dispatch_workflow", "{b}"); run.toolDispatched("o2");
        // process killed here: o2 never reported back
        assertEquals(1, AgentRunJournal.recoverInterrupted(dir, CLOCK));
        TaskJournal after = new JournalStore(dir).load("run-d", CLOCK);
        assertEquals(TaskState.FAILED, after.state());
        assertEquals(TaskState.BUILDING, after.resumeState());
        boolean wroteBeforeDeath = false;
        for (TaskJournal.Event e : after.events()) if (e.detail.contains("write_files")) wroteBeforeDeath = true;
        assertTrue("completed work is still recorded", wroteBeforeDeath);
        after.recover("пользователь продолжает");
        assertEquals(TaskState.RECOVERING, after.state());
    }

    /** E — AUTO switched to MANUAL mid-task: the next change needs approval, reads stay free. */
    @Test public void scenarioE_switchingToManualRequiresApproval() {
        assertEquals(ExecutionMode.Decision.ALLOW, ExecutionMode.AUTO.decide(ExecutionMode.Action.WORKING_BRANCH_WRITE, false, false));
        ExecutionMode now = ExecutionMode.forLevel(app.githubble.mobile.agent.AgentPermissionPolicy.REVIEW);
        assertEquals(ExecutionMode.MANUAL, now);
        assertEquals(ExecutionMode.Decision.NEED_APPROVAL, now.decide(ExecutionMode.Action.LOCAL_WRITE, true, true));
        assertEquals(ExecutionMode.Decision.NEED_APPROVAL, now.decide(ExecutionMode.Action.BUILD, true, true));
        assertEquals(ExecutionMode.Decision.ALLOW, now.decide(ExecutionMode.Action.READ, false, false));
        TaskJournal j = new TaskJournal("t-e", "demo", ExecutionMode.AUTO, CLOCK);
        j.transition(TaskState.ANALYZING, "x"); j.transition(TaskState.PLANNING, "x");
        j.transition(TaskState.APPROVAL, "x"); j.transition(TaskState.EXECUTING, "x");
        j.switchMode(ExecutionMode.MANUAL, "пользователь");
        assertEquals(ExecutionMode.MANUAL, j.mode());
    }

    /** F — GitHub refuses an operation: classified, host demands a report instead of a workaround. */
    @Test public void scenarioF_permissionErrorIsNotCircumvented() {
        ApiFailure f = ApiFailure.classify(403, false, false);
        assertEquals(ApiFailure.PERMISSION_DENIED, f);
        assertTrue(f.instruction.contains("Do NOT retry"));
        assertTrue(f.instruction.contains("get around"));
        assertEquals(ApiFailure.RATE_LIMITED, ApiFailure.classify(403, true, false));
        assertEquals(ApiFailure.NOT_FOUND, ApiFailure.classify(404, false, false));
        assertEquals(ApiFailure.OUTCOME_UNKNOWN, ApiFailure.classify(0, false, true));
        BuildFailureClassifier.Result r = BuildFailureClassifier.classify("Push",
            "RequestError: Resource not accessible by integration");
        RetryPolicy policy = new RetryPolicy(null);
        policy.tryStartBuild();
        RetryPolicy.Decision d = policy.onFailure(r, 0);
        assertNotEquals(RetryPolicy.Action.FIX_SOURCE, d.action);
        assertNotEquals(RetryPolicy.Action.RETRY_AFTER, d.action);
    }

    /** G — the workflow is green but there is no artifact: no "build ready" is accepted. */
    @Test public void scenarioG_greenRunWithoutApkIsNotDelivery() {
        Evidence noArtifact = new Evidence() {
            public String branchHead(String r, String b) { return "a".repeat(40); }
            public Boolean commitExists(String r, String s) { return true; }
            public PullInfo pullRequest(String r, int n) { return new PullInfo(true, "open", "a".repeat(40), "main"); }
            public RunInfo workflowRun(String r, long id) { return new RunInfo("completed", "success", "a".repeat(40), "feat"); }
            public List<ArtifactInfo> artifacts(String r, long id) { return new ArrayList<>(); }
        };
        Supervisor.Claims claims = new Supervisor.Claims();
        claims.repo = "o/r"; claims.branch = "feat"; claims.commitSha = "a".repeat(40); claims.runId = 5;
        claims.claimsBuildDone = true; claims.requireArtifact = true;
        Supervisor.Verdict verdict = new Supervisor(noArtifact).verifyDelivery(claims);
        assertFalse(verdict.summary(), verdict.accepted());
    }
}
