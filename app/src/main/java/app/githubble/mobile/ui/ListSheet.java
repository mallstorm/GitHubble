package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One modern bottom sheet for every "pick from a list" popup: search, groups, badges, toggles, footer actions. */
public final class ListSheet {
    public interface Toggle { void changed(boolean on); }

    private static final class Row {
        String group = "", title, subtitle = "", badge = "";
        boolean toggle, checked, danger;
        Toggle onToggle;
        Runnable click;
    }
    private static final class Action {
        final String label; final boolean primary; final Runnable run;
        Action(String label, boolean primary, Runnable run) { this.label = label; this.primary = primary; this.run = run; }
    }

    private final Activity activity;
    private final HomeScreen.Palette palette;
    private final List<Row> rows = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    private String title = "", subtitle = "", searchHint = "", empty = "Ничего не найдено";
    private Dialog dialog;
    private LinearLayout list;
    private EditText search;

    private ListSheet(Activity activity, HomeScreen.Palette palette) { this.activity = activity; this.palette = palette; }

    public static ListSheet of(Activity activity, HomeScreen.Palette palette) { return new ListSheet(activity, palette); }

    public ListSheet title(String value, String sub) { title = value; subtitle = sub == null ? "" : sub; return this; }
    public ListSheet searchable(String hint) { searchHint = hint; return this; }
    public ListSheet empty(String text) { empty = text; return this; }

    /** A tappable row; tapping closes the sheet and runs {@code click}. */
    public ListSheet row(String group, String title, String subtitle, String badge, Runnable click) {
        Row r = new Row(); r.group = group == null ? "" : group; r.title = title; r.subtitle = subtitle == null ? "" : subtitle;
        r.badge = badge == null ? "" : badge; r.click = click; rows.add(r); return this;
    }
    /** A row with an on/off switch; the sheet stays open. */
    public ListSheet toggle(String group, String title, String subtitle, String badge, boolean checked, Toggle onToggle) {
        Row r = new Row(); r.group = group == null ? "" : group; r.title = title; r.subtitle = subtitle == null ? "" : subtitle;
        r.badge = badge == null ? "" : badge; r.toggle = true; r.checked = checked; r.onToggle = onToggle; rows.add(r); return this;
    }
    public ListSheet action(String label, boolean primary, Runnable run) { actions.add(new Action(label, primary, run)); return this; }

    public void show() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(palette.background, 28, 0, 0));
        root.setClipToOutline(true);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(10), dp(18), dp(8));
        View grip = new View(activity);
        grip.setBackground(round(palette.muted, 3, 0, 0));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(32), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL; gp.bottomMargin = dp(14);
        header.addView(grip, gp);
        header.addView(text(title, 22, palette.text, true));
        if (!subtitle.isEmpty()) header.addView(text(subtitle, 12, palette.muted, false), lp(-1, -2, 0, 2, 0, 0));
        if (!searchHint.isEmpty()) {
            search = new EditText(activity);
            search.setSingleLine(true); search.setHint(searchHint); search.setTextSize(14);
            search.setTextColor(palette.text); search.setHintTextColor(palette.muted);
            search.setPadding(dp(14), 0, dp(14), 0);
            search.setBackground(round(palette.subtle, 14, palette.border, 1));
            search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) { fill(); }
            });
            header.addView(search, lp(-1, 46, 0, 12, 0, 4));
        }
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18), dp(4), dp(18), dp(12));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout footer = new LinearLayout(activity);
        footer.setPadding(dp(18), dp(10), dp(18), dp(16));
        for (int i = 0; i < actions.size(); i++) {
            final Action a = actions.get(i);
            footer.addView(button(a.label, a.primary, v -> { dialog.dismiss(); if (a.run != null) a.run.run(); }), lp(0, 48, 1, i == 0 ? 0 : 8, 0, 0, 0));
        }
        footer.addView(button("Закрыть", actions.isEmpty(), v -> dialog.dismiss()), lp(0, 48, 1, actions.isEmpty() ? 0 : 8, 0, 0, 0));
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        dialog = new Dialog(activity);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        fill();
        dialog.show();
        if (w != null) {
            WindowManager.LayoutParams p = w.getAttributes();
            p.dimAmount = .38f; w.setAttributes(p); w.setGravity(Gravity.BOTTOM);
            w.setLayout(activity.getResources().getDisplayMetrics().widthPixels,
                    Math.min(activity.getResources().getDisplayMetrics().heightPixels - dp(56), dp(40 + 76 * Math.max(3, Math.min(rows.size(), 8)))));
        }
    }

    private void fill() {
        list.removeAllViews();
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String lastGroup = null; int shown = 0;
        for (final Row r : rows) {
            if (!q.isEmpty() && !(r.title + " " + r.subtitle + " " + r.group + " " + r.badge).toLowerCase(Locale.ROOT).contains(q)) continue;
            if (!r.group.isEmpty() && !r.group.equals(lastGroup)) {
                list.addView(text(r.group.toUpperCase(Locale.ROOT), 11, palette.muted, true), lp(-1, -2, 4, lastGroup == null ? 6 : 16, 0, 6));
            }
            lastGroup = r.group; shown++;
            list.addView(card(r), lp(-1, -2, 0, 0, 0, 8));
        }
        if (shown == 0) list.addView(text(empty, 14, palette.muted, false), lp(-1, -2, 4, 24, 0, 0));
    }

    private View card(final Row r) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(11), dp(12), dp(11));
        card.setBackground(round(palette.card, 16, palette.border, 1));
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(r.title, 15, palette.text, true);
        name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(name);
        if (!r.subtitle.isEmpty()) {
            TextView sub = text(r.subtitle, 12, palette.muted, false);
            sub.setMaxLines(2); sub.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(sub, lp(-1, -2, 0, 2, 0, 0));
        }
        card.addView(body, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!r.badge.isEmpty()) {
            TextView badge = text(r.badge, 11, palette.green, true);
            badge.setPadding(dp(9), dp(3), dp(9), dp(3));
            badge.setBackground(round(Color.argb(28, Color.red(palette.green), Color.green(palette.green), Color.blue(palette.green)), 10, 0, 0));
            card.addView(badge, lp(-2, -2, 8, 0, 0, 0));
        }
        if (r.toggle) {
            final Switch sw = new Switch(activity);
            sw.setChecked(r.checked);
            sw.setThumbTintList(ColorStateList.valueOf(sw.isChecked() ? palette.green : palette.muted));
            sw.setTrackTintList(ColorStateList.valueOf(Color.argb(90, Color.red(palette.green), Color.green(palette.green), Color.blue(palette.green))));
            sw.setOnCheckedChangeListener((b, on) -> {
                r.checked = on;
                sw.setThumbTintList(ColorStateList.valueOf(on ? palette.green : palette.muted));
                if (r.onToggle != null) r.onToggle.changed(on);
            });
            card.addView(sw, lp(-2, -2, 8, 0, 0, 0));
            card.setOnClickListener(v -> sw.toggle());
        } else if (r.click != null) {
            card.setOnClickListener(v -> { dialog.dismiss(); r.click.run(); });
        }
        return card;
    }

    private TextView button(String label, boolean primary, View.OnClickListener click) {
        TextView b = text(label, 14, primary ? Color.WHITE : palette.text, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(primary ? round(palette.green, 16, 0, 0) : round(palette.subtle, 16, palette.border, 1));
        b.setOnClickListener(click);
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
    private GradientDrawable round(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w > 0 ? dp(w) : w, h > 0 ? dp(h) : h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w > 0 ? dp(w) : w, h > 0 ? dp(h) : h, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private int dp(int v) { return (int) (v * activity.getResources().getDisplayMetrics().density + .5f); }
}
