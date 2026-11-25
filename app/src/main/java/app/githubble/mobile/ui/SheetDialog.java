package app.githubble.mobile.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The app's own popup, a drop-in for the system SheetDialog: same Builder calls, but a themed rounded card with
 * an icon, tinted buttons and tidy lists. Destructive actions get a red tone automatically.
 */
public class SheetDialog extends Dialog {
    public static final int BUTTON_POSITIVE = DialogInterface.BUTTON_POSITIVE;
    public static final int BUTTON_NEGATIVE = DialogInterface.BUTTON_NEGATIVE;
    public static final int BUTTON_NEUTRAL = DialogInterface.BUTTON_NEUTRAL;
    private static final int DANGER = 0xFFD32F2F;
    private static final Pattern DESTRUCTIVE = Pattern.compile("(?iu)(удал|откат|сброс|очист|стер|отозв|отключ|выйти|выход|разлог|принудит|force|delete|remove|reset|wipe|revoke)");

    private final Context context;
    private CharSequence title = "", message = "";
    private View customTitle, content;
    private CharSequence[] items;
    private int choiceMode; // 0 plain, 1 single, 2 multi
    private int checkedItem = -1;
    private boolean[] checkedItems;
    private ListAdapter adapter;
    private DialogInterface.OnClickListener itemListener;
    private DialogInterface.OnMultiChoiceClickListener multiListener;
    private final Button positive, negative, neutral;
    private CharSequence positiveText, negativeText, neutralText;
    private LinearLayout card, header, body, buttons;
    private TextView titleView, messageView, iconView;
    private final List<View[]> rows = new ArrayList<>();
    private boolean built;

    public SheetDialog(Context context) {
        super(context);
        this.context = context;
        positive = makeButton(); negative = makeButton(); neutral = makeButton();
    }

    // ---- public SheetDialog-compatible surface ----

    public Button getButton(int which) {
        return which == BUTTON_POSITIVE ? positive : which == BUTTON_NEGATIVE ? negative : neutral;
    }

    public void setMessage(CharSequence value) {
        message = value == null ? "" : value;
        if (messageView != null) { messageView.setText(message); messageView.setVisibility(message.length() == 0 ? View.GONE : View.VISIBLE); }
    }

    @Override public void setTitle(CharSequence value) {
        title = value == null ? "" : value;
        if (titleView != null) titleView.setText(title);
    }

    @Override public void setTitle(int resId) { setTitle(context.getString(resId)); }

    public void setView(View view) { content = view; if (built) { body.removeAllViews(); fillBody(); } }

    public void setButton(int which, CharSequence label, DialogInterface.OnClickListener listener) {
        if (which == BUTTON_POSITIVE) positiveText = label; else if (which == BUTTON_NEGATIVE) negativeText = label; else neutralText = label;
        Button b = getButton(which);
        b.setText(label);
        b.setOnClickListener(v -> { if (listener != null) listener.onClick(this, which); dismiss(); });
        if (built) layoutButtons();
    }

    /** Unticks one row of a multi-choice list (used to enforce limits). */
    public void uncheck(int index) {
        if (checkedItems != null && index >= 0 && index < checkedItems.length) { checkedItems[index] = false; refreshRows(); }
    }

    // ---- building ----

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    @Override public void show() {
        build();
        super.show();
    }

    @Override protected void onStart() {
        super.onStart();
        if (card != null) {
            card.setAlpha(0f); card.setScaleX(.92f); card.setScaleY(.92f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start();
        }
    }

    private void build() {
        if (built) return;
        built = true;
        boolean danger = isDanger();
        int tone = danger ? DANGER : ConfirmSheet.accent;
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(16));
        card.setBackground(shape(ConfirmSheet.bg, 26, ConfirmSheet.border, 1));

        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (customTitle != null) header.addView(customTitle, new LinearLayout.LayoutParams(-1, -2));
        else if (title.length() > 0) {
            iconView = label(glyph(danger), 17, tone, true);
            iconView.setGravity(Gravity.CENTER);
            iconView.setBackground(shape(Color.argb(34, Color.red(tone), Color.green(tone), Color.blue(tone)), 14, 0, 0));
            header.addView(iconView, new LinearLayout.LayoutParams(dp(40), dp(40)));
            titleView = label(String.valueOf(title), 19, ConfirmSheet.text, true);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
            tp.leftMargin = dp(12);
            header.addView(titleView, tp);
        }
        if (header.getChildCount() > 0) card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        MaxScroll scroll = new MaxScroll(context, (int) (context.getResources().getDisplayMetrics().heightPixels * .6f));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = header.getChildCount() > 0 ? dp(12) : 0;
        card.addView(scroll, sp);
        fillBody();

        buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(18);
        card.addView(buttons, bp);
        styleButtons(tone);
        layoutButtons();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(card);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = .5f;
            window.setAttributes(lp);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setLayout(Math.min(dp(400), (int) (context.getResources().getDisplayMetrics().widthPixels * .92f)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void fillBody() {
        messageView = label(String.valueOf(message), 14, ConfirmSheet.text, false);
        messageView.setLineSpacing(dp(2), 1f);
        messageView.setVisibility(message.length() == 0 ? View.GONE : View.VISIBLE);
        body.addView(messageView, new LinearLayout.LayoutParams(-1, -2));
        if (content != null) {
            if (content.getParent() instanceof ViewGroup) ((ViewGroup) content.getParent()).removeView(content);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.topMargin = message.length() == 0 ? 0 : dp(10);
            body.addView(content, cp);
        }
        rows.clear();
        if (adapter != null) {
            for (int i = 0; i < adapter.getCount(); i++) {
                final int index = i;
                View item = adapter.getView(i, null, body);
                addRow(item, index, null);
            }
        } else if (items != null) {
            for (int i = 0; i < items.length; i++) {
                final int index = i;
                TextView text = label(String.valueOf(items[i]), 15, ConfirmSheet.text, false);
                addRow(text, index, items[i]);
            }
        }
    }

    private void addRow(View inner, final int index, CharSequence unused) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView mark = null;
        if (choiceMode != 0) {
            mark = label("", 15, Color.WHITE, true);
            mark.setGravity(Gravity.CENTER);
            row.addView(mark, new LinearLayout.LayoutParams(dp(24), dp(24)));
        }
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
        if (choiceMode != 0) ip.leftMargin = dp(12);
        if (inner.getParent() instanceof ViewGroup) ((ViewGroup) inner.getParent()).removeView(inner);
        row.addView(inner, ip);
        row.setOnClickListener(v -> {
            if (choiceMode == 2) {
                checkedItems[index] = !checkedItems[index];
                refreshRows();
                if (multiListener != null) multiListener.onClick(this, index, checkedItems[index]);
            } else if (choiceMode == 1) {
                checkedItem = index;
                refreshRows();
                if (itemListener != null) itemListener.onClick(this, index);
            } else {
                if (itemListener != null) itemListener.onClick(this, index);
                dismiss();
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = dp(8);
        body.addView(row, rp);
        rows.add(new View[]{row, mark});
        refreshRows();
    }

    private void refreshRows() {
        int accent = ConfirmSheet.accent;
        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i)[0];
            TextView mark = (TextView) rows.get(i)[1];
            boolean on = choiceMode == 2 ? checkedItems != null && i < checkedItems.length && checkedItems[i] : choiceMode == 1 && i == checkedItem;
            row.setBackground(on ? shape(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)), 14, accent, 2)
                    : shape(ConfirmSheet.subtle, 14, ConfirmSheet.border, 1));
            if (mark != null) {
                GradientDrawable box = new GradientDrawable();
                box.setShape(choiceMode == 1 ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
                if (choiceMode == 2) box.setCornerRadius(dp(7));
                box.setColor(on ? accent : Color.TRANSPARENT);
                box.setStroke(dp(2), on ? accent : ConfirmSheet.muted);
                mark.setBackground(box);
                mark.setText(on ? (choiceMode == 1 ? "•" : "✓") : "");
            }
        }
    }

    private Button makeButton() {
        Button b = new Button(context) {
            @Override public void setEnabled(boolean enabled) { super.setEnabled(enabled); setAlpha(enabled ? 1f : .45f); }
        };
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setStateListAnimator(null);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setSingleLine(false);
        return b;
    }

    private void styleButtons(int tone) {
        positive.setTextColor(Color.WHITE);
        positive.setBackground(shape(tone, 15, 0, 0));
        negative.setTextColor(ConfirmSheet.text);
        negative.setBackground(shape(Color.TRANSPARENT, 15, ConfirmSheet.border, 2));
        neutral.setTextColor(ConfirmSheet.accent);
        neutral.setBackground(shape(ConfirmSheet.subtle, 15, 0, 0));
    }

    private void layoutButtons() {
        buttons.removeAllViews();
        boolean hasPositive = positiveText != null && positiveText.length() > 0;
        boolean hasNegative = negativeText != null && negativeText.length() > 0;
        boolean hasNeutral = neutralText != null && neutralText.length() > 0;
        if (!hasPositive && !hasNegative && !hasNeutral) {
            if (items == null && adapter == null) { buttons.setVisibility(View.GONE); return; }
            negative.setText("Закрыть");
            negative.setOnClickListener(v -> dismiss());
            hasNegative = true;
        }
        buttons.setVisibility(View.VISIBLE);
        if (!hasNeutral && (hasPositive != hasNegative || (hasPositive && hasNegative))) {
            LinearLayout row = new LinearLayout(context);
            if (hasNegative) row.addView(negative, new LinearLayout.LayoutParams(0, dp(48), 1f));
            if (hasPositive) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), hasNegative ? 1.3f : 1f);
                if (hasNegative) lp.leftMargin = dp(10);
                row.addView(positive, lp);
            }
            buttons.addView(row, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        if (hasPositive) buttons.addView(positive, stacked(false));
        if (hasNeutral) buttons.addView(neutral, stacked(hasPositive));
        if (hasNegative) buttons.addView(negative, stacked(hasPositive || hasNeutral));
    }

    private LinearLayout.LayoutParams stacked(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        if (gap) lp.topMargin = dp(8);
        return lp;
    }

    private boolean isDanger() {
        return positiveText != null && DESTRUCTIVE.matcher(positiveText).find()
                || positiveText != null && DESTRUCTIVE.matcher(title).find();
    }

    private String glyph(boolean danger) {
        if (danger) return "!";
        if (items != null || adapter != null) return "☰";
        if (content != null) return "✎";
        return negativeText == null && neutralText == null ? "i" : "?";
    }

    // ---- helpers ----

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

    private static final class MaxScroll extends ScrollView {
        private final int max;
        MaxScroll(Context context, int max) { super(context); this.max = max; }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }

    // ---- Builder ----

    public static class Builder {
        private final Context context;
        private final SheetDialog dialog;
        private DialogInterface.OnClickListener positiveListener, negativeListener, neutralListener;

        public Builder(Context context) { this.context = context; this.dialog = new SheetDialog(context); }
        public Builder(Context context, int theme) { this(context); }

        public Context getContext() { return context; }
        public Builder setTitle(CharSequence title) { dialog.title = title == null ? "" : title; return this; }
        public Builder setTitle(int resId) { return setTitle(context.getString(resId)); }
        public Builder setCustomTitle(View view) { dialog.customTitle = view; return this; }
        public Builder setMessage(CharSequence message) { dialog.message = message == null ? "" : message; return this; }
        public Builder setMessage(int resId) { return setMessage(context.getString(resId)); }
        public Builder setIcon(int resId) { return this; }
        public Builder setView(View view) { dialog.content = view; return this; }
        public Builder setCancelable(boolean cancelable) { dialog.setCancelable(cancelable); return this; }
        public Builder setOnDismissListener(DialogInterface.OnDismissListener l) { dialog.setOnDismissListener(l); return this; }
        public Builder setOnCancelListener(DialogInterface.OnCancelListener l) { dialog.setOnCancelListener(l); return this; }

        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) { positiveListener = l; dialog.setButtonQuiet(BUTTON_POSITIVE, text, l); return this; }
        public Builder setPositiveButton(int resId, DialogInterface.OnClickListener l) { return setPositiveButton(context.getString(resId), l); }
        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) { negativeListener = l; dialog.setButtonQuiet(BUTTON_NEGATIVE, text, l); return this; }
        public Builder setNegativeButton(int resId, DialogInterface.OnClickListener l) { return setNegativeButton(context.getString(resId), l); }
        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener l) { neutralListener = l; dialog.setButtonQuiet(BUTTON_NEUTRAL, text, l); return this; }
        public Builder setNeutralButton(int resId, DialogInterface.OnClickListener l) { return setNeutralButton(context.getString(resId), l); }

        public Builder setItems(CharSequence[] items, DialogInterface.OnClickListener l) {
            dialog.items = items; dialog.choiceMode = 0; dialog.itemListener = l; return this;
        }
        public Builder setSingleChoiceItems(CharSequence[] items, int checked, DialogInterface.OnClickListener l) {
            dialog.items = items; dialog.choiceMode = 1; dialog.checkedItem = checked; dialog.itemListener = l; return this;
        }
        public Builder setMultiChoiceItems(CharSequence[] items, boolean[] checked, DialogInterface.OnMultiChoiceClickListener l) {
            dialog.items = items; dialog.choiceMode = 2;
            dialog.checkedItems = checked != null ? checked : new boolean[items.length]; dialog.multiListener = l; return this;
        }
        public Builder setAdapter(ListAdapter adapter, DialogInterface.OnClickListener l) {
            dialog.adapter = adapter; dialog.choiceMode = 0; dialog.itemListener = l; return this;
        }

        public SheetDialog create() { return dialog; }
        public SheetDialog show() { dialog.show(); return dialog; }
    }

    /** Stores a button before the card exists. */
    private void setButtonQuiet(int which, CharSequence label, DialogInterface.OnClickListener listener) {
        setButton(which, label, listener);
    }
}
