package app.githubble.mobile;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import app.githubble.mobile.agent.OmniChatConnectionStore;
import app.githubble.mobile.security.SecureStore;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Real production picker: keyboard, filters, scrolling, 80/110% display size and 150% fonts. */
final class ModelPickerUiChecks {
  static void run(Instrumentation host) throws Exception {
    if (!new SecureStore(host.getTargetContext()).get("github_token").isEmpty())
      throw new AssertionError("Clean test profile required");
    File shots = host.getTargetContext().getExternalFilesDir("model-picker-ui");
    if (shots == null || !shots.exists() && !shots.mkdirs())
      throw new AssertionError("Screenshot directory unavailable");
    for (String theme : new String[] {"light", "dark", "beige"})
      for (int display : new int[] {80, 110})
        for (String font : new String[] {"1.0", "1.5"}) {
          shell(host, "settings put system font_scale " + font);
          shell(host, "settings put secure show_ime_with_hard_keyboard 1");
          host.getTargetContext()
              .getSharedPreferences("githubble", 0)
              .edit()
              .putString("theme", theme)
              .putInt("ui_display_scale", display)
              .commit();
          SystemClock.sleep(150);
          MainActivity activity =
              (MainActivity)
                  host.startActivitySync(
                      new Intent(host.getTargetContext(), MainActivity.class)
                          .addFlags(
                              Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
          AtomicReference<Dialog> active = new AtomicReference<>();
          try {
            main(
                host,
                () -> {
                  try {
                    Field field = MainActivity.class.getDeclaredField("aiConnections");
                    field.setAccessible(true);
                    OmniChatConnectionStore store = (OmniChatConnectionStore) field.get(activity);
                    store.save("guest", "openai", "fixture-model-0", "fixture-never-sent");
                    store.cacheModels(
                        "guest",
                        "openai",
                        Arrays.asList(
                            "fixture-model-0",
                            "fixture-model-1",
                            "fixture-model-2",
                            "fixture-model-3",
                            "fixture-model-4",
                            "fixture-model-5",
                            "fixture-model-6",
                            "fixture-model-7",
                            "fixture-model-8",
                            "fixture-model-9"));
                    Method method =
                        MainActivity.class.getDeclaredMethod(
                            "agentChooseComposerModel", String.class);
                    method.setAccessible(true);
                    active.set((Dialog) method.invoke(activity, "guest"));
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                });
            host.waitForIdleSync();
            SystemClock.sleep(350);
            Dialog dialog = active.get();
            check(dialog != null, "Picker was not opened");
            View root = dialog.getWindow().getDecorView();
            AtomicReference<Rect> heading = new AtomicReference<>();
            main(
                host,
                () -> {
                  View title = find(root, "Выбор модели"), tile = find(root, "Добавить подписку");
                  fullyVisible(title, "Title clipped");
                  fullyVisible(tile, "Tile caption clipped");
                  TextView caption = (TextView) tile;
                  check(
                      caption.getLayout() != null
                          && caption.getLayout().getLineBottom(caption.getLineCount() - 1)
                              <= caption.getHeight()
                                  - caption.getCompoundPaddingBottom()
                                  - caption.getCompoundPaddingTop(),
                      "Subscription caption loses lines");
                  heading.set(bounds(title));
                  ScrollView scroll = findClass(root, ScrollView.class);
                  check(scroll != null, "Model scroll missing");
                  scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                });
            host.waitForIdleSync();
            main(
                host,
                () ->
                    check(
                        bounds(find(root, "Выбор модели")).equals(heading.get()),
                        "Scrolling models moved the fixed title"));
            main(
                host,
                () -> {
                  View filter = find(root, "API");
                  check(filter != null, "API filter missing");
                  filter.performClick();
                  EditText search = findClass(root, EditText.class);
                  search.setText("fixture-model-1");
                  search.requestFocus();
                  ((InputMethodManager) activity.getSystemService("input_method"))
                      .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
                });
            for (int i = 0; i < 300 && !imeVisible(dialog); i++) SystemClock.sleep(100);
            check(imeVisible(dialog), "Search keyboard did not open");
            host.waitForIdleSync();
            SystemClock.sleep(300);
            main(
                host,
                () -> {
                  View title = find(root, "Выбор модели"),
                      search = findClass(root, EditText.class),
                      filter = find(root, "API");
                  fullyVisible(title, "Keyboard hid title");
                  fullyVisible(search, "Keyboard hid search");
                  fullyVisible(filter, "Keyboard hid filter");
                  ScrollView scroll = findClass(root, ScrollView.class);
                  Rect viewport = bounds(scroll);
                  check(viewport.top >= bounds(filter).bottom, "Model cards overlap filter row");
                  check(viewport.height() > 0, "Keyboard removed entire model viewport");
                  Rect window = new Rect();
                  root.getWindowVisibleDisplayFrame(window);
                  check(
                      viewport.bottom <= window.bottom + 2,
                      "Model list extends underneath keyboard");
                  check(find(root, "fixture-model-1") != null, "Search/filter lost matching model");
                });
            Bitmap image = host.getUiAutomation().takeScreenshot();
            check(image != null, "Screenshot unavailable");
            try (FileOutputStream out =
                new FileOutputStream(
                    new File(shots, theme + "-" + display + "-" + font + "-ime.png"))) {
              image.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            image.recycle();
            shell(host, "input keyevent 4");
          } catch(Throwable failure){
            Bitmap screenshot=host.getUiAutomation().takeScreenshot();
            if(screenshot!=null){
              try(FileOutputStream out=new FileOutputStream(new File(shots,"failure.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}
              screenshot.recycle();
            }
            throw failure;
          } finally {
            main(
                host,
                () -> {
                  if (active.get() != null) active.get().dismiss();
                  activity.finish();
                });
            host.waitForIdleSync();
          }
        }
    shell(host, "settings put system font_scale 1.0");
    host.getTargetContext()
        .getSharedPreferences("githubble", 0)
        .edit()
        .putInt("ui_display_scale", 100)
        .commit();
  }

  private static boolean imeVisible(Dialog dialog) {
    WindowInsets insets = dialog.getWindow().getDecorView().getRootWindowInsets();
    return insets != null && insets.isVisible(WindowInsets.Type.ime());
  }

  private static Rect bounds(View view) {
    check(view != null, "Missing view");
    int[] point = new int[2];
    view.getLocationOnScreen(point);
    return new Rect(point[0], point[1], point[0] + view.getWidth(), point[1] + view.getHeight());
  }

  private static void fullyVisible(View view, String message) {
    check(view != null, message);
    Rect visible = new Rect();
    check(
        view.getGlobalVisibleRect(visible)
            && visible.height() >= view.getHeight() - 2
            && visible.width() >= view.getWidth() - 2,
        message+"; bounds="+bounds(view)+"; visible="+visible);
  }

  private static View find(View view, String text) {
    if (view instanceof TextView && text.equals(((TextView)view).getText().toString().replaceFirst("^✓\\s*", ""))) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static <T> T findClass(View view, Class<T> type) {
    if (type.isInstance(view)) return type.cast(view);
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        T found = findClass(group.getChildAt(i), type);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void shell(Instrumentation host, String command) throws Exception {
    try (ParcelFileDescriptor fd = host.getUiAutomation().executeShellCommand(command);
        java.io.FileInputStream stream = new java.io.FileInputStream(fd.getFileDescriptor())) {
      stream.readAllBytes();
    }
  }

  private static void main(Instrumentation host, Runnable work) {
    AtomicReference<Throwable> error = new AtomicReference<>();
    host.runOnMainSync(
        () -> {
          try {
            work.run();
          } catch (Throwable e) {
            error.set(e);
          }
        });
    if (error.get() != null) throw new AssertionError("UI verification failed: "+error.get().getMessage(), error.get());
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
