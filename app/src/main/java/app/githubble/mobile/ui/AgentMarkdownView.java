package app.githubble.mobile.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.*;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.*;

/** Native block renderer. No HTML interpretation, JavaScript or automatic resource downloads. */
public final class AgentMarkdownView {
  private final Activity activity;
  private final int text, muted, accent, soft, border;

  public AgentMarkdownView(
      Activity activity, int text, int muted, int accent, int soft, int border) {
    this.activity = activity;
    this.text = text;
    this.muted = muted;
    this.accent = accent;
    this.soft = soft;
    this.border = border;
  }

  private int dp(int value) {
    return (int) (value * activity.getResources().getDisplayMetrics().density + .5f);
  }

  public void render(LinearLayout parent, String source) {
    blocks(parent, AgentMarkdown.parse(source));
  }

  private TextView label(CharSequence value, int size, int color) {
    TextView v = new TextView(activity);
    v.setText(value);
    v.setTextColor(color);
    v.setLinkTextColor(accent);
    v.setTextSize(size);
    v.setTextIsSelectable(true);
    v.setLineSpacing(dp(2), 1.05f);
    v.setMovementMethod(LinkMovementMethod.getInstance());
    return v;
  }

  private GradientDrawable shape(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(12));
    return d;
  }

  private void add(LinearLayout parent, View view) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(9);
    parent.addView(view, p);
  }

  private void blocks(LinearLayout parent, Node node) {
    for (Node n = node.getFirstChild(); n != null; n = n.getNext()) block(parent, n);
  }

  private void block(LinearLayout parent, Node n) {
    if (n instanceof TaskListItemMarker) return;
    if (n instanceof Heading) {
      int level = ((Heading) n).getLevel();
      TextView title =
          label(inline(n), level == 1 ? 23 : level == 2 ? 20 : level == 3 ? 17 : 15, text);
      title.setTypeface(null, Typeface.BOLD);
      title.setPadding(0, dp(8), 0, dp(3));
      add(parent, title);
    } else if (n instanceof Paragraph) add(parent, label(inline(n), 14, text));
    else if (n instanceof FencedCodeBlock) {
      FencedCodeBlock code = (FencedCodeBlock) n;
      code(parent, code.getLiteral(), code.getInfo());
    } else if (n instanceof IndentedCodeBlock)
      code(parent, ((IndentedCodeBlock) n).getLiteral(), "");
    else if (n instanceof TableBlock) table(parent, n);
    else if (n instanceof BulletList || n instanceof OrderedList) {
      int number = n instanceof OrderedList ? ((OrderedList) n).getStartNumber() : 0;
      for (Node item = n.getFirstChild(); item != null; item = item.getNext()) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.TOP);
        Node first = item.getFirstChild();
        String mark =
            first instanceof TaskListItemMarker
                ? (((TaskListItemMarker) first).isChecked() ? "☑" : "☐")
                : n instanceof OrderedList ? (number++) + "." : "•";
        TextView marker = label(mark, 14, muted);
        marker.setTextIsSelectable(false);
        row.addView(marker, new LinearLayout.LayoutParams(dp(25), -2));
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        blocks(body, item);
        add(parent, row);
      }
    } else if (n instanceof BlockQuote) {
      LinearLayout row = new LinearLayout(activity);
      View bar = new View(activity);
      bar.setBackgroundColor(accent);
      row.addView(bar, new LinearLayout.LayoutParams(dp(3), -1));
      LinearLayout body = new LinearLayout(activity);
      body.setOrientation(LinearLayout.VERTICAL);
      body.setPadding(dp(12), dp(4), dp(5), 0);
      blocks(body, n);
      row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
      add(parent, row);
    } else if (n instanceof ThematicBreak) {
      View line = new View(activity);
      line.setBackgroundColor(border);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
      p.setMargins(0, dp(9), 0, dp(14));
      parent.addView(line, p);
    } else if (n instanceof HtmlBlock) {
      TextView html = label(((HtmlBlock) n).getLiteral(), 13, muted);
      html.setTypeface(Typeface.MONOSPACE);
      add(parent, html);
    } else blocks(parent, n);
  }

  private SpannableStringBuilder inline(Node parent) {
    SpannableStringBuilder out = new SpannableStringBuilder();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) append(out, n);
    return out;
  }

  private void append(SpannableStringBuilder out, Node n) {
    int start = out.length();
    if (n instanceof Text) out.append(((Text) n).getLiteral());
    else if (n instanceof Code) out.append(((Code) n).getLiteral());
    else if (n instanceof SoftLineBreak || n instanceof HardLineBreak) out.append('\n');
    else if (n instanceof HtmlInline) out.append(((HtmlInline) n).getLiteral());
    else if (n instanceof TaskListItemMarker)
      out.append(((TaskListItemMarker) n).isChecked() ? "☑ " : "☐ ");
    else if (n instanceof Image) {
      out.append("Изображение: ");
      out.append(inline(n));
    } else out.append(inline(n));
    if (out.length() == start) return;
    Object span = null;
    if (n instanceof StrongEmphasis) span = new StyleSpan(Typeface.BOLD);
    else if (n instanceof Emphasis) span = new StyleSpan(Typeface.ITALIC);
    else if (n instanceof Strikethrough) span = new StrikethroughSpan();
    else if (n instanceof Code) {
      span = new TypefaceSpan("monospace");
      out.setSpan(
          new BackgroundColorSpan(soft), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    } else if (n instanceof Link && AgentMarkdown.safeLink(((Link) n).getDestination()))
      span = new URLSpan(((Link) n).getDestination());
    else if (n instanceof Image && AgentMarkdown.safeLink(((Image) n).getDestination()))
      span = new URLSpan(((Image) n).getDestination());
    if (span != null) out.setSpan(span, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private void code(LinearLayout parent, String source, String info) {
    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(11), dp(5), dp(11), dp(7));
    box.setBackground(shape(soft));
    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView language =
        label(
            info == null || info.trim().isEmpty() ? "КОД" : info.trim().split("\\s+")[0],
            10,
            muted);
    language.setTextIsSelectable(false);
    header.addView(language, new LinearLayout.LayoutParams(0, -2, 1));
    TextView copy = label("Копировать", 11, accent);
    copy.setTextIsSelectable(false);
    copy.setGravity(Gravity.CENTER);
    copy.setPadding(dp(8), dp(9), dp(8), dp(9));
    copy.setMinHeight(dp(48));
    copy.setOnClickListener(
        v -> {
          ClipboardManager cb =
              (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
          if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("Код", source));
          Toast.makeText(activity, "Код скопирован", Toast.LENGTH_SHORT).show();
        });
    header.addView(copy);
    box.addView(header);
    HorizontalScrollView scroll = new HorizontalScrollView(activity);
    scroll.setFillViewport(true);
    TextView content = label(highlight(source), 13, text);
    content.setTypeface(Typeface.MONOSPACE);
    content.setHorizontallyScrolling(true);
    content.setPadding(0, 0, dp(8), dp(4));
    scroll.addView(content, new ScrollView.LayoutParams(-2, -2));
    box.addView(scroll);
    add(parent, box);
  }

  private CharSequence highlight(String code) {
    if (code.length() > 30000) return code;
    SpannableStringBuilder out = new SpannableStringBuilder(code);
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "(?m)//[^\\n"
                    + "]*|#[^\\n"
                    + "]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b(?:class|interface|public|private|final|static|void|return|if|else|for|while|try|catch|throw|new|val|var|fun|def|import|from|const|let|true|false|null|None)\\b|\\b\\d+(?:\\.\\d+)?\\b")
            .matcher(code);
    int count = 0;
    while (m.find() && count++ < 6000) {
      String token = m.group();
      int color =
          token.startsWith("//") || token.startsWith("#")
              ? muted
              : token.startsWith("\"") || token.startsWith("'")
                  ? accent
                  : Color.luminance(text) > .5 ? 0xFF8CC9E8 : 0xFF3A668E;
      out.setSpan(
          new ForegroundColorSpan(color), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return out;
  }

  private void table(LinearLayout parent, Node table) {
    HorizontalScrollView scroll = new HorizontalScrollView(activity);
    scroll.setBackground(shape(soft));
    TableLayout layout = new TableLayout(activity);
    layout.setPadding(dp(1), dp(1), dp(1), dp(1));
    scroll.addView(layout);
    List<TableRow> rows = new ArrayList<>();
    collect(table, rows);
    int[] next = {0};
    Runnable[] more = {null};
    TextView expand = label("Показать следующие строки", 12, accent);
    expand.setTextIsSelectable(false);
    expand.setPadding(dp(8), dp(10), dp(8), dp(10));
    expand.setMinHeight(dp(48));
    more[0] =
        () -> {
          int end = Math.min(rows.size(), next[0] + 100);
          for (; next[0] < end; next[0]++) {
            android.widget.TableRow row = new android.widget.TableRow(activity);
            for (Node c = rows.get(next[0]).getFirstChild(); c != null; c = c.getNext()) {
              if (!(c instanceof TableCell)) continue;
              TableCell cell = (TableCell) c;
              TextView value = label(inline(cell), 13, text);
              value.setPadding(dp(11), dp(9), dp(11), dp(9));
              value.setBackgroundColor(cell.isHeader() ? soft : Color.TRANSPARENT);
              if (cell.isHeader()) value.setTypeface(null, Typeface.BOLD);
              if (cell.getAlignment() == TableCell.Alignment.RIGHT) value.setGravity(Gravity.END);
              else if (cell.getAlignment() == TableCell.Alignment.CENTER)
                value.setGravity(Gravity.CENTER_HORIZONTAL);
              row.addView(value, new android.widget.TableRow.LayoutParams(dp(185), -2));
            }
            layout.addView(row);
            View separator = new View(activity);
            separator.setBackgroundColor(border);
            layout.addView(separator, new TableLayout.LayoutParams(-1, dp(1)));
          }
          expand.setVisibility(next[0] < rows.size() ? View.VISIBLE : View.GONE);
        };
    more[0].run();
    expand.setOnClickListener(v -> more[0].run());
    add(parent, scroll);
    parent.addView(expand);
  }

  private void collect(Node node, List<TableRow> rows) {
    for (Node n = node.getFirstChild(); n != null; n = n.getNext())
      if (n instanceof TableRow) rows.add((TableRow) n);
      else collect(n, rows);
  }
}
