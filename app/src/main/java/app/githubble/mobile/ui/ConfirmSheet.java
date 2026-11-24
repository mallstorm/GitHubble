package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** One confirmation card for risky agent actions: icon, title, facts (repository, account…), note, Yes / No. */
public final class ConfirmSheet {
    /** What the card says. {@code danger} paints the icon and the Yes button red. */
    public static final class Spec {
        public final String title, yes, no;
        public final boolean danger;
        public final List<String[]> facts = new ArrayList<>();
        public String note = "";
        public Spec(String title, String yes, String no, boolean danger) {
            this.title = title; this.yes = yes; this.no = no; this.danger = danger;
        }
        public Spec fact(String label, String value) { facts.add(new String[]{label, value}); return this; }
        public Spec note(String text) { note = text == null ? "" : text; return this; }
    }

    private static volatile int bg = 0xFFFFFFFF, text = 0xFF1B2733, muted = 0xFF6B7A88, subtle = 0xFFF1F4F7, border = 0xFFDCE3E9, accent = 0xFF1565C0;

    private ConfirmSheet() {}

    /** The chat screen publishes its palette so the card matches the current theme. */
    public static void theme(int background, int textColor, int mutedColor, int subtleColor, int borderColor, int accentColor) {
        bg = background; text = textColor; muted = mutedColor; subtle = subtleColor; border = borderColor; accent = accentColor;
    }

    /** A message with one "OK" button, in place of the system alert dialog. */
    public static void info(Activity activity, String title, String message) {
        create(activity, new Spec(title, "Понятно", "", false).note(message), null, null).show();
    }

    public static Dialog create(Activity activity, Spec spec, Runnable yes, Runnable no) {
        final int danger = 0xFFD32F2F;
        final int tone = spec.danger ? danger : accent;
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(activity, 22), dp(activity, 22), dp(activity, 22), dp(activity, 18));
        card.setBackground(shape(activity, bg, 28, border, 1));

        TextView icon = label(activity, spec.danger ? "!" : "?", 28, Color.WHITE, true);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(tone);
        icon.setBackground(circle);
        card.addView(icon, new LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)));

        TextView title = label(activity, spec.title, 20, text, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(activity, 14);
        card.addView(title, titleLp);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        for (String[] fact : spec.facts) {
            TextView name = label(activity, fact[0].toUpperCase(java.util.Locale.ROOT), 10.5f, muted, true);
            name.setPadding(dp(activity, 4), dp(activity, 12), 0, dp(activity, 4));
            body.addView(name);
            TextView value = label(activity, fact[1], 15, text, true);
            value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            value.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10));
            value.setBackground(shape(activity, subtle, 14, border, 1));
            value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            value.setMaxLines(2);
            body.addView(value);
        }
        if (!spec.note.isEmpty()) {
            TextView note = label(activity, spec.note, 13, text, false);
            note.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10));
            note.setBackground(shape(activity, Color.argb(24, Color.red(tone), Color.green(tone), Color.blue(tone)), 14, 0, 0));
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
            noteLp.topMargin = dp(activity, 14);
            body.addView(note, noteLp);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(activity);
        final Dialog[] box = new Dialog[1];
        TextView noButton = label(activity, spec.no, 15, accent, true);
        noButton.setGravity(Gravity.CENTER);
        noButton.setBackground(shape(activity, Color.TRANSPARENT, 16, accent, 2));
        noButton.setOnClickListener(v -> { if (no != null) no.run(); box[0].dismiss(); });
        TextView yesButton = label(activity, spec.yes, 15, Color.WHITE, true);
        yesButton.setGravity(Gravity.CENTER);
        yesButton.setBackground(shape(activity, tone, 16, 0, 0));
        yesButton.setOnClickListener(v -> { if (yes != null) yes.run(); box[0].dismiss(); });
        LinearLayout.LayoutParams noLp = new LinearLayout.LayoutParams(0, dp(activity, 50), 1f);
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(0, dp(activity, 50), 1.4f);
        yesLp.leftMargin = dp(activity, 10);
        if (spec.no == null || spec.no.isEmpty()) yesLp = new LinearLayout.LayoutParams(0, dp(activity, 50), 1f);
        else buttons.addView(noButton, noLp);
        buttons.addView(yesButton, yesLp);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(-1, -2);
        buttonsLp.topMargin = dp(activity, 20);
        card.addView(buttons, buttonsLp);

        Dialog dialog = new Dialog(activity);
        box[0] = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(card);
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = .5f;
            window.setAttributes(lp);
            window.setLayout(Math.min(dp(activity, 380), (int) (activity.getResources().getDisplayMetrics().widthPixels * .92f)),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static TextView label(Activity a, String value, float size, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static GradientDrawable shape(Activity a, int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(a, radiusDp));
        if (strokeDp > 0) d.setStroke(dp(a, strokeDp), stroke);
        return d;
    }

    private static int dp(Activity a, int v) { return (int) (v * a.getResources().getDisplayMetrics().density + .5f); }
}
