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
import java.util.List;

/** Repository «Откат»: roll a branch back with one new commit (history is kept) by N steps, to a tag/commit/date, or undo a single commit. */
final class RepoRevertScreen {
    private static final String[] MODES = {"На N шагов назад", "К тегу или коммиту", "К дате", "Отменить один коммит"};
    private final MainActivity a;
    private final SettingsKit k;
    RepoRevertScreen(MainActivity activity, SettingsKit kit) { this.a = activity; this.k = kit; }

    void show(String full) {
        if (!k.open(full, "Откат (revert)", "Откат и отмена коммитов", () -> show(full))) return;
        k.note("Откат создаёт один новый коммит, история не переписывается и ничего не теряется: откат можно сам откатить. "
            + "«На N шагов» считает по основной линии ветки, «К тегу или коммиту» и «К дате» возвращают ветку к состоянию на тот момент, «Отменить один коммит» убирает только его изменения и сохраняет всё, что было после.");
        k.load("Contents", () -> Gh.object(a.api, Gh.repo(full)).optString("default_branch", "main"), def -> {
            final String[] branch = {def}, single = {""};
            LinearLayout form = k.form(); form.setPadding(0, 0, 0, 0);
            k.line(form, "Ветка", 13, true, a.TEXT);
            final Button branchButton = a.compactButton("Ветка: " + def);
            branchButton.setOnClickListener(v -> k.pickBranch(full, name -> { branch[0] = name; single[0] = ""; branchButton.setText("Ветка: " + name); }));
            form.addView(branchButton, new LinearLayout.LayoutParams(-1, a.dp(44)));
            k.line(form, "Способ", 13, true, a.TEXT);
            final RadioGroup mode = k.radios(form, MODES, 0);
            final EditText steps = k.field(form, "Число шагов (1–" + RepoRevert.MAX_STEPS + ")", "1", false);
            steps.setInputType(InputType.TYPE_CLASS_NUMBER);
            LinearLayout quick = k.buttons(form);
            for (final String n : new String[]{"1", "2", "3", "5", "10"}) k.small(quick, n, () -> steps.setText(n));
            final EditText revision = k.field(form, "Тег, ветка или sha коммита, к которому вернуться", "", false);
            final EditText date = k.field(form, "Дата ГГГГ-ММ-ДД: состояние на конец этого дня (UTC)", "", false);
            final Button pick = a.compactButton("Выбрать коммит для отмены");
            pick.setOnClickListener(v -> a.io.execute(() -> {
                try {
                    final List<RepoRevert.Commit> list = RepoRevert.recent(a.api, full, branch[0], 30);
                    final String[] labels = new String[list.size()];
                    for (int i = 0; i < labels.length; i++) labels[i] = list.get(i).shortSha() + "  " + (list.get(i).title.isEmpty() ? "(без сообщения)" : list.get(i).title);
                    a.runOnUiThread(() -> k.choose("Какой коммит отменить", labels, which -> { single[0] = list.get(which).sha; pick.setText("Коммит: " + labels[which]); }));
                } catch (Exception e) { final String m = Gh.explain(e, "Contents"); a.runOnUiThread(() -> a.errorDialog("Коммиты", m)); }
            }));
            form.addView(pick, new LinearLayout.LayoutParams(-1, a.dp(44)));
            final LinearLayout result = new LinearLayout(a); result.setOrientation(LinearLayout.VERTICAL);
            Button preview = a.button("Показать, что будет откачено");
            preview.setOnClickListener(v -> preview(full, branch[0], SettingsKit.selected(mode), steps.getText().toString(), revision.getText().toString(), date.getText().toString(), single[0], result));
            form.addView(preview, new LinearLayout.LayoutParams(-1, a.dp(50)));
            a.content.addView(form);
            a.content.addView(result);
        });
    }

    private void preview(String full, String branch, int mode, String rawSteps, String revision, String date, String single, LinearLayout result) {
        final int steps;
        try {
            if (mode == 0) steps = RepoRevert.checkSteps(rawSteps); else steps = 0;
            if (mode == 1 && revision.trim().isEmpty()) throw new IllegalArgumentException("Укажите тег или sha коммита");
            if (mode == 3 && single.isEmpty()) throw new IllegalArgumentException("Выберите коммит для отмены");
        } catch (IllegalArgumentException bad) { a.toast(bad.getMessage()); return; }
        result.removeAllViews();
        final TextView status = k.line(result, "Читаем историю…", 12, false, a.MUTED);
        final long generation = a.screenGeneration.get();
        final LinearLayout host = a.content;
        a.io.execute(() -> {
            try {
                final RepoRevert.Plan plan = mode == 0 ? RepoRevert.plan(a.api, full, branch, steps) : mode == 1 ? RepoRevert.planToRevision(a.api, full, branch, revision)
                    : mode == 2 ? RepoRevert.planToDate(a.api, full, branch, date) : RepoRevert.planSingle(a.api, full, branch, single);
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) draw(full, plan, result); });
            } catch (Exception error) {
                final String text = error instanceof IllegalArgumentException ? String.valueOf(error.getMessage()) : Gh.explain(error, "Contents");
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) { status.setText(text); status.setTextColor(0xFFC34F48); } });
            }
        });
    }

    private void draw(String full, RepoRevert.Plan plan, LinearLayout result) {
        result.removeAllViews();
        a.sectionTitle("Что изменится", null);
        LinearLayout summary = k.entry();
        if (plan.single) k.line(summary, "Будет отменён коммит " + plan.target.shortSha() + (plan.target.title.isEmpty() ? "" : " — " + plan.target.title) + ". Всё, что сделано после него, останется.", 14, true, a.TEXT);
        else k.line(summary, "Ветка «" + plan.branch + "» вернётся к состоянию коммита " + plan.target.shortSha() + (plan.target.title.isEmpty() ? "" : " — " + plan.target.title), 14, true, a.TEXT);
        k.line(summary, (plan.single ? "" : "Будет отменено коммитов: " + plan.count + " · ") + (plan.files >= 0 ? "файлов затронуто: " + plan.files + (plan.single ? "" : " (+" + plan.additions + " −" + plan.deletions + ")") : ""), 12, false, a.MUTED);
        result.addView(summary);
        if (!plan.conflicts.isEmpty()) {
            LinearLayout conflict = k.entry();
            k.line(conflict, "Файлы, изменённые после этого коммита (" + plan.conflicts.size() + "), не откатятся:", 13, true, 0xFFB07010);
            for (int i = 0; i < Math.min(plan.conflicts.size(), 15); i++) k.line(conflict, plan.conflicts.get(i), 12, false, a.MUTED);
            if (plan.conflicts.size() > 15) k.line(conflict, "… и ещё " + (plan.conflicts.size() - 15), 12, false, a.MUTED);
            result.addView(conflict);
        }
        if (!plan.single) for (int n = 0; n < Math.min(plan.undone.size(), 50); n++) {
            RepoRevert.Commit c = plan.undone.get(n);
            LinearLayout row = k.entry();
            k.line(row, c.shortSha() + "  " + (c.title.isEmpty() ? "(без сообщения)" : c.title), 13, true, a.TEXT);
            k.line(row, c.author + (c.date.isEmpty() ? "" : " · " + SettingsKit.date(c.date)), 12, false, a.MUTED);
            result.addView(row);
        }
        if (!plan.single && plan.undone.size() > 50) k.line(result, "… и ещё " + (plan.undone.size() - 50) + " коммит(ов)", 12, false, a.MUTED);
        a.sectionTitle("Как применить", null);
        LinearLayout form = k.form(); form.setPadding(0, 0, 0, 0);
        final EditText message = k.field(form, "Сообщение коммита", plan.single ? RepoRevert.defaultSingleMessage(plan.target) : RepoRevert.defaultMessage(plan.count, plan.target), true);
        final RadioGroup mode = k.radios(form, new String[]{"Прямо в ветку «" + plan.branch + "»", "В новую ветку"}, 0);
        final EditText name = k.field(form, "Имя новой ветки", RepoRevert.defaultBranchName(plan.branch, Math.max(1, plan.count)), false);
        final CheckBox pr = k.check(form, "Создать pull request в «" + plan.branch + "»", true);
        final CheckBox partial = plan.conflicts.isEmpty() ? null : k.check(form, "Применить без конфликтующих файлов (" + plan.conflicts.size() + ")", false);
        Button apply = a.button("Откатить");
        apply.setTextColor(0xFFC34F48);
        apply.setOnClickListener(v -> {
            final boolean toBranch = SettingsKit.selected(mode) == 1, allowPartial = partial != null && partial.isChecked();
            final String text = message.getText().toString(), branchName = name.getText().toString();
            final boolean openPr = pr.isChecked();
            k.confirm(plan.single ? "Отменить коммит " + plan.target.shortSha() + "?" : "Откатить на " + plan.count + " коммит(ов)?", toBranch ? "Будет создана ветка с новым коммитом." : "В ветку «" + plan.branch + "» будет добавлен новый коммит. История сохранится.", "Откатить", () -> {
                final RepoRevert.Result[] done = new RepoRevert.Result[1];
                k.run("Contents", null, () -> {
                    RepoRevert.Result r = done[0];
                    a.errorDialog("Откат выполнен", "Коммит " + r.commit.substring(0, 7) + (r.branch == null ? " добавлен в «" + plan.branch + "»." : " создан в ветке «" + r.branch + "».") + (r.pullRequestUrl.isEmpty() ? "" : "\nPull request: " + r.pullRequestUrl));
                    show(full);
                }, () -> done[0] = RepoRevert.apply(a.api, full, plan, text, toBranch ? branchName : null, openPr, allowPartial));
            });
        });
        form.addView(apply, new LinearLayout.LayoutParams(-1, a.dp(50)));
        result.addView(form);
    }
}
