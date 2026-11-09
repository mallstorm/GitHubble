package app.githubble.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;
import app.githubble.mobile.agent.*;
import app.githubble.mobile.ui.AgentRunView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Credential-free Android integration and screenshots of the actual production chat components. */
public final class AgentChatUiInstrumentation extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    int status = 0;
    AgentChatPreviewActivity app = null;
    try {
      if (!new app.githubble.mobile.security.SecureStore(getTargetContext())
          .get("github_token")
          .isEmpty()) throw new AssertionError("Clean test profile required");
      storage();
      File directory = getTargetContext().getExternalFilesDir("agent-ui");
      if (directory == null || (!directory.isDirectory() && !directory.mkdirs()))
        throw new AssertionError("Screenshot directory unavailable");
      for (String theme : new String[] {"light", "dark", "beige"})
        for (String scale : new String[] {"1.0", "1.5"}) {
          shell("settings put system font_scale " + scale);
          shell("settings put secure show_ime_with_hard_keyboard 1");
          for (int i = 0;
              i < 100
                  && Math.abs(
                          getTargetContext().getResources().getConfiguration().fontScale
                              - Float.parseFloat(scale))
                      > .05f;
              i++) SystemClock.sleep(50);
          app =
              (AgentChatPreviewActivity)
                  startActivitySync(
                      new Intent()
                          .setClassName(
                              getTargetContext().getPackageName(),
                              "app.githubble.mobile.AgentChatPreviewActivity")
                          .putExtra("theme", theme)
                          .addFlags(
                              Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
          waitForIdleSync();
          SystemClock.sleep(350);
          if (theme.equals("light") && scale.equals("1.0")) verifyNativeTools(app);
          AgentChatPreviewActivity current = app;
          main(
              () -> {
                View root = current.getWindow().getDecorView();
                check(find(root, "План") == null, "Plan button remains visible");
                check(input(root) != null, "Composer missing");
              });
          capture(directory, theme, scale, "empty");
          main(current::showActive);
          waitForIdleSync();
          SystemClock.sleep(300);
          main(
              () -> {
                View root = current.getWindow().getDecorView();
                AgentRunView run = findClass(root, AgentRunView.class);
                check(run != null, "Task disclosure missing");
                run.requestRectangleOnScreen(new Rect(0, 0, run.getWidth(), run.getHeight()), true);
                check(
                    find(root, " +150") != null && find(root, " −0") != null,
                    "Actual file line counts missing");
                input(root).setText("Сохранённый черновик");
                EditText edit = input(root);
                current.refreshFixture();
                check(edit == input(root), "Task refresh replaced the composer");
                check(
                    edit.getText().toString().equals("Сохранённый черновик"),
                    "Draft lost during update");
              });
          waitForIdleSync();
          SystemClock.sleep(220);
          capture(directory, theme, scale, "active");
          long elapsed = app.run.snapshot().getLong("elapsed_ms");
          SystemClock.sleep(1100);
          check(app.run.snapshot().getLong("elapsed_ms") >= elapsed + 900, "Task clock stopped");
          if (theme.equals("light") && scale.equals("1.0")) {
            String id = app.run.id;
            ActivityMonitor monitor =
                addMonitor(AgentChatPreviewActivity.class.getName(), null, false);
            main(app::recreate);
            Activity restored = monitor.waitForActivityWithTimeout(8000);
            removeMonitor(monitor);
            check(restored instanceof AgentChatPreviewActivity, "Activity did not restore");
            app = (AgentChatPreviewActivity) restored;
            waitForIdleSync();
            check(
                app.run.id.equals(id) && app.run.snapshot().getLong("elapsed_ms") >= elapsed,
                "Task reset after Activity recreation");
          }
          AgentChatPreviewActivity active = app;
          main(
              () -> {
                View stop = findDescription(active.getWindow().getDecorView(), "Остановить запрос");
                check(stop != null && stop.performClick(), "Stop control missing");
                check(
                    active.run.snapshot().optString("state").equals("cancelled"),
                    "Stop did not update task state");
              });
          main(active::showReport);
          waitForIdleSync();
          SystemClock.sleep(220);
          main(active::document);
          waitForIdleSync();
          SystemClock.sleep(250);
          check(accessible("Сопоставление"), "Markdown heading not rendered");
          check(accessible("Стало"), "Table header missing");
          capture(directory, theme, scale, "document");
          clickAccessible("Markdown");
          SystemClock.sleep(220);
          check(accessible("# Отчёт"), "Markdown source unavailable");
          capture(directory, theme, scale, "source");
          clickAccessible("Закрыть");
          SystemClock.sleep(200);
          main(() -> active.openDiff(""));
          waitForIdleSync();
          SystemClock.sleep(250);
          check(accessible("Изменения"), "Diff tabs missing");
          capture(directory, theme, scale, "diff");
          clickAccessible("После");
          SystemClock.sleep(150);
          check(accessible("Строка отчёта 150"), "Full after-version truncated");
          clickAccessible("Закрыть");
          SystemClock.sleep(200);
          main(active::showEmpty);
          waitForIdleSync();
          main(
              () -> {
                EditText edit = input(active.getWindow().getDecorView());
                edit.requestFocus();
                edit.performClick();
              });
          for (int i = 0; i < 50 && !imeVisible(active); i++) SystemClock.sleep(100);
          check(imeVisible(active), "Keyboard did not open");
          main(
              () -> {
                View root = active.getWindow().getDecorView();
                View send = findDescription(root, "Отправить");
                check(send != null, "Send control missing");
                Rect visible = new Rect();
                check(send.getGlobalVisibleRect(visible), "Composer hidden by keyboard");
                Rect window = new Rect();
                root.getWindowVisibleDisplayFrame(window);
                check(
                    visible.bottom <= window.bottom + 4 && visible.height() >= send.getHeight() - 4,
                    "Send control overlaps keyboard");
              });
          // Insets can become visible before the IME publishes its first rendered buffer.
          SystemClock.sleep(1500);
          capture(directory, theme, scale, "keyboard");
          shell("input keyevent 4");
          main(active::finish);
          app = null;
          waitForIdleSync();
        }
      result.putString(
          "result",
          "PASS: agent chat UI; storage migration; native tools; 3 themes; 2 font scales; timer;"
              + " recreation; composer; Markdown; diff; IME");
    } catch (Throwable e) {
      android.util.Log.e("AndroidRuntime", "Agent chat UI failed", e);
      result.putString("result", "FAIL: " + e);
      status = -1;
    } finally {
      if (app != null) {
        Activity current = app;
        main(current::finish);
      }
    }
    finish(status, result);
  }

  private void storage() throws Exception {
    File path = getTargetContext().getDatabasePath("git-agent-chat-archive.db");
    path.getParentFile().mkdirs();
    android.database.sqlite.SQLiteDatabase db =
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null);
    if (db.getVersion() == 0) {
      db.execSQL(
          "CREATE TABLE archived_messages(id TEXT PRIMARY KEY,owner TEXT NOT NULL,thread TEXT NOT"
              + " NULL,at INTEGER NOT NULL,role TEXT NOT NULL,body TEXT NOT NULL,operations TEXT"
              + " NOT NULL DEFAULT '[]',attachments TEXT NOT NULL DEFAULT '[]')");
      db.execSQL(
          "INSERT INTO archived_messages(id,owner,thread,at,role,body)"
              + " VALUES('old','migration','old-thread',1,'agent','preserved legacy message')");
      db.setVersion(3);
    }
    db.close();
    AgentConversationArchive archive = new AgentConversationArchive(getTargetContext());
    check(
        archive
            .read("migration", "old-thread", 10)
            .getJSONObject(0)
            .getString("body")
            .equals("preserved legacy message"),
        "Archive migration lost history");
    archive.close();
    AgentProjectStore store = new AgentProjectStore(getTargetContext());
    String owner = "storage-preview", thread = store.newConversation(owner);
    String longReply = "  Длинный отчёт  \n\n" + "данные ".repeat(1800) + "\n\n";
    store.appendToConversation(owner, thread, "agent", longReply);
    check(
        store.messages(owner, thread).getJSONObject(0).getString("body").equals(longReply),
        "Long Markdown report changed during storage");
    AgentRun run = new AgentRun(owner, thread, SystemClock::elapsedRealtime);
    run.model("fixture");
    run.finish("succeeded", "Готово");
    store.upsertRun(owner, thread, run.snapshot());
    for (int i = 0; i < 81; i++)
      store.appendToConversation(owner, thread, "user", "Archive fixture " + i);
    JSONArray history = store.fullHistory(owner, thread, 1000);
    boolean found = false;
    for (int i = 0; i < history.length(); i++) {
      JSONObject r = history.getJSONObject(i).optJSONObject("run");
      if (r != null && run.id.equals(r.optString("id"))) found = true;
    }
    check(found, "Archived task metadata lost");
    AgentDiffStore diffs = new AgentDiffStore(getTargetContext());
    String removed = diffs.save(owner, thread, "removed.txt", "old", "new");
    String otherThread = store.newConversation(owner);
    String retained = diffs.save(owner, otherThread, "retained.txt", null, "keep");
    store.deleteConversation(owner, thread);
    AgentProjectStore lateWorker = new AgentProjectStore(getTargetContext());
    lateWorker.upsertRun(owner, thread, run.snapshot());
    lateWorker.appendToConversation(owner, thread, "agent", "late response");
    check(
        store.messages(owner, thread).length() == 0,
        "Late worker resurrected deleted conversation history");
    boolean unreadable = false, lateWriteRejected = false;
    try {
      new AgentDiffStore(getTargetContext()).read(owner, thread, removed);
    } catch (IllegalStateException expected) {
      unreadable = true;
    }
    try {
      new AgentDiffStore(getTargetContext()).save(owner, thread, "late.txt", null, "late");
    } catch (IllegalStateException expected) {
      lateWriteRejected = true;
    }
    check(unreadable && lateWriteRejected, "Deleted conversation diff was retained or resurrected");
    check(
        diffs.read(owner, otherThread, retained).getString("after").equals("keep"),
        "Deleting a conversation affected another conversation");
    File[] snapshotFiles = new File(getTargetContext().getFilesDir(), "agent-diffs").listFiles();
    boolean removedFileExists = false;
    if (snapshotFiles != null)
      for (File directory : snapshotFiles)
        if (new File(directory, removed + ".json").exists()) removedFileExists = true;
    check(!removedFileExists, "Deleted snapshot bytes remain on disk");
    store.deleteConversation(owner, otherThread);
  }

  public void verifyNativeTools(AgentChatPreviewActivity activity) throws Exception {
    AgentProjectStore store = new AgentProjectStore(getTargetContext());
    WorkspaceHub workspace = new WorkspaceHub(getTargetContext());
    String owner = "tools-preview-" + java.util.UUID.randomUUID(),
        thread = store.activeConversation(owner);
    AgentRun run = new AgentRun(owner, thread, SystemClock::elapsedRealtime);
    NativeAgentToolHost host =
        new NativeAgentToolHost(
                activity,
                null,
                null,
                workspace,
                store,
                owner,
                thread,
                store.project(owner),
                false,
                true,
                false,
                () -> AgentPermissionPolicy.LOCAL_AUTO,
                () -> {},
                () -> {})
            .withRun(run);
    check(!host.catalog().contains("\nLOCAL READ"), "Unavailable Workspace tools advertised");
    java.util.concurrent.atomic.AtomicInteger turns =
        new java.util.concurrent.atomic.AtomicInteger();
    String reply =
        ProjectRuntime.analyze(
            prompt -> {
              int turn = turns.getAndIncrement();
              if (turn == 0)
                return new JSONObject()
                    .put(
                        "tools",
                        new JSONArray()
                            .put(
                                new JSONObject()
                                    .put("name", "create_workspace")
                                    .put("args", new JSONObject().put("name", "Fixture"))))
                    .toString();
              if (turn == 1) {
                JSONObject file =
                    new JSONObject()
                        .put("path", "docs/report.md")
                        .put("content", AgentChatPreviewActivity.source());
                JSONObject args =
                    new JSONObject()
                        .put("summary", "Fixture")
                        .put("files", new JSONArray().put(file));
                JSONObject call = new JSONObject().put("name", "write_files").put("args", args);
                return new JSONObject().put("tools", new JSONArray().put(call)).toString();
              }
              return "{\"answer\":\"Готово\"}";
            },
            null,
            workspace,
            owner,
            "",
            "main",
            "",
            "Create fixture",
            "",
            false,
            true,
            () -> {},
            host,
            new ProjectRuntime.Listener() {
              public void round(int n) {
                run.round(n);
              }

              public String started(String name, JSONObject args) {
                return run.begin(name, args);
              }

              public void completed(String id, String state, String error) {
                run.result(id, state, error);
              }
            });
    run.finish("succeeded", "Готово");
    check(reply.equals("Готово"), "Native tool loop incomplete");
    String id = store.project(owner).workspaceId;
    check(
        workspace.readFile(owner, id, "docs/report.md").equals(AgentChatPreviewActivity.source()),
        "Host did not write actual file");
    JSONObject file =
        run.snapshot()
            .getJSONArray("operations")
            .getJSONObject(1)
            .getJSONArray("files")
            .getJSONObject(0);
    check(file.getInt("added") == 150 && file.getInt("removed") == 0, "Host line counts incorrect");
    check(
        new AgentDiffStore(activity)
            .read(owner, thread, file.getString("diff_id"))
            .getString("after")
            .equals(AgentChatPreviewActivity.source()),
        "Diff snapshot differs from actual write");
  }

  private void main(Runnable action) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable e) {
            failure.set(e);
          }
        });
    if (failure.get() != null) throw new AssertionError(failure.get());
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private void shell(String command) throws Exception {
    try (ParcelFileDescriptor p = getUiAutomation().executeShellCommand(command);
        java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(p)) {
      while (in.read() != -1) {}
    }
  }

  private void capture(File dir, String theme, String scale, String mode) throws Exception {
    Bitmap image = getUiAutomation().takeScreenshot();
    check(image != null, "No screenshot");
    try (FileOutputStream out =
        new FileOutputStream(new File(dir, theme + "-" + scale + "-" + mode + ".png"))) {
      check(image.compress(Bitmap.CompressFormat.PNG, 100, out), "PNG failed");
    } finally {
      image.recycle();
    }
  }

  private boolean accessible(String text) {
    AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
    return root != null && !root.findAccessibilityNodeInfosByText(text).isEmpty();
  }

  private void clickAccessible(String text) {
    AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
    check(root != null, "No active window");
    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
      if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
    }
    throw new AssertionError("Action unavailable: " + text);
  }

  private boolean imeVisible(Activity activity) {
    AtomicReference<Boolean> visible = new AtomicReference<>(false);
    main(
        () -> {
          WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
          visible.set(insets != null && insets.isVisible(WindowInsets.Type.ime()));
        });
    return visible.get();
  }

  private static View find(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals(text))
      return view;
    if (view instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) view;
      for (int i = 0; i < g.getChildCount(); i++) {
        View v = find(g.getChildAt(i), text);
        if (v != null) return v;
      }
    }
    return null;
  }

  private static View findDescription(View view, String text) {
    if (text.equals(
        view.getContentDescription() == null ? "" : view.getContentDescription().toString()))
      return view;
    if (view instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) view;
      for (int i = 0; i < g.getChildCount(); i++) {
        View v = findDescription(g.getChildAt(i), text);
        if (v != null) return v;
      }
    }
    return null;
  }

  private static EditText input(View view) {
    return findClass(view, EditText.class);
  }

  private static <T extends View> T findClass(View view, Class<T> type) {
    if (type.isInstance(view)) return type.cast(view);
    if (view instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) view;
      for (int i = 0; i < g.getChildCount(); i++) {
        T found = findClass(g.getChildAt(i), type);
        if (found != null) return found;
      }
    }
    return null;
  }
}
