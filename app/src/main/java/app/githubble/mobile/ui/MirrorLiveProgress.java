package app.githubble.mobile.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import app.githubble.mobile.mirror.MirrorManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Inline progress for running mirror operations: stage, percent, Actions step and elapsed time, updated in place. */
final class MirrorLiveProgress {
    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private MirrorLiveProgress() {}

    private static final class Entry {
        String jobId;
        long since;
        LinearLayout view;
        TextView stage, meta;
        ProgressBar bar;
        String step = "";
        int percent = -1;

        void apply(JSONObject p) {
            if (p != null) {
                stage.setText(p.optString("stage", stage.getText().toString()));
                percent = p.optInt("percent", -1);
                step = p.optString("step_label", "");
                if (percent >= 0) {
                    bar.setIndeterminate(false);
                    bar.setMax(100);
                    bar.setProgress(percent);
                } else bar.setIndeterminate(true);
            }
            StringBuilder m = new StringBuilder("Прошло ").append(time(System.currentTimeMillis() - since));
            if (percent >= 0) m.append(" · ").append(percent).append('%');
            if (!step.isEmpty()) m.append(" · ").append(step);
            meta.setText(m.toString());
        }
    }

    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    /** Called at the start of every render: old views are gone, old pollers stop at their next tick. */
    static void reset() {
        ENTRIES.clear();
        GENERATION.incrementAndGet();
    }

    static boolean attached() {
        for (Entry e : ENTRIES) if (e.view.isAttachedToWindow()) return true;
        return false;
    }

    /** jobId == null is an operation that has no journal record yet (indeterminate). */
    static LinearLayout panel(Activity a, String jobId, long since, String stage,
            int text, int muted, int accent) {
        Entry e = new Entry();
        e.jobId = jobId;
        e.since = since;
        e.view = new LinearLayout(a);
        e.view.setOrientation(LinearLayout.VERTICAL);
        e.stage = new TextView(a);
        e.stage.setTextColor(text);
        e.stage.setTextSize(13);
        e.stage.setTypeface(null, Typeface.BOLD);
        e.stage.setText(stage);
        e.view.addView(e.stage);
        e.bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        e.bar.setIndeterminate(true);
        e.bar.setProgressTintList(ColorStateList.valueOf(accent));
        e.bar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, (int) (6 * a.getResources().getDisplayMetrics().density));
        bp.topMargin = bp.bottomMargin = (int) (8 * a.getResources().getDisplayMetrics().density);
        e.view.addView(e.bar, bp);
        e.meta = new TextView(a);
        e.meta.setTextColor(muted);
        e.meta.setTextSize(11);
        e.meta.setGravity(Gravity.START);
        e.view.addView(e.meta);
        e.apply(null);
        ENTRIES.add(e);
        return e.view;
    }

    /** One daemon poller per render generation; fetches GitHub every 4 s, redraws the clock every second. */
    static void poll(Activity a, MirrorManager manager, Runnable reload) {
        if (ENTRIES.isEmpty()) return;
        final int mine = GENERATION.get();
        Thread t = new Thread(() -> {
            int tick = 0;
            boolean finished = false;
            try {
                while (!a.isFinishing() && GENERATION.get() == mine) {
                    Thread.sleep(1000);
                    tick++;
                    List<Entry> now = new ArrayList<>(ENTRIES);
                    if (now.isEmpty() || GENERATION.get() != mine) break;
                    boolean visible = false;
                    for (Entry e : now) if (e.view.isAttachedToWindow()) visible = true;
                    if (!visible) break;
                    for (Entry e : now) {
                        JSONObject p = null;
                        if (tick % 4 == 0 && e.jobId != null) {
                            try {
                                MirrorManager.Job j = manager.find(e.jobId);
                                if (j != null && !MirrorManager.FAILED.equals(j.state)
                                        && !MirrorManager.VERIFIED.equals(j.state)) manager.refresh(e.jobId);
                                p = manager.liveProgress(e.jobId);
                            } catch (Exception ignored) {}
                        }
                        final JSONObject fp = p;
                        a.runOnUiThread(() -> e.apply(fp));
                        if (p != null && p.optBoolean("terminal")) finished = true;
                    }
                    if (finished) break;
                }
            } catch (InterruptedException ignored) {
                return;
            }
            if (finished && GENERATION.get() == mine)
                a.runOnUiThread(() -> { if (!a.isFinishing() && attached()) reload.run(); });
        }, "mirror-live");
        t.setDaemon(true);
        t.start();
    }
}
