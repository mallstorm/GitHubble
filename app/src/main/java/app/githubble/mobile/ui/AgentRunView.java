package app.githubble.mobile.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.githubble.mobile.agent.AgentRun;
import app.githubble.mobile.agent.AgentRunRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Compact disclosure for an execution, with its own second ticker and stable expansion state. */
public final class AgentRunView extends LinearLayout {
  private final Activity activity;
  private final int text, muted, accent, border;
  private final Consumer<String> openDiff;
  private final TextView heading, timer, stage;
  private final LinearLayout body, reads, other, partial;
  private final TextView readHeading, taskLine, resume;
  private Runnable resumeHandler;
  private final Map<String, TextView> operationTimers = new LinkedHashMap<>();
  private boolean expanded, readsExpanded;
  private boolean manuallyExpanded;
  private String fingerprint = "";
  private JSONObject value;
  private long baseElapsed, received;
  private boolean ticking;

  public AgentRunView(
      Activity activity,
      int text,
      int muted,
      int accent,
      int soft,
      int border,
      JSONObject snapshot,
      Consumer<String> openDiff) {
    super(activity);
    this.activity = activity;
    this.text = text;
    this.muted = muted;
    this.accent = accent;
    this.border = border;
    this.openDiff = openDiff;
    setOrientation(VERTICAL);
    setPadding(dp(11), dp(3), dp(11), dp(7));
    GradientDrawable background = new GradientDrawable();
    background.setColor(soft);
    background.setCornerRadius(dp(16));
    background.setStroke(dp(1), border);
    setBackground(background);
    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setMinimumHeight(dp(48));
    heading = label("", 12, text);
    heading.setTypeface(null, Typeface.BOLD);
    heading.setMaxLines(2);
    header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    timer = label("", 10, muted);
    timer.setFontFeatureSettings("tnum");
    timer.setPadding(dp(8), 0, 0, 0);
    header.addView(timer);
    addView(header);
    body = new LinearLayout(activity);
    body.setOrientation(VERTICAL);
    addView(body);
    stage = label("", 11, muted);
    stage.setPadding(dp(2), dp(2), dp(2), dp(7));
    body.addView(stage);
    taskLine = label("", 11, muted);
    taskLine.setPadding(dp(2), 0, dp(2), dp(7));
    body.addView(taskLine);
    readHeading = label("", 12, muted);
    readHeading.setGravity(Gravity.CENTER_VERTICAL);
    readHeading.setMinHeight(dp(48));
    body.addView(readHeading);
    reads = new LinearLayout(activity);
    reads.setOrientation(VERTICAL);
    body.addView(reads);
    other = new LinearLayout(activity);
    other.setOrientation(VERTICAL);
    body.addView(other);
    partial = new LinearLayout(activity);
    partial.setOrientation(VERTICAL);
    body.addView(partial);
    resume = label("↻ Продолжить с контекстом", 12, accent);
    resume.setTypeface(null, Typeface.BOLD);
    resume.setGravity(Gravity.CENTER_VERTICAL);
    resume.setMinHeight(dp(48));
    resume.setVisibility(GONE);
    resume.setOnClickListener(
        v -> {
          Runnable handler = resumeHandler;
          if (handler != null) handler.run();
        });
    body.addView(resume);
    readHeading.setOnClickListener(
        v -> {
          readsExpanded = !readsExpanded;
          updateReads();
        });
    expanded = live(snapshot) || attention(snapshot);
    header.setOnClickListener(
        v -> {
          manuallyExpanded = true;
          expanded = !expanded;
          updateHeading();
        });
    update(snapshot);
  }

  private int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }

  private TextView label(String s, int size, int color) {
    TextView v = new TextView(activity);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    return v;
  }

  private static boolean active(JSONObject v) {
    return "running".equals(v.optString("state")) || "approval".equals(v.optString("state"));
  }

  private static boolean live(JSONObject v) {
    return active(v)
        && AgentRunRegistry.live(
            v.optString("id"), v.optString("account"), v.optString("conversation"));
  }

  /** Offered only on the newest run; the handler restarts the task with its recorded context. */
  public void setResumeHandler(Runnable handler) {
    resumeHandler = handler;
    if (value != null) updateResume();
  }

  private void updateResume() {
    boolean interrupted =
        !live(value)
            && (active(value)
                || "failed".equals(value.optString("state"))
                || "interrupted".equals(value.optString("state")));
    boolean offered =
        resumeHandler != null
            && (interrupted
                || app.githubble.mobile.agent.AgentTaskLabels.resumable(value.optString("task_state")));
    resume.setVisibility(offered ? VISIBLE : GONE);
  }

  public void update(JSONObject snapshot) {
    boolean wasActive = value != null && active(value);
    value = snapshot;
    baseElapsed = value.optLong("elapsed_ms");
    received = SystemClock.elapsedRealtime();
    if (wasActive && !active(value) && !manuallyExpanded && !attention(value)) expanded = false;
    if (!active(value) && attention(value)) expanded = true;
    boolean isLive = live(value);
    String state = value.optString("state");
    stage.setText((active(value) && !isLive ? "Прервана · " : "") + value.optString("stage"));
    if ("approval".equals(state))
      stage.setText("Ожидает подтверждения · время ожидания входит в задачу");
    String content =
        value.optJSONArray("operations") == null
            ? "[]"
            : value.optJSONArray("operations").toString();
    if (!fingerprint.equals(content)) {
      fingerprint = content;
      rebuild();
    }
    partial.removeAllViews();
    if (!live(value)
        && !"succeeded".equals(value.optString("state"))
        && !value.optString("preview").isEmpty()) {
      partial.addView(label("Частичный ответ · выполнение прервано", 11, muted));
      new AgentMarkdownView(activity, text, muted, accent, 0, border)
          .render(partial, value.optString("preview"));
    }
    String line =
        app.githubble.mobile.agent.AgentTaskLabels.line(
            value.optString("task_state"), value.optString("mode"));
    taskLine.setText(line);
    taskLine.setVisibility(line.isEmpty() ? GONE : VISIBLE);
    updateResume();
    updateHeading();
    tick();
  }

  private static boolean attention(JSONObject value) {
    if ("failed".equals(value.optString("state"))) return true;
    JSONArray ops = value.optJSONArray("operations");
    for (int i = 0; ops != null && i < ops.length(); i++) {
      JSONObject op = ops.optJSONObject(i);
      if (op != null
          && ("failed".equals(op.optString("state")) || "unknown".equals(op.optString("state"))))
        return true;
    }
    return false;
  }

  private void updateHeading() {
    String state = value.optString("state");
    String title =
        live(value)
            ? ("approval".equals(state)
                ? "Ожидает подтверждения"
                : "Работаю · итерация " + Math.max(1, value.optInt("iteration")))
            : "succeeded".equals(state)
                ? (attention(value) ? "Завершено · есть ошибки" : "Готово")
                : "cancelled".equals(state)
                    ? "Остановлено"
                    : "failed".equals(state) ? "Ошибка задачи" : "Прервано";
    JSONArray ops = value.optJSONArray("operations");
    heading.setText(
        (expanded ? "▾ " : "▸ ")
            + title
            + (ops != null && ops.length() > 0 ? " · действий: " + ops.length() : ""));
    body.setVisibility(expanded ? VISIBLE : GONE);
    setContentDescription(title + ". Время " + AgentRun.time(baseElapsed));
  }

  private void updateReads() {
    reads.setVisibility(readsExpanded ? VISIBLE : GONE);
    String count = String.valueOf(reads.getChildCount());
    readHeading.setText((readsExpanded ? "▾ " : "▸ ") + "Просмотр файлов · " + count);
  }

  private void rebuild() {
    reads.removeAllViews();
    other.removeAllViews();
    operationTimers.clear();
    JSONArray ops = value.optJSONArray("operations");
    int lastIteration = -1;
    for (int i = 0; ops != null && i < ops.length(); i++) {
      JSONObject op = ops.optJSONObject(i);
      if (op == null) continue;
      String name = op.optString("name"), state = op.optString("state");
      boolean group = AgentRun.read(name) && "succeeded".equals(state);
      LinearLayout parent = group ? reads : other;
      if (!group && op.optInt("iteration") != lastIteration) {
        lastIteration = op.optInt("iteration");
        TextView iteration = label("Итерация " + Math.max(1, lastIteration), 10, muted);
        iteration.setPadding(0, dp(6), 0, dp(3));
        parent.addView(iteration);
      }
      LinearLayout row = new LinearLayout(activity);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setMinimumHeight(dp(40));
      TextView icon = label(AgentRun.icon(name), 16, muted);
      row.addView(icon, new LayoutParams(dp(28), -2));
      LinearLayout labels = new LinearLayout(activity);
      labels.setOrientation(VERTICAL);
      TextView label = label(AgentRun.label(name), 12, text);
      labels.addView(label);
      String target = op.optString("target");
      if (!target.isEmpty()) {
        TextView path = label(target, 11, muted);
        path.setSingleLine();
        path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        path.setContentDescription(target);
        labels.addView(path);
      }
      row.addView(labels, new LayoutParams(0, -2, 1));
      TextView time = label(AgentRun.time(op.optLong("elapsed_ms")), 10, muted);
      time.setPadding(dp(5), 0, dp(5), 0);
      time.setFontFeatureSettings("tnum");
      row.addView(time);
      operationTimers.put(op.optString("id"), time);
      TextView mark =
          label(
              "succeeded".equals(state)
                  ? "✓"
                  : "failed".equals(state)
                      ? "!"
                      : "cancelled".equals(state) || "interrupted".equals(state)
                          ? "−"
                          : "unknown".equals(state) ? "?" : "⋯",
              13,
              "failed".equals(state) ? 0xFFC75B66 : accent);
      row.addView(mark);
      parent.addView(row);
      String error = op.optString("error");
      if (!error.isEmpty()) {
        TextView failure = label(error, 11, 0xFFC75B66);
        failure.setPadding(dp(28), 0, 0, dp(5));
        parent.addView(failure);
      }
      JSONArray files = op.optJSONArray("files");
      for (int j = 0; files != null && j < files.length(); j++) {
        JSONObject f = files.optJSONObject(j);
        if (f != null) file(parent, f, op.optBoolean("applied"));
      }
    }
    readHeading.setVisibility(reads.getChildCount() > 0 ? VISIBLE : GONE);
    updateReads();
  }

  private void file(LinearLayout parent, JSONObject f, boolean applied) {
    String path = f.optString("path"),
        folder = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "Проект";
    TextView directory =
        label("📂 " + folder + (applied ? " · локально" : " · предложено"), 10, muted);
    directory.setPadding(dp(4), dp(6), 0, 0);
    parent.addView(directory);
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(48));
    TextView icon = label("create".equals(f.optString("kind")) ? "＋" : "✏️", 17, accent);
    row.addView(icon, new LayoutParams(dp(28), -2));
    TextView name = label(path.substring(path.lastIndexOf('/') + 1), 13, text);
    name.setTypeface(null, Typeface.BOLD);
    name.setSingleLine();
    name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    row.addView(name, new LayoutParams(0, -2, 1));
    if (f.optBoolean("approximate")) row.addView(label(" ≈", 10, muted));
    TextView plus = label(" +" + f.optInt("added"), 11, applied ? accent : muted);
    row.addView(plus);
    row.addView(
        label(
            " −" + f.optInt("removed"),
            11,
            f.optInt("removed") > 0 && applied ? 0xFFC75B66 : muted));
    row.setContentDescription(
        path
            + (applied ? " сохранён локально" : " предложен")
            + ", добавлено "
            + f.optInt("added")
            + ", удалено "
            + f.optInt("removed"));
    String id = f.optString("diff_id");
    if (!id.isEmpty()) row.setOnClickListener(v -> openDiff.accept(id));
    parent.addView(row);
  }

  private final Runnable ticker =
      () -> {
        ticking = false;
        tick();
      };

  private void tick() {
    if (value == null) return;
    long elapsed =
        baseElapsed + (live(value) ? Math.max(0, SystemClock.elapsedRealtime() - received) : 0);
    timer.setText(AgentRun.time(elapsed));
    if (live(value)) {
      AgentRun run =
          AgentRunRegistry.get(value.optString("account"), value.optString("conversation"));
      JSONObject current = run == null ? value : run.snapshot();
      JSONArray ops = current.optJSONArray("operations");
      for (int i = 0; ops != null && i < ops.length(); i++) {
        JSONObject op = ops.optJSONObject(i);
        TextView t = op == null ? null : operationTimers.get(op.optString("id"));
        if (t != null && "running".equals(op.optString("state")))
          t.setText(AgentRun.time(SystemClock.elapsedRealtime() - op.optLong("started")));
      }
      if (!ticking && isAttachedToWindow()) {
        ticking = true;
        postDelayed(ticker, 1000);
      }
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    tick();
  }

  @Override
  protected void onDetachedFromWindow() {
    removeCallbacks(ticker);
    ticking = false;
    super.onDetachedFromWindow();
  }
}
