package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import app.githubble.mobile.agent.core.AgentRunJournal;
import app.githubble.mobile.agent.core.ExecutionMode;
import app.githubble.mobile.agent.core.TaskState;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;

public class AgentRunJournalWiringTest {
  @Test
  public void runDrivesJournalAndBlocksRepeatedCreation() throws Exception {
    AgentRun run = new AgentRun("one", "thread", () -> 0);
    AgentRunJournal journal =
        new AgentRunJournal(
            run.id, "demo", ExecutionMode.AUTO,
            Files.createTempDirectory("wiring").toFile(), System::currentTimeMillis);
    run.attachJournal(journal);
    JSONObject args = new JSONObject().put("repository", "a/b").put("title", "T");

    String first = run.begin("create_issue", args);
    run.requireNotDuplicate();
    run.remoteDispatched();
    run.result(first, "succeeded", "");
    assertEquals(TaskState.EXECUTING, journal.journal().state());

    run.begin("create_issue", args);
    try {
      run.requireNotDuplicate();
      fail("repeat must be refused");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("create_issue"));
    }
    run.finish("succeeded", "ok");
    assertEquals(TaskState.COMPLETED, journal.journal().state());
  }
}
