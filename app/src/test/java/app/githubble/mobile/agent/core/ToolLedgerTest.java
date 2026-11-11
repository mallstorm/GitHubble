package app.githubble.mobile.agent.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class ToolLedgerTest {
    private final ToolLedger ledger = new ToolLedger(new TaskJournal.Clock() {
        long t = 0; public long now() { return t += 5; }
    });

    @Test public void successfulWriteCannotBeRepeated() {
        String fp = ToolLedger.fingerprint("{\"branch\":\"x\"}");
        ToolLedger.Invocation a = ledger.begin("t", "create_branch", 1, fp, true);
        ledger.succeed(a, "ref:x");
        assertEquals(5, a.durationMs());
        try { ledger.begin("t", "create_branch", 1, fp, true); fail(); }
        catch (ToolLedger.DuplicateOperationException expected) { }
        // Different arguments and different tasks are independent.
        ledger.begin("t", "create_branch", 1, ToolLedger.fingerprint("{\"branch\":\"y\"}"), true);
        ledger.begin("t2", "create_branch", 1, fp, true);
    }

    @Test public void readsMayRepeat() {
        String fp = ToolLedger.fingerprint("{}");
        ledger.succeed(ledger.begin("t", "list_files", 1, fp, false), "");
        ledger.begin("t", "list_files", 1, fp, false);
    }

    @Test public void unknownOutcomeBlocksRetryUntilSettled() {
        String fp = ToolLedger.fingerprint("pr");
        ToolLedger.Invocation a = ledger.begin("t", "create_pull_request", 1, fp, true);
        ledger.unknown(a, "timeout");
        assertEquals(1, ledger.unresolved("t").size());
        try { ledger.begin("t", "create_pull_request", 1, fp, true); fail(); }
        catch (ToolLedger.DuplicateOperationException expected) { }
        ledger.settle(a, false);   // verified on GitHub: not created
        assertTrue(ledger.unresolved("t").isEmpty());
        ledger.begin("t", "create_pull_request", 1, fp, true);   // now safe
    }

    @Test public void failedWriteMayBeRetried() {
        String fp = ToolLedger.fingerprint("c");
        ledger.fail(ledger.begin("t", "commit", 1, fp, true), "422");
        ledger.begin("t", "commit", 1, fp, true);
    }

    @Test public void concurrentRunningWriteIsRefused() {
        String fp = ToolLedger.fingerprint("d");
        ledger.begin("t", "dispatch_workflow", 1, fp, true);
        try { ledger.begin("t", "dispatch_workflow", 1, fp, true); fail(); }
        catch (ToolLedger.DuplicateOperationException expected) { }
    }

    @Test public void finishedInvocationCannotBeFinishedTwice() {
        ToolLedger.Invocation a = ledger.begin("t", "x", 1, "f", false);
        ledger.succeed(a, "");
        try { ledger.fail(a, "late"); fail(); } catch (IllegalStateException expected) { }
        assertTrue(ledger.succeeded("t", "x"));
    }
}
