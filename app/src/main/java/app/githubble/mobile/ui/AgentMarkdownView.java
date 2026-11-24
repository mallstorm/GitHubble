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
  /** Receives taps on branch links produced by the agent (repository, branch). */
  public interface BranchListener {
    void pick(String repository, String branch);
  }

  public static volatile BranchListener branchListener;

  /** Receives taps on in-chat action links such as «Продолжить» (githubble-action://continue). */
  public static volatile java.util.function.Consumer<String> actionListener;

  private static Object linkSpan(String url) {
    if (url != null && url.startsWith("githubble-action://")) {
      final String action = url.substring("githubble-action://".length());
      return new ClickableSpan() {
        @Override
        public void onClick(View widget) {
          java.util.function.Consumer<String> l = actionListener;
          if (l != null) l.accept(action);
        }
      };
    }
    final String[] branch = app.githubble.mobile.agent.BranchHistory.parse(url);
    if (branch == null) return new URLSpan(url);
    return new ClickableSpan() {
      @Override
      public void onClick(View widget) {
        BranchListener l = branchListener;
        if (l != null) l.pick(branch[0], branch[1]);
      }
    };
  }

  private final Activity activity;
  private final int text, muted, accent, soft, border;
  private boolean selectable = true;
  private boolean wideTables;

  /** Kept for callers; tables now always stay inside the message column (no breaking out to the screen edges). */
  public AgentMarkdownView wideTables(boolean value) {
    wideTables = false;
    return this;
  }

  /** Chat bubbles use a long-press menu instead of in-place selection. */
  public AgentMarkdownView selectable(boolean value) {
    selectable = value;
    return this;
  }

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
    v.setTextIsSelectable(selectable);
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
      span = linkSpan(((Link) n).getDestination());
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

  private int mix(int a, int b, float w) {
    int r = (int) (Color.red(a) * (1 - w) + Color.red(b) * w);
    int g = (int) (Color.green(a) * (1 - w) + Color.green(b) * w);
    int bl = (int) (Color.blue(a) * (1 - w) + Color.blue(b) * w);
    return Color.rgb(r, g, bl);
  }

  private void table(LinearLayout parent, Node table) {
    final List<TableRow> rows = new ArrayList<>();
    collect(table, rows);
    // Pre-render every cell once: column widths are derived from the real content.
    final List<List<SpannableStringBuilder>> cells = new ArrayList<>();
    final List<List<TableCell>> meta = new ArrayList<>();
    int cols = 0;
    for (TableRow tr : rows) {
      List<SpannableStringBuilder> line = new ArrayList<>();
      List<TableCell> info = new ArrayList<>();
      for (Node c = tr.getFirstChild(); c != null; c = c.getNext()) {
        if (!(c instanceof TableCell)) continue;
        line.add(inline(c));
        info.add((TableCell) c);
      }
      cols = Math.max(cols, line.size());
      cells.add(line);
      meta.add(info);
    }
    if (cols == 0) return;
    // Phone-friendly rendering: wide tables become compact cards (no sideways scrolling, plain TextViews, fast),
    // and a table that has only a header (unfinished answer) never becomes a giant empty block.
    if (cols >= 3 || cells.size() < 2) {
      tableCards(parent, cells, meta, cols);
      return;
    }
    final float charPx = 7.4f * activity.getResources().getDisplayMetrics().density;
    final int padding = dp(12), pad2 = padding * 2;
    final int[] want = new int[cols], least = new int[cols];
    for (int r = 0; r < cells.size(); r++) {
      for (int c = 0; c < cells.get(r).size(); c++) {
        String plain = cells.get(r).get(c).toString();
        int longest = 0;
        for (String word : plain.split("\\s+")) longest = Math.max(longest, word.length());
        want[c] = Math.max(want[c], Math.min(dp(240), (int) (plain.length() * charPx) + pad2));
        least[c] = Math.max(least[c], Math.min(dp(130), (int) (longest * charPx) + pad2));
      }
    }
    for (int c = 0; c < cols; c++) {
      least[c] = Math.max(least[c], dp(64));
      want[c] = Math.max(want[c], least[c]);
    }
    final int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
    final int available = wideTables ? screenWidth - dp(16) : screenWidth - dp(34);
    int total = 0;
    for (int w : want) total += w;
    final int[] width = want.clone();
    if (total < available) {
      int used = 0;
      for (int c = 0; c < cols; c++) {
        width[c] = c == cols - 1 ? available - used : (int) ((long) want[c] * available / total);
        used += width[c];
      }
    } else if (total > available) {
      int excess = total - available, room = 0;
      for (int c = 0; c < cols; c++) room += want[c] - least[c];
      for (int c = 0; c < cols; c++)
        width[c] = room <= 0 ? least[c] : want[c] - (int) ((long) Math.min(excess, room) * (want[c] - least[c]) / room);
    }
    int gridWidth = 0;
    for (int w : width) gridWidth += w + 1;

    // Beige "paper" palette, with its own text colors so contrast does not depend on the chat theme.
    final boolean night = Color.luminance(text) > .5;
    final int paper = night ? 0xFF3A3328 : 0xFFF6EFDF;
    final int headerBg = night ? 0xFF4C4232 : 0xFFE8DABB;
    final int zebra = night ? 0xFF433A2D : 0xFFEFE4CD;
    final int line = night ? 0xFF5E5240 : 0xFFD3C29C;
    final int ink = night ? 0xFFF1E8D4 : 0xFF3A3123;
    final int headInk = night ? 0xFFFFE6B0 : 0xFF2A200F;
    final int linkInk = night ? 0xFFF0C070 : 0xFF8A5A00;
    final LinearLayout frame = new LinearLayout(activity);
    GradientDrawable frameShape = new GradientDrawable();
    frameShape.setColor(paper);
    frameShape.setStroke(dp(1), line);
    frame.setBackground(frameShape);
    frame.setOrientation(LinearLayout.VERTICAL);
    if (wideTables) {
      frame.addOnAttachStateChangeListener(
          new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
              for (android.view.ViewParent p = v.getParent(); p instanceof android.view.ViewGroup; p = p.getParent()) {
                ((android.view.ViewGroup) p).setClipChildren(false);
                ((android.view.ViewGroup) p).setClipToPadding(false);
                if (p instanceof ScrollView) break;
              }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {}
          });
      frame.addOnLayoutChangeListener(
          (v, l, t, r, b, ol, ot, or, ob) -> {
            int[] where = new int[2];
            v.getLocationInWindow(where);
            float shift = dp(8) - where[0];
            if (Math.abs(shift) > 1) v.setTranslationX(v.getTranslationX() + shift);
          });
    }
    HorizontalScrollView scroll = new HorizontalScrollView(activity);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
    final LinearLayout grid = new LinearLayout(activity);
    grid.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(grid, new FrameLayout.LayoutParams(gridWidth, -2));
    frame.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    final int columns = cols;
    int[] next = {0};
    Runnable[] more = {null};
    TextView expand = label("Показать следующие строки", 12, accent);
    expand.setTextIsSelectable(false);
    expand.setPadding(dp(8), dp(10), dp(8), dp(10));
    expand.setMinHeight(dp(48));
    more[0] =
        () -> {
          int end = Math.min(rows.size(), next[0] + 150);
          for (; next[0] < end; next[0]++) {
            final int index = next[0];
            boolean header = index == 0 && !meta.get(0).isEmpty() && meta.get(0).get(0).isHeader();
            LinearLayout row = new LinearLayout(activity);
            row.setBaselineAligned(false);
            row.setBackgroundColor(header ? headerBg : index % 2 == 0 ? Color.TRANSPARENT : zebra);
            for (int c = 0; c < columns; c++) {
              if (c > 0) {
                View vertical = new View(activity);
                vertical.setBackgroundColor(line);
                row.addView(vertical, new LinearLayout.LayoutParams(1, -1));
              }
              CharSequence content = c < cells.get(index).size() ? cells.get(index).get(c) : "";
              TextView value = label(content, header ? 12 : 13, header ? headInk : ink);
              value.setLinkTextColor(linkInk);
              value.setPadding(padding, dp(9), padding, dp(9));
              value.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
              if (header) value.setTypeface(null, Typeface.BOLD);
              TableCell cell = c < meta.get(index).size() ? meta.get(index).get(c) : null;
              if (cell != null && cell.getAlignment() == TableCell.Alignment.RIGHT)
                value.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
              else if (cell != null && cell.getAlignment() == TableCell.Alignment.CENTER)
                value.setGravity(Gravity.CENTER);
              row.addView(value, new LinearLayout.LayoutParams(width[c], -1));
            }
            grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            View separator = new View(activity);
            separator.setBackgroundColor(header ? (night ? 0xFF8C7A55 : 0xFFB59F6E) : line);
            grid.addView(separator, new LinearLayout.LayoutParams(-1, header ? dp(2) : 1));
          }
          expand.setVisibility(next[0] < rows.size() ? View.VISIBLE : View.GONE);
        };
    more[0].run();
    expand.setOnClickListener(v -> more[0].run());
    LinearLayout.LayoutParams framePlace =
        new LinearLayout.LayoutParams(wideTables ? screenWidth - dp(16) : -1, -2);
    framePlace.topMargin = dp(6);
    framePlace.bottomMargin = dp(6);
    parent.addView(frame, framePlace);
    parent.addView(expand);
  }

  private void tableCards(
      LinearLayout parent,
      final List<List<SpannableStringBuilder>> cells,
      final List<List<TableCell>> meta,
      int columns) {
    final boolean night = Color.luminance(text) > .5;
    final int paper = night ? 0xFF3A3328 : 0xFFF6EFDF;
    final int line = night ? 0xFF5E5240 : 0xFFD3C29C;
    final int ink = night ? 0xFFF1E8D4 : 0xFF3A3123;
    final int headInk = night ? 0xFFFFE6B0 : 0xFF2A200F;
    final int labelInk = night ? 0xFFC9B88F : 0xFF7A6A48;
    final int linkInk = night ? 0xFFF0C070 : 0xFF8A5A00;
    final boolean header = !meta.isEmpty() && !meta.get(0).isEmpty() && meta.get(0).get(0).isHeader();
    final int first = header ? 1 : 0;
    final List<SpannableStringBuilder> names = header ? cells.get(0) : new ArrayList<SpannableStringBuilder>();
    if (cells.size() <= first) {
      // Only a header: show it as one quiet line instead of an empty table.
      StringBuilder sb = new StringBuilder();
      for (SpannableStringBuilder name : names) sb.append(sb.length() == 0 ? "" : " · ").append(name);
      if (sb.length() > 0) {
        TextView only = label(sb.toString(), 13, labelInk);
        only.setPadding(dp(2), dp(4), dp(2), dp(4));
        parent.addView(only);
      }
      return;
    }
    final LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    final int[] next = {first};
    final TextView expand = label("Показать ещё", 13, accent);
    expand.setTextIsSelectable(false);
    expand.setGravity(Gravity.CENTER);
    expand.setMinHeight(dp(48));
    final Runnable[] more = {null};
    more[0] =
        () -> {
          int end = Math.min(cells.size(), next[0] + 40);
          for (; next[0] < end; next[0]++) {
            List<SpannableStringBuilder> row = cells.get(next[0]);
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(paper);
            shape.setCornerRadius(dp(12));
            shape.setStroke(dp(1), line);
            card.setBackground(shape);
            if (!row.isEmpty()) {
              TextView title = label(row.get(0), 15, headInk);
              title.setTypeface(null, Typeface.BOLD);
              title.setLinkTextColor(linkInk);
              card.addView(title);
            }
            for (int c = 1; c < columns && c < row.size(); c++) {
              SpannableStringBuilder value = row.get(c);
              String plain = value.toString().trim();
              if (plain.isEmpty() || "—".equals(plain) || "-".equals(plain)) continue;
              SpannableStringBuilder line1 = new SpannableStringBuilder();
              if (c < names.size() && names.get(c).length() > 0) {
                int start = line1.length();
                line1.append(names.get(c)).append(": ");
                line1.setSpan(new StyleSpan(Typeface.BOLD), start, line1.length(), 0);
                line1.setSpan(new ForegroundColorSpan(labelInk), start, line1.length(), 0);
              }
              line1.append(value);
              TextView entry = label(line1, 13, ink);
              entry.setLinkTextColor(linkInk);
              LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
              lp.topMargin = dp(3);
              card.addView(entry, lp);
            }
            LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-1, -2);
            place.topMargin = dp(6);
            box.addView(card, place);
          }
          expand.setVisibility(next[0] < cells.size() ? View.VISIBLE : View.GONE);
        };
    more[0].run();
    expand.setOnClickListener(v -> more[0].run());
    parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
    parent.addView(expand);
  }

  private void collect(Node node, List<TableRow> rows) {
    for (Node n = node.getFirstChild(); n != null; n = n.getNext())
      if (n instanceof TableRow) rows.add((TableRow) n);
      else collect(n, rows);
  }
}
