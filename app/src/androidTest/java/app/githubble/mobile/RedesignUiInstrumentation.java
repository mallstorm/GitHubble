package app.githubble.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opens real app screens with synthetic data, saves full-page images and audits layout. */
public final class RedesignUiInstrumentation extends Instrumentation {
  private final List<String> findings = new ArrayList<>();
  private int shots;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    int code = 0;
    try {
      File dir = getTargetContext().getExternalFilesDir("redesign-ui");
      if (dir == null || (!dir.exists() && !dir.mkdirs())) throw new AssertionError("No output dir");
      Activity app =
          startActivitySync(
              new Intent(getTargetContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      waitForIdleSync();
      for (String name :
          new String[] {
            "showHome", "showMyRepositories", "showSearch", "showNotifications", "showFavorites",
            "showTracking", "showSettings", "showSettingsAccount", "showSettingsInterface",
            "showSettingsNotifications", "showSettingsData", "showSettingsGitAgentWorkspace",
            "showSettingsAbout", "showIssues", "showReleases", "showBuildCenter",
            "showProfile", "showOrganizations"
          }) {
        try {
          call(app, name);
          capture(app, dir, name);
        } catch (Throwable error) {
          findings.add(name + ": render failed " + error);
        }
      }
      try {
        repository(app);
        capture(app, dir, "repository");
        release(app);
        capture(app, dir, "release-card");
      } catch (Throwable error) {
        findings.add("synthetic: render failed " + error);
      }
      String[] synthetic = {"builds", "issues", "search", "projects", "repolist", "people"};
      for (String name : synthetic) {
        try {
          scenario(app, name);
          capture(app, dir, "s-" + name);
        } catch (Throwable error) {
          findings.add("s-" + name + ": render failed " + error);
        }
      }
      try (PrintWriter out = new PrintWriter(new File(dir, "audit.txt"))) {
        out.println("screens captured: " + shots);
        out.println("findings: " + findings.size());
        for (String line : findings) out.println(line);
      }
      result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "PASS: redesign UI shots=" + shots + " findings=" + findings.size() + "\n");
    } catch (Throwable error) {
      code = 1;
      result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "FAIL: " + error + "\n");
    }
    finish(code, result);
  }

  private void call(Activity app, String method, Object... args) throws Throwable {
    Method found = null;
    for (Method candidate : MainActivity.class.getDeclaredMethods())
      if (candidate.getName().equals(method) && candidate.getParameterTypes().length == args.length) found = candidate;
    if (found == null) throw new NoSuchMethodException(method);
    found.setAccessible(true);
    final Method m = found;
    Throwable[] failure = new Throwable[1];
    runOnMainSync(
        () -> {
          try {
            m.invoke(app, args);
          } catch (Throwable error) {
            failure[0] = error.getCause() == null ? error : error.getCause();
          }
        });
    if (failure[0] != null) throw failure[0];
  }

  private void repository(Activity app) throws Throwable {
    JSONObject repo =
        new JSONObject()
            .put("full_name", "mallstorm/OmniCodeIDE")
            .put("private", true)
            .put("description", JSONObject.NULL)
            .put("default_branch", "main");
    call(app, "shell", "mallstorm/OmniCodeIDE", true);
    call(
        app,
        "renderRepositoryOverview",
        "mallstorm/OmniCodeIDE", repo, "Без описания", 3, 1, 4, "main", "Go", "MIT",
        "# OmniCode-IDE\n\nНезависимая десктопная IDE для Windows x64.", Boolean.FALSE);
  }

  private void scenario(Activity app, String name) throws Throwable {
    switch (name) {
      case "builds":
        call(app, "shell", "Центр сборок", false);
        call(app, "repoSectionHeader", "mallstorm/GitHubble", "GitHub Actions", "Сборки и артефакты", drawable(app, "ic_android"));
        call(app, "addRepositoryActionsTabs", "mallstorm/GitHubble", 0);
        onUi(
            () -> {
              try {
                android.widget.LinearLayout host = new android.widget.LinearLayout(app);
                host.setOrientation(android.widget.LinearLayout.VERTICAL);
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("content");
                field.setAccessible(true);
                ((android.widget.LinearLayout) field.get(app)).addView(host);
                JSONArray runs = new JSONArray();
                for (int i = 0; i < 5; i++)
                  runs.put(
                      new JSONObject()
                          .put("id", 100 + i)
                          .put("repository", "mallstorm/GitHubble")
                          .put("name", i == 2 ? "GitHubble Android checks and release with a really long workflow name" : "GitHubble Android checks and release")
                          .put("status", i == 0 ? "in_progress" : "completed")
                          .put("conclusion", i == 3 ? "failure" : "success")
                          .put("head_branch", "claude/redesign-2026-11")
                          .put("head_sha", "d46c0e9a1b2c3d4e5f60718293a4b5c6d7e8f901")
                          .put("run_started_at", "2026-11-12T06:30:00Z")
                          .put("artifacts", i == 1 ? new JSONArray().put(new JSONObject().put("created_at", "2026-11-12T06:44:00Z")) : new JSONArray()));
                invoke(app, "renderBuildRows", host, runs, "");
              } catch (Throwable error) {
                throw new RuntimeException(error);
              }
            });
        break;
      case "issues":
        call(app, "shell", "Задачи", true);
        for (int i = 0; i < 3; i++)
          call(
              app,
              "renderGlobalIssueCard",
              new JSONObject()
                  .put("number", 30 + i)
                  .put("title", i == 1 ? "Очень длинный заголовок задачи, который не помещается в одну строку и должен аккуратно переноситься на несколько строк в карточке" : "Кнопка отправки остаётся неактивной")
                  .put("repository_url", "https://api.github.com/repos/mallstorm/GitHubble")
                  .put("updated_at", "2026-11-12T06:44:33Z")
                  .put("user", new JSONObject().put("login", "mallstorm")),
              i == 2);
        break;
      case "search":
        call(app, "shell", "Поиск", false);
        call(app, "renderAdvancedIssueCard", new JSONObject().put("number", 7).put("title", "Fix tracking limits").put("repository_url", "https://api.github.com/repos/mallstorm/GitHubble").put("updated_at", "2026-11-12T06:44:33Z").put("pull_request", new JSONObject()).put("user", new JSONObject().put("login", "mallstorm")), 2);
        call(app, "renderCodeSearchCard", new JSONObject().put("name", "MainActivity.java").put("path", "app/src/main/java/app/githubble/mobile/MainActivity.java").put("repository", new JSONObject().put("full_name", "mallstorm/GitHubble")), 1);
        call(app, "renderPeopleSearchCard", new JSONObject().put("login", "mallstorm").put("type", "User"), 1);
        call(app, "renderPeopleSearchCard", new JSONObject().put("login", "anthropics").put("type", "Organization"), 3);
        break;
      case "projects":
        call(app, "shell", "Проекты", true);
        call(app, "renderProjectCard", new JSONObject().put("title", "GitHubble roadmap").put("shortDescription", "Планирование проходов и приёмка").put("closed", false).put("items", new JSONObject().put("totalCount", 14)).put("updatedAt", "2026-11-12T06:44:33Z"));
        call(app, "renderProjectCard", new JSONObject().put("title", "Архивный проект").put("closed", true).put("items", new JSONObject().put("totalCount", 0)).put("updatedAt", "2026-10-01T06:44:33Z"));
        call(app, "renderDiscussionGraphQlCard", new JSONObject().put("repository", "mallstorm/GitHubble").put("number", 3).put("title", "Как настроить Git Agent").put("category", new JSONObject().put("name", "Q&A").put("emoji", "🙏")).put("author", new JSONObject().put("login", "mallstorm")).put("comments", new JSONObject().put("totalCount", 4)));
        break;
      case "repolist":
        call(app, "shell", "Репозитории", true);
        for (int i = 0; i < 2; i++)
          call(app, "addRepoCard", new JSONObject().put("full_name", "mallstorm/GitHubble").put("description", i == 0 ? "Android-клиент GitHub с агентом разработки" : JSONObject.NULL).put("language", "Java").put("stargazers_count", 12), 0);
        onUi(
            () -> {
              try {
                android.widget.GridLayout grid = new android.widget.GridLayout(app);
                grid.setColumnCount(2);
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("content");
                field.setAccessible(true);
                ((android.widget.LinearLayout) field.get(app)).addView(grid);
                java.lang.reflect.Method tile = MainActivity.class.getDeclaredMethod("addRepoTile", android.widget.GridLayout.class, JSONObject.class);
                tile.setAccessible(true);
                for (int i = 0; i < 4; i++)
                  tile.invoke(app, grid, new JSONObject().put("full_name", "mallstorm/Repo" + i).put("private", i % 2 == 0).put("description", "Описание репозитория номер " + i).put("language", "Kotlin").put("stargazers_count", i));
              } catch (Throwable error) {
                throw new RuntimeException(error);
              }
            });
        break;
      case "people":
        call(app, "shell", "Организации", true);
        call(app, "eventCard", "mallstorm открыл Pull Request #36", "mallstorm/GitHubble", "12.11.2026 09:30", 0xFF2E9B46);
        call(app, "addDirectionRow", drawable(app, "ic_release"), "Релизы", "Версии и сборки", 0xFFE87D1E, (Runnable) () -> {});
        call(app, "heroBanner", "Организации", "Команды и доступ", drawable(app, "ic_organization"));
        break;
      default:
        break;
    }
  }

  private static void invoke(Activity app, String method, Object... args) throws Exception {
    for (Method candidate : MainActivity.class.getDeclaredMethods())
      if (candidate.getName().equals(method) && candidate.getParameterTypes().length == args.length) {
        candidate.setAccessible(true);
        candidate.invoke(app, args);
        return;
      }
    throw new NoSuchMethodException(method);
  }

  private static int drawable(Activity app, String name) {
    return app.getResources().getIdentifier(name, "drawable", app.getPackageName());
  }

  private void onUi(Runnable action) throws Throwable {
    Throwable[] failure = new Throwable[1];
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure[0] = error.getCause() == null ? error : error.getCause();
          }
        });
    if (failure[0] != null) throw failure[0];
  }

  private void release(Activity app) throws Throwable {
    call(app, "shell", "Релизы", true);
    JSONArray assets =
        new JSONArray()
            .put(asset(1, "release.apk", 21_800_000L, "application/vnd.android.package-archive"))
            .put(asset(2, "githubble-sources-with-a-very-long-file-name-1.0.0-rc4.2.31.zip", 4_200_000L, "application/zip"))
            .put(asset(3, "checksums.txt", 812L, "text/plain"));
    JSONObject release =
        new JSONObject()
            .put("id", 11)
            .put("name", "GitHubble RC 4.2.31")
            .put("tag_name", "build-1.0.0-rc4.2.31")
            .put("prerelease", true)
            .put("published_at", "2026-11-12T06:44:33Z")
            .put("body", "Исправления кнопки отправки и скрытие служебного JSON.")
            .put("assets", assets);
    call(app, "renderReleaseCard", release);
    call(app, "renderReleaseCard", new JSONObject(release.toString()).put("id", 12).put("name", "Второй релиз").put("prerelease", false));
  }

  private static JSONObject asset(long id, String name, long size, String type) throws Exception {
    return new JSONObject()
        .put("id", id)
        .put("name", name)
        .put("size", size)
        .put("content_type", type)
        .put("url", "https://api.github.com/repos/o/r/releases/assets/" + id);
  }

  private void capture(Activity app, File dir, String name) throws Exception {
    waitForIdleSync();
    android.os.SystemClock.sleep(500);
    Bitmap[] holder = new Bitmap[1];
    runOnMainSync(
        () -> {
          View decor = app.getWindow().getDecorView();
          audit(name, decor, decor);
          ScrollView scroll = findScroll(decor);
          View target = scroll != null && scroll.getChildCount() > 0 ? scroll.getChildAt(0) : decor;
          int width = Math.max(1, target.getWidth());
          int height = Math.max(1, Math.min(target.getHeight(), 7000));
          Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
          Canvas canvas = new Canvas(bitmap);
          int bg = Color.rgb(246, 248, 250);
          if (scroll != null && scroll.getParent() instanceof View) {
            android.graphics.drawable.Drawable d = ((View) scroll.getParent()).getBackground();
            if (d instanceof ColorDrawable) bg = ((ColorDrawable) d).getColor();
          }
          canvas.drawColor(bg);
          target.draw(canvas);
          holder[0] = bitmap;
        });
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      holder[0].compress(Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      holder[0].recycle();
    }
    shots++;
  }

  private static ScrollView findScroll(View view) {
    if (view instanceof ScrollView) return (ScrollView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        ScrollView found = findScroll(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private void audit(String screen, View view, View root) {
    float density = view.getResources().getDisplayMetrics().density;
    if (view instanceof TextView) {
      TextView text = (TextView) view;
      if (text.getVisibility() == View.VISIBLE && text.getLayout() != null && text.getMaxLines() == Integer.MAX_VALUE
          && text.getLayout().getHeight() > text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 2)
        findings.add(screen + ": TEXT CLIPPED \"" + shorten(text.getText()) + "\"");
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View a = group.getChildAt(i);
        if (a.getVisibility() != View.VISIBLE) continue;
        if (a.getRight() > group.getWidth() - group.getPaddingRight() + 2 && !(group instanceof android.widget.HorizontalScrollView) && group.getWidth() > 0)
          findings.add(screen + ": OVERFLOW " + describe(a));
        for (int j = i + 1; j < group.getChildCount(); j++) {
          View b = group.getChildAt(j);
          if (b.getVisibility() != View.VISIBLE || !surface(a) || !surface(b)) continue;
          Rect ra = new Rect(a.getLeft(), a.getTop(), a.getRight(), a.getBottom());
          Rect rb = new Rect(b.getLeft(), b.getTop(), b.getRight(), b.getBottom());
          int vOverlap = Math.min(ra.bottom, rb.bottom) - Math.max(ra.top, rb.top);
          int hOverlap = Math.min(ra.right, rb.right) - Math.max(ra.left, rb.left);
          int minH = Math.min(ra.height(), rb.height());
          int minW = Math.min(ra.width(), rb.width());
          int hGap = Math.max(rb.left - ra.right, ra.left - rb.right);
          int vGap = Math.max(rb.top - ra.bottom, ra.top - rb.bottom);
          float limit = 4 * density;
          if (vOverlap > minH / 2 && hGap < limit)
            findings.add(screen + ": TOUCHING " + describe(a) + " | " + describe(b) + " gap=" + hGap + "px");
          else if (hOverlap > minW / 2 && vGap < limit && vGap >= -1)
            findings.add(screen + ": STACKED-TIGHT " + describe(a) + " | " + describe(b) + " gap=" + vGap + "px");
        }
        audit(screen, a, root);
      }
    }
  }

  private static boolean surface(View view) {
    return view.getBackground() != null && (view instanceof Button || view.isClickable());
  }

  private static String describe(View view) {
    String text = view instanceof TextView ? shorten(((TextView) view).getText()) : "";
    if (text.isEmpty() && view instanceof ViewGroup) text = firstText((ViewGroup) view);
    return view.getClass().getSimpleName() + "[" + text + "]";
  }

  private static String firstText(ViewGroup group) {
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child instanceof TextView && ((TextView) child).getText().length() > 0) return shorten(((TextView) child).getText());
      if (child instanceof ViewGroup) {
        String nested = firstText((ViewGroup) child);
        if (!nested.isEmpty()) return nested;
      }
    }
    return "";
  }

  private static String shorten(CharSequence value) {
    String s = String.valueOf(value).replace('\n', ' ');
    return s.length() > 40 ? s.substring(0, 40) + "…" : s;
  }
}
