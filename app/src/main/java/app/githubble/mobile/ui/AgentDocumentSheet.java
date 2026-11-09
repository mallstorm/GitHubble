package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.*;
import app.githubble.mobile.agent.AgentLineDiff;
import java.util.Locale;

/** Native document/source and before/after readers, with original text kept intact. */
public final class AgentDocumentSheet {
  private AgentDocumentSheet() {}

  public static void document(
      Activity a,
      String source,
      int text,
      int muted,
      int accent,
      int soft,
      int border,
      Runnable save) {
    LinearLayout root = column(a), tabs = new LinearLayout(a);
    root.addView(tabs);
    ScrollView scroll = new ScrollView(a);
    LinearLayout body = column(a);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Runnable formatted =
        () -> {
          body.removeAllViews();
          new AgentMarkdownView(a, text, muted, accent, soft, border).render(body, source);
        };
    action(a, tabs, "Документ", formatted);
    action(
        a,
        tabs,
        "Markdown",
        () -> {
          body.removeAllViews();
          TextView raw = plain(a, source, text);
          raw.setTypeface(Typeface.MONOSPACE);
          body.addView(raw);
        });
    formatted.run();
    AlertDialog d =
        new AlertDialog.Builder(a)
            .setTitle("Документ Git Agent")
            .setView(root)
            .setPositiveButton("Сохранить .md", (v, w) -> save.run())
            .setNegativeButton("Закрыть", null)
            .create();
    d.show();
    size(a, d);
  }

  public static void diff(
      Activity a,
      String path,
      String before,
      String after,
      int text,
      int muted,
      int accent,
      int border) {
    LinearLayout root = column(a), tabs = new LinearLayout(a);
    root.addView(tabs);
    ScrollView scroll = new ScrollView(a);
    HorizontalScrollView horizontal = new HorizontalScrollView(a);
    TextView content = plain(a, "", text);
    content.setTypeface(Typeface.MONOSPACE);
    content.setHorizontallyScrolling(true);
    horizontal.addView(content);
    scroll.addView(horizontal);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    AgentLineDiff diff = AgentLineDiff.between(before, after);
    Runnable changes =
        () -> {
          SpannableStringBuilder value = new SpannableStringBuilder();
          for (AgentLineDiff.Line line : diff.lines) {
            int start = value.length();
            value.append(
                String.format(
                    Locale.ROOT,
                    "%4s %4s  %c %s\n",
                    line.before == 0 ? "" : line.before,
                    line.after == 0 ? "" : line.after,
                    line.kind,
                    line.text));
            if (line.kind != ' ')
              value.setSpan(
                  new ForegroundColorSpan(line.kind == '+' ? accent : 0xFFC75B66),
                  start,
                  value.length(),
                  Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          }
          content.setText(value);
          scroll.scrollTo(0, 0);
        };
    action(a, tabs, "Изменения", changes);
    action(
        a,
        tabs,
        "До",
        () -> {
          content.setText(before.isEmpty() ? "(новый файл)" : before);
          scroll.scrollTo(0, 0);
        });
    action(
        a,
        tabs,
        "После",
        () -> {
          content.setText(after.isEmpty() ? "(пустой файл)" : after);
          scroll.scrollTo(0, 0);
        });
    if (diff.coarse) {
      TextView note =
          plain(
              a,
              "Крупный файл: показана замена целиком. Полные версии доступны во вкладках «До» и"
                  + " «После».",
              muted);
      root.addView(note, 1);
    }
    changes.run();
    AlertDialog d =
        new AlertDialog.Builder(a)
            .setTitle(path)
            .setView(root)
            .setNegativeButton("Закрыть", null)
            .create();
    d.show();
    size(a, d);
  }

  private static LinearLayout column(Activity a) {
    LinearLayout v = new LinearLayout(a);
    v.setOrientation(LinearLayout.VERTICAL);
    int p = (int) (12 * a.getResources().getDisplayMetrics().density);
    v.setPadding(p, p, p, p);
    return v;
  }

  private static TextView plain(Activity a, CharSequence s, int color) {
    TextView v = new TextView(a);
    v.setText(s);
    v.setTextColor(color);
    v.setTextSize(13);
    v.setTextIsSelectable(true);
    return v;
  }

  private static void action(Activity a, LinearLayout row, String name, Runnable action) {
    Button b = new Button(a);
    b.setText(name);
    b.setTextSize(12);
    b.setOnClickListener(v -> action.run());
    row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
  }

  private static void size(Activity a, AlertDialog d) {
    if (d.getWindow() != null)
      d.getWindow().setLayout(-1, (int) (a.getResources().getDisplayMetrics().heightPixels * .86));
  }
}
