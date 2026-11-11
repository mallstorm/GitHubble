package app.githubble.mobile.agent.core;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class TaskJournalTest {
    private static final TaskJournal.Clock CLOCK = new TaskJournal.Clock() {
        long t = 1000; public long now() { return t++; }
    };

    private static TaskJournal journal(ExecutionMode m) { return new TaskJournal("t-1", "demo", m, CLOCK); }

    private static void advanceTo(TaskJournal j, TaskState target) {
        TaskState[] chain = {TaskState.ANALYZING, TaskState.PLANNING, TaskState.APPROVAL, TaskState.EXECUTING,
            TaskState.TESTING, TaskState.BUILDING, TaskState.VERIFYING, TaskState.DELIVERING, TaskState.COMPLETED};
        for (TaskState s : chain) { j.transition(s, "x"); if (s == target) return; }
    }

    @Test public void happyPathReachesCompleted() {
        TaskJournal j = journal(ExecutionMode.AUTO);
        advanceTo(j, TaskState.COMPLETED);
        assertEquals(TaskState.COMPLETED, j.state());
        assertTrue(j.state().isTerminal());
    }

    @Test public void skippingStagesIsRejected() {
        TaskJournal j = journal(ExecutionMode.AUTO);
        try { j.transition(TaskState.BUILDING, "skip"); fail(); } catch (IllegalStateException expected) { }
        assertEquals(TaskState.CREATED, j.state());
    }

    @Test public void terminalStatesAcceptNothing() {
        TaskJournal j = journal(ExecutionMode.AUTO);
        advanceTo(j, TaskState.COMPLETED);
        for (TaskState s : TaskState.values()) assertFalse(s.name(), TaskState.COMPLETED.canMoveTo(s));
    }

    @Test public void failedBuildGoesBackToExecuting() {
        TaskJournal j = journal(ExecutionMode.AUTO);
        advanceTo(j, TaskState.BUILDING);
        j.transition(TaskState.EXECUTING, "fix build");
        j.transition(TaskState.TESTING, "retest");
        assertEquals(TaskState.TESTING, j.state());
    }

    @Test public void pauseResumesToInterruptedStateOnly() {
        TaskJournal j = journal(ExecutionMode.AUTO);
        advanceTo(j, TaskState.TESTING);
        j.pause("user");
        assertEquals(TaskState.PAUSED, j.state());
        try { j.transition(TaskState.BUILDING, "wrong"); fail(); } catch (IllegalStateException expected) { }
        j.resume("go");
        assertEquals(TaskState.TESTING, j.state());
        assertNull(j.resumeState());
    }

    @Test public void failedTaskRecoversToFailedState() {
        TaskJournal j = journal(ExecutionMode.AUTO);
        advanceTo(j, TaskState.BUILDING);
        j.fail("runner died");
        assertEquals(TaskState.FAILED, j.state());
        j.recover("restart");
        assertEquals(TaskState.RECOVERING, j.state());
        j.resume("continue");
        assertEquals(TaskState.BUILDING, j.state());
    }

    @Test public void switchingToManualParksExecutingTask() {
        TaskJournal j = journal(ExecutionMode.AUTO);
        advanceTo(j, TaskState.EXECUTING);
        j.switchMode(ExecutionMode.MANUAL, "user");
        assertEquals(ExecutionMode.MANUAL, j.mode());
        assertEquals(TaskState.WAITING_PERMISSION, j.state());
        j.resume("approved");
        assertEquals(TaskState.EXECUTING, j.state());
    }

    @Test public void modeSwitchDoesNotRepeatHistory() {
        TaskJournal j = journal(ExecutionMode.AUTO);
        advanceTo(j, TaskState.EXECUTING);
        int before = j.events().size();
        j.switchMode(ExecutionMode.SEMI, "x");
        assertEquals(before + 1, j.events().size());
        assertEquals(TaskState.EXECUTING, j.state());
    }

    @Test public void serializationRoundTripKeepsEverything() {
        TaskJournal j = journal(ExecutionMode.SEMI);
        advanceTo(j, TaskState.TESTING);
        j.checkpoint("before\ttests\nline2");
        j.pause("tab\tand\\slash");
        TaskJournal copy = TaskJournal.parse(j.serialize(), CLOCK);
        assertEquals(j.id, copy.id);
        assertEquals(TaskState.PAUSED, copy.state());
        assertEquals(TaskState.TESTING, copy.resumeState());
        assertEquals(ExecutionMode.SEMI, copy.mode());
        assertEquals(j.checkpoints(), copy.checkpoints());
        assertEquals(j.events().size(), copy.events().size());
        assertEquals("tab\tand\\slash", copy.events().get(copy.events().size() - 1).detail);
        copy.resume("go");
        assertEquals(TaskState.TESTING, copy.state());
    }

    @Test public void corruptJournalIsRejected() {
        try { TaskJournal.parse("garbage", CLOCK); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void storeSurvivesRestartAndSkipsFinished() throws Exception {
        File dir = Files.createTempDirectory("journals").toFile();
        JournalStore store = new JournalStore(dir);
        TaskJournal running = new TaskJournal("run-1", "p", ExecutionMode.AUTO, CLOCK);
        running.transition(TaskState.ANALYZING, "x");
        TaskJournal done = new TaskJournal("done-1", "p", ExecutionMode.AUTO, CLOCK);
        advanceTo(done, TaskState.COMPLETED);
        store.save(running); store.save(done);
        Files.write(new File(dir, "bad.journal").toPath(), "junk".getBytes("UTF-8"));
        JournalStore reopened = new JournalStore(dir);
        assertEquals(1, reopened.unfinished(CLOCK).size());
        assertEquals("run-1", reopened.unfinished(CLOCK).get(0).id);
        assertEquals(TaskState.ANALYZING, reopened.load("run-1", CLOCK).state());
        assertNull(reopened.load("absent", CLOCK));
        try { reopened.load("../escape", CLOCK); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void modePolicyMatchesSpec() {
        for (ExecutionMode.Action a : ExecutionMode.Action.values()) {
            if (a == ExecutionMode.Action.READ) continue;
            assertEquals(a.name(), ExecutionMode.Decision.NEED_APPROVAL,
                ExecutionMode.MANUAL.decide(a, true, true));
        }
        assertEquals(ExecutionMode.Decision.ALLOW, ExecutionMode.MANUAL.decide(ExecutionMode.Action.READ, false, false));
        // Dangerous needs approval in every mode, even AUTO.
        for (ExecutionMode m : ExecutionMode.values())
            assertEquals(ExecutionMode.Decision.NEED_APPROVAL, m.decide(ExecutionMode.Action.DANGEROUS, true, true));
        assertEquals(ExecutionMode.Decision.ALLOW, ExecutionMode.AUTO.decide(ExecutionMode.Action.MERGE_PROTECTED, false, false));
        assertEquals(ExecutionMode.Decision.NEED_APPROVAL, ExecutionMode.SEMI.decide(ExecutionMode.Action.PUBLISH, true, true));
        assertEquals(ExecutionMode.Decision.NEED_APPROVAL, ExecutionMode.SEMI.decide(ExecutionMode.Action.MERGE_PROTECTED, true, false));
        assertEquals(ExecutionMode.Decision.ALLOW, ExecutionMode.SEMI.decide(ExecutionMode.Action.MERGE_PROTECTED, false, true));
        assertEquals(ExecutionMode.Decision.ALLOW, ExecutionMode.SEMI.decide(ExecutionMode.Action.LOCAL_WRITE, false, false));
        assertEquals(app.githubble.mobile.agent.AgentPermissionPolicy.GITHUB_AUTO, ExecutionMode.AUTO.permissionLevel());
    }
}
