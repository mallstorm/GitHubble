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
      int surface,
      Runnable save) {
    LinearLayout root = readerRoot(a), tabs = new LinearLayout(a);
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
    action(a, tabs, "Документ", text, accent, soft, formatted);
    action(
        a,
        tabs,
        "Markdown",
        text,
        accent,
        soft,
        () -> {
          body.removeAllViews();
          TextView raw = plain(a, source, text);
          raw.setTypeface(Typeface.MONOSPACE);
          body.addView(raw);
        });
    formatted.run();
    AlertDialog d =
        new AlertDialog.Builder(a)
            .setCustomTitle(title(a, "Документ Git Agent", text))
            .setView(root)
            .setPositiveButton("Сохранить .md", (v, w) -> save.run())
            .setNegativeButton("Закрыть", null)
            .create();
    d.show();
    size(a, d, surface, accent);
  }

  public static void diff(
      Activity a,
      String path,
      String before,
      String after,
      int text,
      int muted,
      int accent,
      int border,
      int surface) {
    LinearLayout root = readerRoot(a), tabs = new LinearLayout(a);
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
    action(a, tabs, "Изменения", text, accent, border, changes);
    action(
        a,
        tabs,
        "До",
        text,
        accent,
        border,
        () -> {
          content.setText(before.isEmpty() ? "(новый файл)" : before);
          scroll.scrollTo(0, 0);
        });
    action(
        a,
        tabs,
        "После",
        text,
        accent,
        border,
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
            .setCustomTitle(title(a, path, text))
            .setView(root)
            .setNegativeButton("Закрыть", null)
            .create();
    d.show();
    size(a, d, surface, accent);
  }

  private static LinearLayout readerRoot(Activity a) {
    LinearLayout root = column(a);
    // Reserve height for the weighted reader inside AlertDialog's wrapping custom panel.
    root.setMinimumHeight((int) (a.getResources().getDisplayMetrics().heightPixels * .62));
    return root;
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

  private static TextView title(Activity a, String value, int text) {
    TextView title = plain(a, value, text);
    title.setTextSize(17);
    title.setTypeface(null, Typeface.BOLD);
    int p = (int) (20 * a.getResources().getDisplayMetrics().density);
    title.setPadding(p, p, p, p / 2);
    return title;
  }

  private static void action(
      Activity a, LinearLayout row, String name, int text, int accent, int soft, Runnable action) {
    TextView b = new TextView(a);
    b.setText(name);
    b.setTextSize(12);
    b.setGravity(android.view.Gravity.CENTER);
    int p = (int) (8 * a.getResources().getDisplayMetrics().density);
    b.setPadding(p / 2, p, p / 2, p);
    b.setMinHeight(p * 6);
    b.setOnClickListener(
        v -> {
          selectTab(row, b, text, accent, soft);
          action.run();
        });
    row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    selectTab(row, (TextView) row.getChildAt(0), text, accent, soft);
  }

  private static void selectTab(
      LinearLayout row, TextView selected, int text, int accent, int soft) {
    for (int i = 0; i < row.getChildCount(); i++) {
      TextView tab = (TextView) row.getChildAt(i);
      tab.setTextColor(tab == selected ? accent : text);
      tab.setTypeface(null, tab == selected ? Typeface.BOLD : Typeface.NORMAL);
      android.graphics.drawable.GradientDrawable bg =
          new android.graphics.drawable.GradientDrawable();
      bg.setColor(tab == selected ? soft : android.graphics.Color.TRANSPARENT);
      bg.setCornerRadius(12 * row.getResources().getDisplayMetrics().density);
      tab.setBackground(bg);
      tab.setSelected(tab == selected);
    }
  }

  private static void size(Activity a, AlertDialog d, int surface, int accent) {
    if (d.getWindow() != null) {
      android.graphics.drawable.GradientDrawable bg =
          new android.graphics.drawable.GradientDrawable();
      bg.setColor(surface);
      bg.setCornerRadius(24 * a.getResources().getDisplayMetrics().density);
      d.getWindow().setBackgroundDrawable(bg);
      d.getWindow().setLayout(-1, (int) (a.getResources().getDisplayMetrics().heightPixels * .86));
    }
    for (int which : new int[] {AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE}) {
      Button button = d.getButton(which);
      if (button != null) button.setTextColor(accent);
    }
  }
}
