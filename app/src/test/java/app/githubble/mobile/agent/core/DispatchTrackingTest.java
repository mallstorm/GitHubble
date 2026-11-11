package app.githubble.mobile.agent.core;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class DispatchTrackingTest {
    @Test public void triggerForms() {
        assertTrue(WorkflowTrigger.hasDispatch("name: x\non: workflow_dispatch\njobs:\n  a:\n"));
        assertTrue(WorkflowTrigger.hasDispatch("on: [push, workflow_dispatch]\njobs:\n"));
        assertTrue(WorkflowTrigger.hasDispatch("on:\n  push:\n  workflow_dispatch:\n    inputs:\n      a:\njobs:\n"));
        assertTrue(WorkflowTrigger.hasDispatch("'on':\n  workflow_dispatch:\njobs:\n"));
    }

    @Test public void triggerAbsentOrMisleading() {
        assertFalse(WorkflowTrigger.hasDispatch("on:\n  push:\njobs:\n  a:\n    if: github.event_name == 'workflow_dispatch'\n"));
        assertFalse(WorkflowTrigger.hasDispatch("on:\n  push:\n  # workflow_dispatch:\njobs:\n"));
        assertFalse(WorkflowTrigger.hasDispatch("on: push # workflow_dispatch\njobs:\n"));
        assertFalse(WorkflowTrigger.hasDispatch("on: [push, my_workflow_dispatch_x]\njobs:\n"));
        assertFalse(WorkflowTrigger.hasDispatch(""));
        assertFalse(WorkflowTrigger.hasDispatch(null));
    }

    private static Set<Long> set(Long... v) { return new HashSet<>(java.util.Arrays.asList(v)); }

    @Test public void matchesTheOnlyNewDispatchRun() {
        DispatchRunMatcher.Match m = DispatchRunMatcher.pick(new long[]{9, 8, 7},
            new String[]{"workflow_dispatch", "workflow_dispatch", "push"}, new String[]{"dev", "dev", "dev"},
            set(8L, 7L), "dev");
        assertEquals(DispatchRunMatcher.Outcome.MATCHED, m.outcome);
        assertEquals(9, m.runId);
    }

    @Test public void ignoresOtherBranchesAndEvents() {
        DispatchRunMatcher.Match m = DispatchRunMatcher.pick(new long[]{9, 10},
            new String[]{"workflow_dispatch", "push"}, new String[]{"other", "dev"}, set(), "dev");
        assertEquals(DispatchRunMatcher.Outcome.NOT_FOUND, m.outcome);
    }

    @Test public void concurrentDispatchesAreAmbiguousNotGuessed() {
        DispatchRunMatcher.Match m = DispatchRunMatcher.pick(new long[]{12, 11},
            new String[]{"workflow_dispatch", "workflow_dispatch"}, new String[]{"dev", "dev"}, set(), "dev");
        assertEquals(DispatchRunMatcher.Outcome.AMBIGUOUS, m.outcome);
        assertEquals(12, m.runId);
    }
}
