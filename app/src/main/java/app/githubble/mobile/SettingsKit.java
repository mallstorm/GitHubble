package app.githubble.mobile;

import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import app.githubble.mobile.repository.Gh;
import app.githubble.mobile.ui.SheetDialog;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Shared building blocks for the repository settings screens: async load/run with permission-aware errors, forms and pickers. */
final class SettingsKit {
    interface Job { void run() throws Exception; }
    interface Loader<T> { T load() throws Exception; }
    interface Show<T> { void show(T value); }

    final MainActivity a;
    SettingsKit(MainActivity activity) { this.a = activity; }

    /** Starts a settings screen. Returns false (and draws the sign-in card) when no account is connected. */
    boolean open(String full, String title, String details, Runnable self) {
        a.setCurrentScreen(self);
        a.shell(title, false);
        a.repoSectionHeader(full, "Настройки", details, R.drawable.ic_settings);
        if (!a.isSignedIn()) { a.accountRequiredCard("Подключите GitHub, чтобы менять настройки репозитория."); return false; }
        return true;
    }

    TextView note(String text) { TextView t = a.text(text, 12, false); t.setTextColor(a.MUTED); a.card(t); return t; }

    Button action(String label, Runnable run) {
        Button b = a.button(label);
        b.setOnClickListener(v -> run.run());
        a.content.addView(b, new LinearLayout.LayoutParams(-1, a.dp(50)));
        return b;
    }

    /** Loads off the UI thread; the result is drawn into the screen that asked for it, unless the user has left. */
    <T> void load(String permission, Loader<T> loader, Show<T> shown) {
        final long generation = a.screenGeneration.get();
        final LinearLayout host = a.content;
        final TextView status = a.text("Загружаем…", 12, false);
        status.setTextColor(a.MUTED); a.card(status);
        a.io.execute(() -> {
            try {
                final T value = loader.load();
                a.runOnUiThread(() -> {
                    if (generation != a.screenGeneration.get() || host != a.content) return;
                    status.setVisibility(View.GONE);
                    shown.show(value);
                });
            } catch (Exception error) {
                final String text = Gh.explain(error, permission);
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) { status.setText(text); status.setTextColor(0xFFC34F48); } });
            }
        });
    }

    /** Runs a write off the UI thread, then refreshes through {@code then}. */
    void run(String permission, String done, Runnable then, Job job) {
        a.io.execute(() -> {
            try {
                job.run();
                a.runOnUiThread(() -> { if (done != null) a.toast(done); if (then != null) then.run(); });
            } catch (Exception error) {
                final String text = error instanceof IllegalArgumentException ? String.valueOf(error.getMessage()) : Gh.explain(error, permission);
                a.runOnUiThread(() -> a.errorDialog("Не выполнено", text));
            }
        });
    }

    void confirm(String title, String message, String yes, Runnable go) {
        new SheetDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton(yes, (d, w) -> go.run()).setNegativeButton("Отмена", null).show();
    }

    LinearLayout form() { LinearLayout f = new LinearLayout(a); f.setOrientation(LinearLayout.VERTICAL); f.setPadding(a.dp(20), 0, a.dp(20), 0); return f; }

    /** The dialog body already scrolls, so the form is used as it is. */
    View scroll(LinearLayout form) { return form; }

    EditText field(LinearLayout into, String hint, String value, boolean multiline) {
        EditText e = new EditText(a);
        e.setHint(hint); e.setText(value == null ? "" : value); e.setTextColor(a.TEXT); e.setHintTextColor(a.MUTED);
        if (multiline) { e.setMinLines(2); e.setGravity(android.view.Gravity.TOP | android.view.Gravity.START); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); }
        else { e.setSingleLine(true); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); }
        into.addView(e);
        return e;
    }

    CheckBox check(LinearLayout into, String label, boolean on) {
        CheckBox c = new CheckBox(a); c.setText(label); c.setTextColor(a.TEXT); c.setChecked(on); into.addView(c);
        return c;
    }

    RadioGroup radios(LinearLayout into, String[] labels, int selected) {
        RadioGroup g = new RadioGroup(a);
        for (int i = 0; i < labels.length; i++) { RadioButton r = new RadioButton(a); r.setId(1000 + i); r.setText(labels[i]); r.setTextColor(a.TEXT); g.addView(r); }
        g.check(1000 + Math.max(0, Math.min(selected, labels.length - 1)));
        into.addView(g);
        return g;
    }

    static int selected(RadioGroup g) { return g.getCheckedRadioButtonId() - 1000; }

    void choose(String title, String[] labels, IntConsumer pick) {
        new SheetDialog.Builder(a).setTitle(title).setItems(labels, (d, which) -> pick.accept(which)).setNegativeButton("Отмена", null).show();
    }

    /** A card that holds one list entry; the caller fills it. */
    LinearLayout entry() {
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(14), a.dp(10), a.dp(12), a.dp(10)); box.setBackground(a.round(a.CARD, 14, a.BORDER, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, a.dp(8), 0, 0);
        box.setLayoutParams(lp);
        return box;
    }

    TextView line(LinearLayout box, String text, int sp, boolean bold, int color) {
        TextView t = a.text(text, sp, bold); t.setTextColor(color); t.setPadding(0, a.dp(2), 0, 0); box.addView(t);
        return t;
    }

    /** A horizontal strip of compact buttons under a list entry. */
    LinearLayout buttons(LinearLayout box) {
        LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0, a.dp(6), 0, 0);
        box.addView(row);
        return row;
    }

    Button small(LinearLayout row, String label, Runnable run) {
        Button b = a.compactButton(label);
        b.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, a.dp(40), 1f); lp.setMargins(0, 0, a.dp(6), 0);
        row.addView(b, lp);
        return b;
    }

    /** A form dialog: {@code prepare} validates the fields (throwing IllegalArgumentException keeps the dialog open) and returns the write to run. */
    void form(String title, String ok, View view, String permission, String done, Runnable then, Supplier<Job> prepare) {
        SheetDialog dialog = new SheetDialog.Builder(a).setTitle(title).setView(view).setPositiveButton(ok, null).setNegativeButton("Отмена", null).show();
        dialog.setOnShowListener(x -> dialog.getButton(-1).setOnClickListener(w -> {
            final Job job;
            try { job = prepare.get(); }
            catch (IllegalArgumentException bad) { a.toast(String.valueOf(bad.getMessage())); return; }
            dialog.dismiss();
            run(permission, done, then, job);
        }));
    }

    static String date(String iso) { return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : (iso == null ? "" : iso); }
}
