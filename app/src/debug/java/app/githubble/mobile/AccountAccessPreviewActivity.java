package app.githubble.mobile;

import android.os.Bundle;
import android.widget.LinearLayout;
import app.githubble.mobile.ui.AccountAccessScreen;
import app.githubble.mobile.ui.HomeScreen;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Debug-only synthetic fixtures. Uses the production shell and renderer without GitHub credentials.
 */
public final class AccountAccessPreviewActivity extends MainActivity {
  @Override
  public void onCreate(Bundle state) {
    if (!new app.githubble.mobile.security.SecureStore(this).get("github_token").isEmpty())
      throw new IllegalStateException("Preview requires a clean test device");
    getSharedPreferences("githubble", MODE_PRIVATE)
        .edit()
        .putString("theme", getIntent().getStringExtra("theme"))
        .commit();
    super.onCreate(null);
    try {
      render(0);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JSONObject fixture(long id) throws Exception {
    JSONObject permissions =
        new JSONObject()
            .put("contents", "write")
            .put("issues", "write")
            .put("pull_requests", "write")
            .put("actions", "write")
            .put("metadata", "read")
            .put("secrets", "write")
            .put("workflows", "write")
            .put("discussions", "write")
            .put("checks", "read")
            .put("pages", "write")
            .put("administration", "write")
            .put("deployments", "write");
    return new JSONObject()
        .put("id", id)
        .put(
            "account",
            new JSONObject()
                .put("login", id == 101 ? "demo-user" : "example-maintainer")
                .put("type", "User"))
        .put("repository_selection", id == 101 ? "all" : "selected")
        .put("suspended_at", JSONObject.NULL)
        .put("permissions", permissions);
  }

  private void render(long id) throws Exception {
    Method shell = MainActivity.class.getDeclaredMethod("shell", String.class, boolean.class);
    shell.setAccessible(true);
    shell.invoke(this, id == 0 ? "Управление аккаунтом" : "Права установки", false);
    Field field = MainActivity.class.getDeclaredField("content");
    field.setAccessible(true);
    LinearLayout content = (LinearLayout) field.get(this);
    Method palette = MainActivity.class.getDeclaredMethod("settingsPalette");
    palette.setAccessible(true);
    AccountAccessScreen.Actions actions =
        new AccountAccessScreen.Actions() {
          public void blocks() {}

          public void limits() {}

          public void permissions() {}

          public void authorize() {}

          public void refresh() {
            installation(0);
          }

          public void installation(long selected) {
            try {
              render(selected);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
        };
    AccountAccessScreen screen =
        new AccountAccessScreen(this, content, (HomeScreen.Palette) palette.invoke(this), actions);
    if (id == 0) {
      screen.overview("demo-user");
      screen.installations(new JSONArray().put(fixture(101)).put(fixture(102)));
    } else screen.detail(fixture(id));
  }
}
