package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;

public class AgentRunTest {
  @Test
  public void timerIncludesApprovalAndFreezesAtCompletion() throws Exception {
    AtomicLong clock = new AtomicLong(1000);
    AgentRun run = new AgentRun("one", "thread", clock::get);
    run.round(1);
    String op = run.begin("write_files", new JSONObject().put("path", "src/App.java"));
    clock.addAndGet(2500);
    run.approval(true);
    clock.addAndGet(6500);
    assertEquals(9000, run.snapshot().getLong("elapsed_ms"));
    run.approval(false);
    run.result(op, "succeeded", "");
    run.finish("succeeded", "Готово");
    clock.addAndGet(100000);
    assertEquals(9000, run.snapshot().getLong("elapsed_ms"));
    assertEquals("00:09", AgentRun.time(9000));
    assertEquals("1:02:03", AgentRun.time(3723000));
    assertEquals(
        9000, run.snapshot().getJSONArray("operations").getJSONObject(0).getLong("elapsed_ms"));
  }

  @Test
  public void lateResultsCannotCompleteCancelledTask() throws Exception {
    AtomicLong clock = new AtomicLong();
    AgentRun run = new AgentRun("one", "thread", clock::get);
    String op = run.begin("read_file", new JSONObject().put("path", "README.md"));
    clock.set(1400);
    run.finish("cancelled", "Остановлено");
    clock.set(6000);
    run.result(op, "succeeded", "");
    assertEquals("cancelled", run.snapshot().getString("state"));
    assertEquals(
        "cancelled", run.snapshot().getJSONArray("operations").getJSONObject(0).getString("state"));
    assertEquals(1400, run.snapshot().getLong("elapsed_ms"));
  }

  @Test
  public void dispatchedWriteIsUnknownAfterCancellation() throws Exception {
    AgentRun run = new AgentRun("one", "thread", () -> 0);
    run.begin("publish_workspace", new JSONObject());
    run.remoteDispatched();
    run.finish("cancelled", "Отмена");
    assertEquals(
        "unknown", run.snapshot().getJSONArray("operations").getJSONObject(0).getString("state"));
  }

  @Test
  public void ambiguousWriteStopsFurtherRuntimeWork() throws Exception {
    AgentRun run = new AgentRun("one", "thread", () -> 0);
    String id = run.begin("publish_workspace", new JSONObject());
    run.remoteDispatched();
    try {
      run.result(id, "failed", "connection lost");
      fail("Uncertain write must stop execution");
    } catch (IllegalStateException expected) {
    }
    assertEquals(
        "unknown", run.snapshot().getJSONArray("operations").getJSONObject(0).getString("state"));
  }

  @Test
  public void taskSnapshotsCannotBeMutatedByView() throws Exception {
    AgentRun run = new AgentRun("one", "thread", () -> 0);
    run.begin("read_file", new JSONObject().put("path", "a.java"));
    run.snapshot().getJSONArray("operations").getJSONObject(0).put("state", "succeeded");
    assertEquals(
        "running", run.snapshot().getJSONArray("operations").getJSONObject(0).getString("state"));
  }
}
