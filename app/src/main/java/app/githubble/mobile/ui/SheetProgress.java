package app.githubble.mobile.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Themed progress popup, a drop-in for the old system SheetProgress: spinner or bar, title, message, percent. */
public class SheetProgress extends Dialog {
    public static final int STYLE_SPINNER = 0, STYLE_HORIZONTAL = 1;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CharSequence title = "", message = "";
    private int style = STYLE_SPINNER, max = 100, progress;
    private boolean indeterminate = true, built;
    private LinearLayout card;
    private TextView titleView, messageView, percentView;
    private ProgressBar spinner, bar;

    public SheetProgress(Context context) {
        super(context);
        this.context = context;
        setCancelable(false);
        setCanceledOnTouchOutside(false);
    }

    public static SheetProgress show(Context context, CharSequence title, CharSequence message) { return show(context, title, message, true, false); }
    public static SheetProgress show(Context context, CharSequence title, CharSequence message, boolean indeterminate) { return show(context, title, message, indeterminate, false); }
    public static SheetProgress show(Context context, CharSequence title, CharSequence message, boolean indeterminate, boolean cancelable) {
        SheetProgress dialog = new SheetProgress(context);
        dialog.title = title == null ? "" : title;
        dialog.message = message == null ? "" : message;
        dialog.indeterminate = indeterminate;
        dialog.setCancelable(cancelable);
        dialog.show();
        return dialog;
    }

    @Override public void setTitle(CharSequence value) { title = value == null ? "" : value; ui(() -> { if (titleView != null) { titleView.setText(title); titleView.setVisibility(title.length() == 0 ? android.view.View.GONE : android.view.View.VISIBLE); } }); }
    @Override public void setTitle(int resId) { setTitle(context.getString(resId)); }
    public void setMessage(CharSequence value) { message = value == null ? "" : value; ui(() -> { if (messageView != null) { messageView.setText(message); messageView.setVisibility(message.length() == 0 ? android.view.View.GONE : android.view.View.VISIBLE); } }); }
    public void setProgressStyle(int value) { style = value; if (value == STYLE_HORIZONTAL) indeterminate = false; }
    public void setIndeterminate(boolean value) { indeterminate = value; ui(this::apply); }
    public void setMax(int value) { max = Math.max(1, value); ui(this::apply); }
    public void setProgress(int value) { progress = value; ui(this::apply); }
    public int getProgress() { return progress; }

    private void ui(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }

    private void apply() {
        if (!built || bar == null) return;
        boolean horizontal = style == STYLE_HORIZONTAL;
        bar.setVisibility(horizontal ? android.view.View.VISIBLE : android.view.View.GONE);
        spinner.setVisibility(horizontal ? android.view.View.GONE : android.view.View.VISIBLE);
        bar.setIndeterminate(indeterminate);
        bar.setMax(max);
        bar.setProgress(progress);
        percentView.setVisibility(horizontal && !indeterminate ? android.view.View.VISIBLE : android.view.View.GONE);
        percentView.setText(Math.min(100, Math.round(progress * 100f / max)) + "%");
    }

    @Override protected void onCreate(Bundle state) { super.onCreate(state); build(); }

    @Override public void show() { build(); super.show(); }

    @Override protected void onStart() {
        super.onStart();
        if (card != null) {
            card.setAlpha(0f); card.setScaleX(.94f); card.setScaleY(.94f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
        }
    }

    private void build() {
        if (built) return;
        built = true;
        int accent = ConfirmSheet.accent;
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(shape(ConfirmSheet.bg, 24, ConfirmSheet.border, 1));

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new ProgressBar(context);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(accent));
        top.addView(spinner, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        titleView = label(String.valueOf(title), 17, ConfirmSheet.text, true);
        titleView.setVisibility(title.length() == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
        words.addView(titleView);
        messageView = label(String.valueOf(message), 13, ConfirmSheet.muted, false);
        messageView.setVisibility(message.length() == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(2);
        words.addView(messageView, mp);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        wp.leftMargin = dp(14);
        top.addView(words, wp);
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));

        bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(ConfirmSheet.border));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(8));
        bp.topMargin = dp(16);
        card.addView(bar, bp);
        percentView = label("0%", 12, ConfirmSheet.muted, true);
        percentView.setGravity(Gravity.END);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.topMargin = dp(6);
        card.addView(percentView, pp);
        apply();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(card);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = .5f;
            window.setAttributes(lp);
            window.setLayout(Math.min(dp(360), (int) (context.getResources().getDisplayMetrics().widthPixels * .9f)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView t = new TextView(context);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable shape(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private int dp(int v) { return (int) (v * context.getResources().getDisplayMetrics().density + .5f); }
}
