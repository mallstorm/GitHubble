package app.githubble.mobile.agent.core;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class AgentRunJournalTest {
    private static final TaskJournal.Clock CLOCK = new TaskJournal.Clock() {
        long t = 5000; public long now() { return t++; }
    };

    private static AgentRunJournal run(File dir) {
        AgentRunJournal j = new AgentRunJournal("run-1", "demo", ExecutionMode.AUTO, dir, CLOCK);
        j.start();
        return j;
    }

    private static File tmp() throws Exception { return Files.createTempDirectory("journal").toFile(); }

    @Test public void answerOnlyRunCompletesFromAnalyzing() throws Exception {
        AgentRunJournal j = run(tmp());
        assertNull(j.toolBegin("o1", "repository_read_file", "{path=a}"));
        j.toolResult("o1", "succeeded", "");
        j.finish("succeeded", "ok");
        assertEquals(TaskState.COMPLETED, j.journal().state());
    }

    @Test public void writeMovesToExecutingAndCompletesLocally() throws Exception {
        AgentRunJournal j = run(tmp());
        j.toolBegin("o1", "write_files", "{x}");
        assertEquals(TaskState.EXECUTING, j.journal().state());
        j.toolResult("o1", "succeeded", "");
        j.finish("succeeded", "ok");
        assertEquals(TaskState.COMPLETED, j.journal().state());
    }

    @Test public void startedBuildWithoutVerificationPausesInsteadOfCompleting() throws Exception {
        AgentRunJournal j = run(tmp());
        j.toolBegin("o1", "dispatch_workflow", "{wf}");
        j.toolDispatched("o1");
        j.toolResult("o1", "succeeded", "");
        assertEquals(TaskState.BUILDING, j.journal().state());
        j.finish("succeeded", "запущено");
        assertEquals(TaskState.PAUSED, j.journal().state());
        assertEquals(TaskState.BUILDING, j.journal().resumeState());
    }

    @Test public void verifiedBuildCompletes() throws Exception {
        AgentRunJournal j = run(tmp());
        j.toolBegin("o1", "dispatch_workflow", "{wf}"); j.toolResult("o1", "succeeded", "");
        j.toolBegin("o2", "verify_build", "{run=1}"); j.toolResult("o2", "succeeded", "");
        assertEquals(TaskState.VERIFYING, j.journal().state());
        j.finish("succeeded", "готово");
        assertEquals(TaskState.COMPLETED, j.journal().state());
    }

    @Test public void failedBuildLoopReturnsToExecutingThenBuildsAgain() throws Exception {
        AgentRunJournal j = run(tmp());
        j.toolBegin("o1", "dispatch_workflow", "{a}"); j.toolResult("o1", "succeeded", "");
        j.toolBegin("o2", "write_files", "{fix}");
        assertEquals(TaskState.EXECUTING, j.journal().state());
        j.toolResult("o2", "succeeded", "");
        j.toolBegin("o3", "dispatch_workflow", "{a}"); // dispatch is not a once-only tool
        assertEquals(TaskState.BUILDING, j.journal().state());
    }

    @Test public void repeatedCreationIsRefusedAfterSuccess() throws Exception {
        AgentRunJournal j = run(tmp());
        assertNull(j.toolBegin("o1", "create_pull_request", "{t=1}"));
        j.toolDispatched("o1");
        j.toolResult("o1", "succeeded", "");
        assertNotNull(j.toolBegin("o2", "create_pull_request", "{t=1}"));
        assertNull(j.toolBegin("o3", "create_pull_request", "{t=2}"));
    }

    @Test public void lostAnswerOnWriteBlocksRepeat() throws Exception {
        AgentRunJournal j = run(tmp());
        j.toolBegin("o1", "create_issue", "{t=1}");
        j.toolDispatched("o1");
        j.toolResult("o1", "failed", "timeout");
        assertEquals(ToolLedger.State.UNKNOWN, j.ledger().invocations().get(0).state());
        assertNotNull(j.toolBegin("o2", "create_issue", "{t=1}"));
    }

    @Test public void approvalSuspendsAndResumes() throws Exception {
        AgentRunJournal j = run(tmp());
        j.toolBegin("o1", "write_files", "{x}");
        j.approval(true);
        assertEquals(TaskState.WAITING_PERMISSION, j.journal().state());
        j.approval(false);
        assertEquals(TaskState.EXECUTING, j.journal().state());
    }

    @Test public void interruptedAndCancelledRuns() throws Exception {
        AgentRunJournal a = run(tmp());
        a.finish("interrupted", "Задача прервана");
        assertEquals(TaskState.FAILED, a.journal().state());
        AgentRunJournal b = run(tmp());
        b.finish("cancelled", "Остановлено пользователем");
        assertEquals(TaskState.CANCELLED, b.journal().state());
    }

    @Test public void journalSurvivesAndRecoveryMarksKilledRuns() throws Exception {
        File dir = tmp();
        AgentRunJournal j = run(dir);
        j.toolBegin("o1", "write_files", "{x}");           // process "dies" here
        assertEquals(1, AgentRunJournal.recoverInterrupted(dir, CLOCK));
        TaskJournal loaded = new JournalStore(dir).load("run-1", CLOCK);
        assertEquals(TaskState.FAILED, loaded.state());
        assertEquals(TaskState.EXECUTING, loaded.resumeState());
        assertEquals(0, AgentRunJournal.recoverInterrupted(dir, CLOCK)); // idempotent
    }

    @Test public void pausedBuildIsNotTouchedByRecovery() throws Exception {
        File dir = tmp();
        AgentRunJournal j = run(dir);
        j.toolBegin("o1", "dispatch_workflow", "{a}"); j.toolResult("o1", "succeeded", "");
        j.finish("succeeded", "запущено");
        assertEquals(0, AgentRunJournal.recoverInterrupted(dir, CLOCK));
        assertEquals(TaskState.PAUSED, new JournalStore(dir).load("run-1", CLOCK).state());
    }

    @Test public void unwritableDirectoryDoesNotBreakTheRun() throws Exception {
        File file = File.createTempFile("notadir", "x");
        AgentRunJournal j = new AgentRunJournal("run-2", "demo", ExecutionMode.SEMI, file, CLOCK);
        j.start();
        j.toolBegin("o1", "write_files", "{x}");
        j.finish("succeeded", "ok");
        assertTrue(j.persistFailed());
        assertEquals(TaskState.COMPLETED, j.journal().state());
    }

    @Test public void buildingCannotCompleteDirectly() {
        assertFalse(TaskState.BUILDING.canMoveTo(TaskState.COMPLETED));
        assertFalse(TaskState.VERIFYING.canMoveTo(TaskState.COMPLETED));
        assertTrue(TaskState.ANALYZING.canMoveTo(TaskState.COMPLETED));
    }
}
