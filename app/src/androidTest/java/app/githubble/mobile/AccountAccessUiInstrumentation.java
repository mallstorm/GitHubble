package app.githubble.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;

public final class AccountAccessUiInstrumentation extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    Activity app = null;
    int code = 0;
    try {
      File dir = getTargetContext().getExternalFilesDir("account-ui");
      if (dir == null || (!dir.exists() && !dir.mkdirs()))
        throw new AssertionError("Screenshot directory unavailable");
      for (String theme : new String[] {"light", "dark", "beige"})
        for (String scale : new String[] {"1.0", "1.5"}) {
          try (ParcelFileDescriptor p =
                  getUiAutomation().executeShellCommand("settings put system font_scale " + scale);
              java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(p)) {
            while (in.read() != -1) {}
          }
          for (int i = 0;
              i < 100
                  && Math.abs(
                          getTargetContext().getResources().getConfiguration().fontScale
                              - Float.parseFloat(scale))
                      > .05f;
              i++) SystemClock.sleep(50);
          if (Math.abs(
                  getTargetContext().getResources().getConfiguration().fontScale
                      - Float.parseFloat(scale))
              > .05f) throw new AssertionError("Font scale did not apply");
          app =
              startActivitySync(
                  new Intent()
                      .setClassName(
                          getTargetContext().getPackageName(),
                          "app.githubble.mobile.AccountAccessPreviewActivity")
                      .putExtra("theme", theme)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
          waitForIdleSync();
          SystemClock.sleep(500);
          Activity current = app;
          runOnMainSync(
              () -> {
                if (find(current.getWindow().getDecorView(), "Подключение GitHub") == null)
                  throw new AssertionError("Unified settings groups missing");
                assertNoJson(current.getWindow().getDecorView());
              });
          capture(new File(dir, theme + "-" + scale + "-overview.png"));
          runOnMainSync(
              () -> {
                View row = find(current.getWindow().getDecorView(), "@example-maintainer");
                if (row == null) throw new AssertionError("Installation missing");
                row.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, row.getWidth(), row.getHeight()), true);
              });
          waitForIdleSync();
          SystemClock.sleep(250);
          capture(new File(dir, theme + "-" + scale + "-installations.png"));
          runOnMainSync(
              () -> {
                View row = find(current.getWindow().getDecorView(), "@example-maintainer");
                if (row == null) throw new AssertionError("Installation row missing");
                while (row != null && !row.isClickable())
                  row = row.getParent() instanceof View ? (View) row.getParent() : null;
                if (row == null || !row.performClick())
                  throw new AssertionError("Installation row not actionable");
              });
          waitForIdleSync();
          SystemClock.sleep(250);
          runOnMainSync(
              () -> {
                if (find(current.getWindow().getDecorView(), "Файлы, коммиты и релизы") == null)
                  throw new AssertionError("Readable permission missing");
                assertNoJson(current.getWindow().getDecorView());
              });
          capture(new File(dir, theme + "-" + scale + "-permissions.png"));
          runOnMainSync(current::finish);
          app = null;
          waitForIdleSync();
        }
      result.putString(
          "result",
          "PASS: account access UI, 3 themes, 2 font scales, readable permissions and navigation");

    } catch (Throwable e) {
      android.util.Log.e("AndroidRuntime", "Account access UI failed", e);
      result.putString("result", "FAIL: " + e);
      code = -1;
    } finally {
      if (app != null) {
        Activity current = app;
        runOnMainSync(current::finish);
      }
    }
    finish(code, result);
  }

  private void capture(File file) throws Exception {
    Bitmap shot = getUiAutomation().takeScreenshot();
    if (shot == null) throw new AssertionError("No screenshot");
    try (FileOutputStream out = new FileOutputStream(file)) {
      if (!shot.compress(Bitmap.CompressFormat.PNG, 100, out))
        throw new AssertionError("PNG write failed");
    } finally {
      shot.recycle();
    }
  }

  private static View find(View v, String text) {
    if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return v;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        View r = find(g.getChildAt(i), text);
        if (r != null) return r;
      }
    }
    return null;
  }

  private static void assertNoJson(View v) {
    if (v instanceof TextView && ((TextView) v).getText().toString().contains("\"contents\""))
      throw new AssertionError("Raw JSON in user interface");
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) assertNoJson(g.getChildAt(i));
    }
  }
}
