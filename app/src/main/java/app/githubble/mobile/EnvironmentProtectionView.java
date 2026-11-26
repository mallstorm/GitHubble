package app.githubble.mobile;

import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.githubble.mobile.repository.EnvironmentRules;
import app.githubble.mobile.repository.EnvironmentRules.Policy;
import app.githubble.mobile.ui.SheetDialog;
import java.util.List;

/** Environment «Защита»: wait timer, required reviewers, self-review ban and deployment branches — as on the GitHub site. */
final class EnvironmentProtectionView {
    private final MainActivity a;
    EnvironmentProtectionView(MainActivity activity) { this.a = activity; }

    void render(String full, String env, Runnable reload) {
        final long generation = a.screenGeneration.get();
        final LinearLayout host = a.content;
        final TextView status = a.text("Загружаем правила…", 12, false);
        status.setTextColor(a.MUTED); a.card(status);
        final LinearLayout body = new LinearLayout(a); body.setOrientation(LinearLayout.VERTICAL);
        a.content.addView(body);
        a.io.execute(() -> {
            try {
                final Policy policy = EnvironmentRules.load(a.api, full, env);
                final List<EnvironmentRules.BranchRule> patterns = EnvironmentRules.CUSTOM.equals(policy.branches) ? EnvironmentRules.branchRules(a.api, full, env) : null;
                a.runOnUiThread(() -> {
                    if (generation != a.screenGeneration.get() || host != a.content) return;
                    status.setText("Правила окружения «" + env + "». Проверяющие, ожидание и ограничения веток в приватных репозиториях доступны на платных тарифах GitHub.");
                    draw(body, full, env, policy, patterns, reload);
                });
            } catch (Exception error) {
                final String text = EnvironmentRules.explain(error);
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) { status.setText(text); status.setTextColor(0xFFC34F48); } });
            }
        });
    }

    private TextView title(String text) { TextView t = a.text(text, 15, true); t.setPadding(0, a.dp(14), 0, a.dp(4)); return t; }

    private void draw(LinearLayout body, String full, String env, Policy policy, List<EnvironmentRules.BranchRule> patterns, Runnable reload) {
        body.removeAllViews();
        // Required reviewers
        body.addView(title("Обязательные проверяющие"));
        TextView hint = a.text("Задание workflow с этим окружением ждёт одобрения одного из них (до шести). Пользователь — логин, команда — организация/команда.", 12, false);
        hint.setTextColor(a.MUTED); body.addView(hint);
        for (EnvironmentRules.Reviewer r : policy.reviewers) {
            LinearLayout row = new LinearLayout(a); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(a.dp(12), a.dp(6), a.dp(6), a.dp(6)); row.setBackground(a.round(a.CARD, 12, a.BORDER, 1));
            row.addView(a.text(("Team".equals(r.type) ? "👥  " : "👤  ") + r.label, 14, false), new LinearLayout.LayoutParams(0, -2, 1));
            Button remove = a.compactButton("✕"); remove.setTextColor(0xFFC34F48);
            remove.setOnClickListener(v -> { policy.reviewers.remove(r); draw(body, full, env, policy, patterns, reload); });
            row.addView(remove, new LinearLayout.LayoutParams(a.dp(48), a.dp(38)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, a.dp(6), 0, 0);
            body.addView(row, lp);
        }
        if (policy.reviewers.size() < EnvironmentRules.MAX_REVIEWERS) {
            Button add = a.compactButton("+  Добавить проверяющего");
            add.setOnClickListener(v -> addReviewer(policy, () -> draw(body, full, env, policy, patterns, reload)));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, a.dp(42)); ap.setMargins(0, a.dp(8), 0, 0);
            body.addView(add, ap);
        }
        final CheckBox self = new CheckBox(a); self.setText("Запретить одобрять тому, кто запустил задание"); self.setTextColor(a.TEXT);
        self.setChecked(policy.preventSelfReview); self.setOnCheckedChangeListener((b, on) -> policy.preventSelfReview = on);
        body.addView(self);

        // Wait timer
        body.addView(title("Ожидание перед запуском"));
        final EditText wait = new EditText(a); wait.setSingleLine(true); wait.setInputType(InputType.TYPE_CLASS_NUMBER);
        wait.setHint("Минуты, 0 — без ожидания (максимум 43200)"); wait.setTextColor(a.TEXT); wait.setHintTextColor(a.MUTED);
        wait.setText(policy.waitMinutes == 0 ? "" : String.valueOf(policy.waitMinutes));
        body.addView(wait);

        // Deployment branches
        body.addView(title("Ветки, которым можно запускаться"));
        String[] labels = {"Любые ветки и теги", "Только защищённые ветки", "Выбранные ветки и теги"};
        String[] modes = {EnvironmentRules.ALL, EnvironmentRules.PROTECTED, EnvironmentRules.CUSTOM};
        LinearLayout modeRow = new LinearLayout(a); modeRow.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 3; i++) {
            final int index = i; boolean active = modes[i].equals(policy.branches);
            Button b = a.compactButton((active ? "●  " : "○  ") + labels[i]);
            b.setTextColor(active ? 0xFFFFFFFF : a.GREEN);
            b.setBackground(a.round(active ? a.GREEN : a.CARD, 12, active ? a.GREEN : a.BORDER, active ? 0 : 1));
            b.setOnClickListener(v -> {
                policy.waitMinutes = parse(wait);
                policy.branches = modes[index];
                if (EnvironmentRules.CUSTOM.equals(modes[index]) && patterns == null) saveThen(full, env, policy, reload);
                else draw(body, full, env, policy, patterns, reload);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(42)); lp.setMargins(0, a.dp(6), 0, 0);
            modeRow.addView(b, lp);
        }
        body.addView(modeRow);
        if (patterns != null && EnvironmentRules.CUSTOM.equals(policy.branches)) {
            for (EnvironmentRules.BranchRule rule : patterns) {
                LinearLayout row = new LinearLayout(a); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(a.dp(12), a.dp(6), a.dp(6), a.dp(6)); row.setBackground(a.round(a.CARD, 12, a.BORDER, 1));
                row.addView(a.text(("tag".equals(rule.type) ? "🏷  " : "⎇  ") + rule.name, 14, false), new LinearLayout.LayoutParams(0, -2, 1));
                Button remove = a.compactButton("✕"); remove.setTextColor(0xFFC34F48);
                remove.setOnClickListener(v -> a.io.execute(() -> {
                    try { EnvironmentRules.deleteBranchRule(a.api, full, env, rule.id); a.runOnUiThread(reload); }
                    catch (Exception e) { final String m = EnvironmentRules.explain(e); a.runOnUiThread(() -> a.errorDialog("Не удалено", m)); }
                }));
                row.addView(remove, new LinearLayout.LayoutParams(a.dp(48), a.dp(38)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, a.dp(6), 0, 0);
                body.addView(row, lp);
            }
            Button add = a.compactButton("+  Добавить шаблон ветки или тега");
            add.setOnClickListener(v -> addPattern(full, env, reload));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, a.dp(42)); ap.setMargins(0, a.dp(8), 0, 0);
            body.addView(add, ap);
        }

        Button save = a.button("Сохранить правила");
        save.setOnClickListener(v -> { policy.waitMinutes = parse(wait); saveThen(full, env, policy, reload); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, a.dp(50)); sp.setMargins(0, a.dp(16), 0, 0);
        body.addView(save, sp);
    }

    private int parse(EditText field) {
        try { return field.getText().toString().trim().isEmpty() ? 0 : Integer.parseInt(field.getText().toString().trim()); }
        catch (NumberFormatException bad) { return -1; }
    }

    private void saveThen(String full, String env, Policy policy, Runnable reload) {
        a.toast("Сохраняем…");
        a.io.execute(() -> {
            try { EnvironmentRules.save(a.api, full, env, policy); a.runOnUiThread(() -> { a.toast("Правила сохранены"); reload.run(); }); }
            catch (Exception e) { final String m = e instanceof IllegalArgumentException ? e.getMessage() : EnvironmentRules.explain(e); a.runOnUiThread(() -> a.errorDialog("Не сохранено", m)); }
        });
    }

    private void addReviewer(Policy policy, Runnable redraw) {
        LinearLayout form = new LinearLayout(a); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(a.dp(20), 0, a.dp(20), 0);
        final EditText who = new EditText(a); who.setSingleLine(true); who.setHint("логин или организация/команда"); who.setTextColor(a.TEXT); who.setHintTextColor(a.MUTED);
        who.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(who);
        SheetDialog dialog = new SheetDialog.Builder(a).setTitle("Проверяющий").setView(form).setPositiveButton("Добавить", null).setNegativeButton("Отмена", null).show();
        dialog.setOnShowListener(x -> dialog.getButton(-1).setOnClickListener(w -> {
            final String input = who.getText().toString();
            dialog.dismiss();
            a.io.execute(() -> {
                try {
                    EnvironmentRules.Reviewer r = EnvironmentRules.resolve(a.api, input);
                    for (EnvironmentRules.Reviewer existing : policy.reviewers) if (existing.id == r.id && existing.type.equals(r.type)) throw new IllegalArgumentException("Уже добавлен");
                    a.runOnUiThread(() -> { policy.reviewers.add(r); redraw.run(); });
                } catch (Exception e) { final String m = e instanceof IllegalArgumentException ? e.getMessage() : EnvironmentRules.explain(e); a.runOnUiThread(() -> a.errorDialog("Проверяющий не добавлен", m)); }
            });
        }));
    }

    private void addPattern(String full, String env, Runnable reload) {
        LinearLayout form = new LinearLayout(a); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(a.dp(20), 0, a.dp(20), 0);
        final EditText name = new EditText(a); name.setSingleLine(true); name.setHint("main или release/*"); name.setTextColor(a.TEXT); name.setHintTextColor(a.MUTED);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final CheckBox tag = new CheckBox(a); tag.setText("Это шаблон тега, а не ветки"); tag.setTextColor(a.TEXT);
        form.addView(name); form.addView(tag);
        SheetDialog dialog = new SheetDialog.Builder(a).setTitle("Шаблон").setView(form).setPositiveButton("Добавить", null).setNegativeButton("Отмена", null).show();
        dialog.setOnShowListener(x -> dialog.getButton(-1).setOnClickListener(w -> {
            final String pattern = name.getText().toString(); final boolean isTag = tag.isChecked();
            dialog.dismiss();
            a.io.execute(() -> {
                try { EnvironmentRules.addBranchRule(a.api, full, env, pattern, isTag); a.runOnUiThread(reload); }
                catch (Exception e) { final String m = e instanceof IllegalArgumentException ? e.getMessage() : EnvironmentRules.explain(e); a.runOnUiThread(() -> a.errorDialog("Шаблон не добавлен", m)); }
            });
        }));
    }
}
