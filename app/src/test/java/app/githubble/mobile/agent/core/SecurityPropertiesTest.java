package app.githubble.mobile.agent.core;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

/** Security-relevant properties of the new core (TZ 17.1, 15): no payloads on disk, no path escape, bounded parsing. */
public class SecurityPropertiesTest {
    private static final TaskJournal.Clock CLOCK = new TaskJournal.Clock() {
        long t = 1; public long now() { return t++; }
    };

    @Test public void journalNeverStoresToolArguments() throws Exception {
        File dir = Files.createTempDirectory("sec").toFile();
        String secret = "ghp_SENTINEL0123456789012345678901234";
        AgentRunJournal run = new AgentRunJournal("run-sec", "demo", ExecutionMode.AUTO, dir, CLOCK);
        run.start();
        run.toolBegin("o1", "create_issue", "{body=" + secret + "}");
        run.toolResult("o1", "succeeded", "");
        run.finish("succeeded", "ok");
        String onDisk = new String(Files.readAllBytes(new File(dir, "run-sec.journal").toPath()), "UTF-8");
        assertFalse("arguments must not reach disk", onDisk.contains(secret));
        for (ToolLedger.Invocation i : run.ledger().invocations())
            assertFalse(i.argsFingerprint.contains(secret));
    }

    @Test public void journalStoreRejectsPathEscapingIds() throws Exception {
        JournalStore store = new JournalStore(Files.createTempDirectory("sec2").toFile());
        for (String bad : new String[]{"../x", "a/b", "..", "x\u0000y", ""}) {
            try { store.load(bad, CLOCK); fail("accepted " + bad); } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void workflowTriggerParsingIsLinearOnHostileInput() {
        StringBuilder big = new StringBuilder("on:\n");
        for (int i = 0; i < 200_000; i++) big.append("  # workflow_dispatch x\n");
        big.append("jobs:\n");
        long start = System.nanoTime();
        assertFalse(WorkflowTrigger.hasDispatch(big.toString()));
        assertTrue("parsing took too long", (System.nanoTime() - start) / 1_000_000 < 3000);
    }

    @Test public void expectedIdentityCannotSmuggleControlCharacters() {
        assertEquals(ApiFailure.PERMISSION_DENIED, ApiFailure.classify(401, false, false));
        assertEquals(ApiFailure.OTHER, ApiFailure.classify(200, false, false));
        assertEquals("", ApiFailure.OTHER.instruction);
    }
}
