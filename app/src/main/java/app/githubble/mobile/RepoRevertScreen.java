package app.githubble.mobile;

import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import app.githubble.mobile.repository.Gh;
import app.githubble.mobile.repository.RepoRevert;

/** Repository «Откат»: roll a branch back by N commits with one new commit (history is kept), directly or through a new branch and pull request. */
final class RepoRevertScreen {
    private final MainActivity a;
    private final SettingsKit k;
    RepoRevertScreen(MainActivity activity, SettingsKit kit) { this.a = activity; this.k = kit; }

    void show(String full) {
        if (!k.open(full, "Откат (revert)", "Откат на N шагов", () -> show(full))) return;
        k.note("Возвращает ветку к состоянию N коммитов назад. Создаётся один новый коммит, история не переписывается и ничего не теряется: откат можно сам откатить тем же способом. "
            + "Шаги считаются по основной линии ветки (первые родители), merge-коммит считается одним шагом.");
        k.load("Contents", () -> Gh.object(a.api, Gh.repo(full)).optString("default_branch", "main"), def -> {
            final String[] branch = {def};
            LinearLayout form = k.form(); form.setPadding(0, 0, 0, 0);
            k.line(form, "Ветка", 13, true, a.TEXT);
            final Button branchButton = a.compactButton("Ветка: " + def);
            branchButton.setOnClickListener(v -> k.pickBranch(full, name -> { branch[0] = name; branchButton.setText("Ветка: " + name); }));
            form.addView(branchButton, new LinearLayout.LayoutParams(-1, a.dp(44)));
            k.line(form, "На сколько шагов откатить", 13, true, a.TEXT);
            final EditText steps = k.field(form, "Число шагов (1–" + RepoRevert.MAX_STEPS + ")", "1", false);
            steps.setInputType(InputType.TYPE_CLASS_NUMBER);
            LinearLayout quick = k.buttons(form);
            for (final String n : new String[]{"1", "2", "3", "5", "10"}) k.small(quick, n, () -> steps.setText(n));
            final LinearLayout result = new LinearLayout(a); result.setOrientation(LinearLayout.VERTICAL);
            Button preview = a.button("Показать, что будет откачено");
            preview.setOnClickListener(v -> preview(full, branch[0], steps.getText().toString(), result));
            form.addView(preview, new LinearLayout.LayoutParams(-1, a.dp(50)));
            a.content.addView(form);
            a.content.addView(result);
        });
    }

    private void preview(String full, String branch, String rawSteps, LinearLayout result) {
        final int steps;
        try { steps = RepoRevert.checkSteps(rawSteps); } catch (IllegalArgumentException bad) { a.toast(bad.getMessage()); return; }
        result.removeAllViews();
        final TextView status = k.line(result, "Читаем историю…", 12, false, a.MUTED);
        final long generation = a.screenGeneration.get();
        final LinearLayout host = a.content;
        a.io.execute(() -> {
            try {
                final RepoRevert.Plan plan = RepoRevert.plan(a.api, full, branch, steps);
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) draw(full, plan, steps, result); });
            } catch (Exception error) {
                final String text = error instanceof IllegalArgumentException ? String.valueOf(error.getMessage()) : Gh.explain(error, "Contents");
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) { status.setText(text); status.setTextColor(0xFFC34F48); } });
            }
        });
    }

    private void draw(String full, RepoRevert.Plan plan, int steps, LinearLayout result) {
        result.removeAllViews();
        a.sectionTitle("Что изменится", null);
        LinearLayout summary = k.entry();
        k.line(summary, "Ветка «" + plan.branch + "» вернётся к состоянию коммита " + plan.target.shortSha() + (plan.target.title.isEmpty() ? "" : " — " + plan.target.title), 14, true, a.TEXT);
        k.line(summary, "Будет отменено коммитов: " + plan.undone.size() + (plan.files >= 0 ? " · файлов затронуто: " + plan.files + " (+" + plan.additions + " −" + plan.deletions + ")" : ""), 12, false, a.MUTED);
        result.addView(summary);
        for (RepoRevert.Commit c : plan.undone) {
            LinearLayout row = k.entry();
            k.line(row, c.shortSha() + "  " + (c.title.isEmpty() ? "(без сообщения)" : c.title), 13, true, a.TEXT);
            k.line(row, c.author + (c.date.isEmpty() ? "" : " · " + SettingsKit.date(c.date)), 12, false, a.MUTED);
            result.addView(row);
        }
        a.sectionTitle("Как применить", null);
        LinearLayout form = k.form(); form.setPadding(0, 0, 0, 0);
        final EditText message = k.field(form, "Сообщение коммита", RepoRevert.defaultMessage(steps, plan.target), true);
        final RadioGroup mode = k.radios(form, new String[]{"Прямо в ветку «" + plan.branch + "»", "В новую ветку"}, 0);
        final EditText name = k.field(form, "Имя новой ветки", RepoRevert.defaultBranchName(plan.branch, steps), false);
        final CheckBox pr = k.check(form, "Создать pull request в «" + plan.branch + "»", true);
        Button apply = a.button("Откатить");
        apply.setTextColor(0xFFC34F48);
        apply.setOnClickListener(v -> {
            final boolean toBranch = SettingsKit.selected(mode) == 1;
            final String text = message.getText().toString(), branchName = name.getText().toString();
            final boolean openPr = pr.isChecked();
            k.confirm("Откатить на " + steps + " шаг(ов)?", toBranch ? "Будет создана ветка и коммит с состоянием " + plan.target.shortSha() + "." : "В ветку «" + plan.branch + "» будет добавлен коммит с состоянием " + plan.target.shortSha() + ". История сохранится.", "Откатить", () -> {
                final RepoRevert.Result[] done = new RepoRevert.Result[1];
                k.run("Contents", null, () -> {
                    RepoRevert.Result r = done[0];
                    a.errorDialog("Откат выполнен", "Коммит " + r.commit.substring(0, 7) + (r.branch == null ? " добавлен в «" + plan.branch + "»." : " создан в ветке «" + r.branch + "».") + (r.pullRequestUrl.isEmpty() ? "" : "\nPull request: " + r.pullRequestUrl));
                    show(full);
                }, () -> done[0] = RepoRevert.apply(a.api, full, plan, text, toBranch ? branchName : null, openPr));
            });
        });
        form.addView(apply, new LinearLayout.LayoutParams(-1, a.dp(50)));
        result.addView(form);
    }
}
