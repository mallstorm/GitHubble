package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import app.githubble.mobile.agent.core.AgentRunJournal;
import app.githubble.mobile.agent.core.ExecutionMode;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AgentActivityPanelTest {
  @Test public void labelsCoverEveryTaskStateAndMode() {
    for (app.githubble.mobile.agent.core.TaskState s : app.githubble.mobile.agent.core.TaskState.values())
      assertNotEquals(s.name(), AgentTaskLabels.state(s.name()));
    assertEquals("Состояние: Сборка · режим: авто", AgentTaskLabels.line("BUILDING", "AUTO"));
    assertEquals("", AgentTaskLabels.line("", "AUTO"));
    assertTrue(AgentTaskLabels.resumable("PAUSED"));
    assertTrue(AgentTaskLabels.resumable("FAILED"));
    assertFalse(AgentTaskLabels.resumable("COMPLETED"));
    assertFalse(AgentTaskLabels.resumable("EXECUTING"));
  }

  @Test public void snapshotCarriesTaskStateOnlyWithJournal() throws Exception {
    AgentRun plain = new AgentRun("a", "t", () -> 0);
    assertFalse(plain.snapshot().has("task_state"));
    AgentRun run = new AgentRun("a", "t", () -> 0);
    run.attachJournal(new AgentRunJournal(run.id, "p", ExecutionMode.SEMI,
        Files.createTempDirectory("panel").toFile(), System::currentTimeMillis));
    assertEquals("ANALYZING", run.snapshot().getString("task_state"));
    assertEquals("SEMI", run.snapshot().getString("mode"));
    run.begin("write_files", new JSONObject());
    assertEquals("EXECUTING", run.snapshot().getString("task_state"));
  }

  @Test public void eventsFireForApprovalAndFinish() throws Exception {
    final List<String> seen = new ArrayList<>();
    AgentRun run = new AgentRun("a", "t", () -> 0);
    run.events(new AgentRun.Events() {
      @Override public void approvalWaiting(String what) { seen.add("approval:" + what); }
      @Override public void finished(String state, String stage) { seen.add("finished:" + state); }
    });
    run.begin("write_files", new JSONObject());
    run.approval(true);
    run.approval(false);
    run.finish("succeeded", "ok");
    assertEquals(2, seen.size());
    assertEquals("approval:Изменения файлов", seen.get(0));
    assertEquals("finished:succeeded", seen.get(1));
  }

  @Test public void throwingEventsNeverBreakTheRun() throws Exception {
    AgentRun run = new AgentRun("a", "t", () -> 0);
    run.events(new AgentRun.Events() {
      @Override public void approvalWaiting(String what) { throw new IllegalStateException("boom"); }
      @Override public void finished(String state, String stage) { throw new IllegalStateException("boom"); }
    });
    run.begin("write_files", new JSONObject());
    run.approval(true);
    run.finish("succeeded", "ok");
    assertEquals("succeeded", run.snapshot().getString("state"));
  }

  @Test public void resumePromptSummarizesWithoutRepeatingBlindly() throws Exception {
    JSONArray ops = new JSONArray()
        .put(new JSONObject().put("name", "write_files").put("target", "src/A.java").put("state", "succeeded"))
        .put(new JSONObject().put("name", "create_pull_request").put("target", "a/b").put("state", "unknown")
            .put("error", "Результат неизвестен"))
        .put(new JSONObject().put("name", "dispatch_workflow").put("state", "failed").put("error", "HTTP 422"));
    JSONObject snapshot = new JSONObject().put("state", "interrupted").put("task_state", "PAUSED").put("operations", ops);
    String prompt = ResumePrompt.build(snapshot);
    assertTrue(prompt.contains("✓ write_files · src/A.java"));
    assertTrue(prompt.contains("? create_pull_request"));
    assertTrue(prompt.contains("✗ dispatch_workflow"));
    assertTrue(prompt.contains("не повторяй успешные записи"));
    assertTrue(prompt.contains("проверь фактическое состояние в GitHub"));
    assertTrue(prompt.contains("await_build"));
    assertTrue(prompt.contains("Приостановлена"));
  }

  @Test public void resumePromptIsBoundedAndHandlesEmptyHistory() throws Exception {
    JSONArray ops = new JSONArray();
    for (int i = 0; i < 40; i++)
      ops.put(new JSONObject().put("name", "read_file").put("target", "f" + i).put("state", "succeeded"));
    String bounded = ResumePrompt.build(new JSONObject().put("state", "failed").put("operations", ops));
    assertTrue(bounded.contains("f39"));
    assertFalse(bounded.contains("f10 "));
    assertTrue(ResumePrompt.build(new JSONObject().put("state", "failed")).contains("операций не было"));
  }
}
