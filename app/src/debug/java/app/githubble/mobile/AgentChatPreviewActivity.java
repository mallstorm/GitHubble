package app.githubble.mobile;

import android.os.Bundle;
import android.os.SystemClock;
import android.widget.LinearLayout;
import app.githubble.mobile.agent.*;
import app.githubble.mobile.ui.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only synthetic previews of production Views and the app shell, on a clean test device. */
public final class AgentChatPreviewActivity extends MainActivity {
  public static final String OWNER = "preview-owner", THREAD = "preview-thread";
  public static final String REPORT =
      "# Отчёт по проекту\n\n"
          + "Подготовлен **проверяемый документ**.\n\n"
          + "## Сопоставление\n\n"
          + "| Раздел | Было | Стало |\n"
          + "| --- | --- | --- |\n"
          + "| История | Сообщения | Лента действий |\n"
          + "| Файлы | Текст | +150 −0 и сравнение |\n\n"
          + "## Проверки\n\n"
          + "- [x] Файлы прочитаны\n"
          + "- [ ] Проверить на телефоне\n\n"
          + "> Локальная запись и публикация GitHub — отдельные операции.\n\n"
          + "```java\n"
          + "if (operation.succeeded()) {\n"
          + "  showFileChange(operation);\n"
          + "}\n"
          + "```\n";
  public AgentRun run;
  public AgentChatScreen screen;
  private HomeScreen.Palette palette;
  private String diffId = "";
  private boolean empty = true;
  private JSONArray messages = new JSONArray();

  @Override
  public void onCreate(Bundle state) {
    if (!new app.githubble.mobile.security.SecureStore(this).get("github_token").isEmpty())
      throw new IllegalStateException("Preview requires a clean test profile");
    getSharedPreferences("githubble", MODE_PRIVATE)
        .edit()
        .putString("theme", getIntent().getStringExtra("theme"))
        .commit();
    super.onCreate(null);
    try {
      Method p = MainActivity.class.getDeclaredMethod("settingsPalette");
      p.setAccessible(true);
      palette = (HomeScreen.Palette) p.invoke(this);
      if (state != null && state.getBoolean("preview_active")) {
        run = AgentRunRegistry.get(OWNER, THREAD);
        showActive();
      } else showEmpty();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putBoolean("preview_active", !empty);
    super.onSaveInstanceState(state);
  }

  public void showEmpty() {
    empty = true;
    render(new JSONArray(), false);
  }

  public void showActive() {
    empty = false;
    try {
      if (run == null || !run.active()) {
        AtomicLong offset = new AtomicLong();
        run = new AgentRun(OWNER, THREAD, () -> SystemClock.elapsedRealtime() + offset.get());
        run.model("Demo model");
        run.round(1);
        for (int i = 0; i < 16; i++) {
          String op =
              run.begin("read_file", new JSONObject().put("path", "src/Module" + i + ".java"));
          offset.addAndGet(120);
          run.result(op, "succeeded", "");
        }
        run.round(2);
        String write = run.begin("write_files", new JSONObject());
        String source = source();
        diffId = new AgentDiffStore(this).save(OWNER, THREAD, "docs/report.md", null, source);
        FileChangeStats stats = FileChangeStats.between(null, source);
        run.files(
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("path", "docs/report.md")
                        .put("kind", "create")
                        .put("added", stats.added)
                        .put("removed", stats.removed)
                        .put("diff_id", diffId)),
            true);
        offset.addAndGet(41000);
        run.result(write, "succeeded", "");
        run.round(3);
        run.begin("search_files", new JSONObject().put("query", "TODO"));
        offset.set(134000);
        AgentRunRegistry.put(run);
      }
      messages =
          new JSONArray()
              .put(
                  new JSONObject()
                      .put("role", "user")
                      .put("at", 1)
                      .put("body", "Проанализируй проект и оформи отчёт"))
              .put(
                  new JSONObject()
                      .put("role", "system")
                      .put("at", 2)
                      .put("body", "Работа агента")
                      .put("run", run.snapshot()));
      render(messages, true);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void showReport() {
    try {
      if (run == null || !run.active()) {
        run = null;
        showActive();
      }
      run.finish("succeeded", "Задача завершена");
      messages =
          new JSONArray()
              .put(
                  new JSONObject()
                      .put("role", "system")
                      .put("at", 2)
                      .put("body", "Работа агента")
                      .put("run", run.snapshot()))
              .put(new JSONObject().put("role", "agent").put("at", 3).put("body", REPORT));
      render(messages, false);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void document() {
    AgentDocumentSheet.document(
        this,
        REPORT,
        palette.text,
        palette.muted,
        palette.green,
        palette.subtle,
        palette.border,
        () -> {});
  }

  public void openDiff(String id) {
    try {
      if (id == null || id.isEmpty()) {
        JSONObject snap = run.snapshot();
        JSONArray ops = snap.getJSONArray("operations");
        for (int i = 0; i < ops.length(); i++)
          if (ops.getJSONObject(i).optJSONArray("files") != null)
            id = ops.getJSONObject(i).getJSONArray("files").getJSONObject(0).getString("diff_id");
      }
      JSONObject d = new AgentDiffStore(this).read(OWNER, THREAD, id);
      AgentDocumentSheet.diff(
          this,
          d.getString("path"),
          d.getString("before"),
          d.getString("after"),
          palette.text,
          palette.muted,
          palette.green,
          palette.border);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void refreshFixture() {
    try {
      messages.getJSONObject(1).put("run", run.snapshot());
      screen.refresh(messages, true);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void render(JSONArray entries, boolean busy) {
    try {
      Method shell = MainActivity.class.getDeclaredMethod("shell", String.class, boolean.class);
      shell.setAccessible(true);
      shell.invoke(this, "Чат", false);
      Field content = MainActivity.class.getDeclaredField("content");
      content.setAccessible(true);
      LinearLayout host = (LinearLayout) content.get(this);
      AgentChatScreen.Actions actions =
          (AgentChatScreen.Actions)
              Proxy.newProxyInstance(
                  AgentChatScreen.Actions.class.getClassLoader(),
                  new Class[] {AgentChatScreen.Actions.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("activeRun"))
                      return run == null ? null : run.snapshot();
                    if (method.getName().equals("openDiff")) openDiff((String) args[0]);
                    if (method.getName().equals("cancelRequest") && run != null) {
                      run.finish("cancelled", "Остановлено пользователем");
                      screen.refresh(messages, false);
                    }
                    return null;
                  });
      screen =
          new AgentChatScreen(
              this,
              host,
              palette.background,
              palette.card,
              palette.subtle,
              palette.text,
              palette.muted,
              palette.green,
              palette.border,
              actions);
      screen.render(
          "preview",
          new AgentProjectStore.Project(
              OWNER,
              empty ? "" : "Демо-проект",
              empty ? "" : "demo/project",
              "main",
              empty ? "" : "demo-workspace"),
          entries,
          1,
          true,
          "Demo provider",
          "Demo model",
          true,
          "Новый диалог",
          busy,
          "");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String source() {
    StringBuilder s = new StringBuilder();
    for (int i = 1; i <= 150; i++) s.append("Строка отчёта ").append(i).append('\n');
    return s.toString();
  }

  @Override
  protected void onDestroy() {
    if (!isChangingConfigurations() && run != null && run.active())
      run.finish("interrupted", "Предпросмотр закрыт");
    super.onDestroy();
  }
}
