package app.githubble.mobile.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Question from the agent: a short heading, the question as readable text, and one card per option
 * (bold title, muted description, a "recommended" badge on the suggested one), a free-answer card and "Пропустить".
 */
public final class QuestionSheet {
    private QuestionSheet() {}

    public static final class Option {
        public final String label, description;
        public final boolean recommended;
        public Option(String label, String description, boolean recommended) {
            this.label = label == null ? "" : label;
            this.description = description == null ? "" : description;
            this.recommended = recommended;
        }
    }

    public interface Answer {
        void choose(int index);
        void custom();
        void skip();
    }

    public static SheetDialog create(final Activity host, String question, List<Option> options, final Answer answer) {
        final float density = host.getResources().getDisplayMetrics().density;
        final SheetDialog[] self = new SheetDialog[1];
        LinearLayout list = new LinearLayout(host);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < options.size(); i++) {
            final int index = i;
            Option option = options.get(i);
            list.addView(card(host, density, option.label, option.description, option.recommended, false, v -> {
                answer.choose(index);
                if (self[0] != null) self[0].dismiss();
            }), spacing(density));
        }
        list.addView(card(host, density, "Свой вариант…", "", false, true, v -> {
            if (self[0] != null) self[0].dismiss();
            answer.custom();
        }), spacing(density));
        SheetDialog dialog = new SheetDialog.Builder(host).setTitle("Вопрос агента").setMessage(question).setView(list)
                .setNegativeButton("Пропустить", (d, w) -> answer.skip()).create();
        self[0] = dialog;
        return dialog;
    }

    private static LinearLayout.LayoutParams spacing(float density) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = (int) (8 * density + .5f);
        return lp;
    }

    private static View card(Activity host, float density, String title, String description, boolean recommended,
            boolean quiet, View.OnClickListener click) {
        int accent = ConfirmSheet.accent;
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        int h = (int) (14 * density + .5f), v = (int) (11 * density + .5f);
        card.setPadding(h, v, h, v);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(14 * density);
        if (recommended) {
            shape.setColor(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)));
            shape.setStroke((int) (2 * density + .5f), accent);
        } else {
            shape.setColor(quiet ? Color.TRANSPARENT : ConfirmSheet.subtle);
            shape.setStroke((int) (1 * density + .5f), ConfirmSheet.border);
        }
        card.setBackground(shape);
        if (recommended) {
            TextView badge = text(host, "★ Рекомендую", 11, accent, true);
            card.addView(badge);
        }
        TextView head = text(host, title, 15, quiet ? accent : ConfirmSheet.text, true);
        card.addView(head);
        if (!description.isEmpty()) {
            TextView sub = text(host, description, 13, ConfirmSheet.muted, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = (int) (3 * density + .5f);
            card.addView(sub, lp);
        }
        card.setOnClickListener(click);
        return card;
    }

    private static TextView text(Activity host, String value, int size, int color, boolean bold) {
        TextView t = new TextView(host);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        t.setLineSpacing(0, 1.05f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
}
