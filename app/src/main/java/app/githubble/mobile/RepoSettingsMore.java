package app.githubble.mobile;

import android.graphics.Typeface;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import app.githubble.mobile.repository.ActionsOps;
import app.githubble.mobile.repository.BranchCleanup;
import app.githubble.mobile.repository.DependabotConfig;
import app.githubble.mobile.repository.Gh;
import app.githubble.mobile.repository.RepoFiles;
import app.githubble.mobile.repository.RepoIssuesMeta;
import app.githubble.mobile.repository.RepoReleases;
import app.githubble.mobile.repository.RepoTraffic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Actions runs and caches, labels, milestones, community files, dependabot wizard, traffic. */
final class RepoSettingsMore {
    private final MainActivity a;
    private final SettingsKit k;
    RepoSettingsMore(MainActivity activity, SettingsKit kit) { this.a = activity; this.k = kit; }

    private LinearLayout section(String title) {
        a.sectionTitle(title, null);
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        a.content.addView(box);
        return box;
    }

    private static String shortDate(String iso) { return SettingsKit.date(iso); }

    // ---- Actions: workflows, runs, caches --------------------------------------------------------------------------

    void actions(String full) {
        if (!k.open(full, "Actions: запуски", "Workflows, запуски и кэши", () -> actions(full))) return;
        k.note("Включение и ручной запуск workflow, повтор упавших jobs, отмена и удаление запусков, очистка кэшей этого репозитория.");
        final LinearLayout workflowBox = section("Workflows");
        k.load("Actions", () -> ActionsOps.workflows(a.api, full), list -> {
            if (list.isEmpty()) k.line(workflowBox, "Workflow-файлов нет.", 13, false, a.MUTED);
            for (ActionsOps.Workflow w : list) {
                LinearLayout row = k.entry();
                k.line(row, w.name, 15, true, a.TEXT);
                k.line(row, w.path + " · " + (w.active() ? "включён" : "выключен"), 12, false, w.active() ? a.GREEN : a.MUTED);
                LinearLayout buttons = k.buttons(row);
                if (w.active()) k.small(buttons, "Запустить", () -> dispatch(full, w));
                k.small(buttons, w.active() ? "Выключить" : "Включить", () -> k.run("Actions", w.active() ? "Workflow выключен" : "Workflow включён", () -> actions(full), () -> ActionsOps.setEnabled(a.api, full, w.id, !w.active())));
                workflowBox.addView(row);
            }
        });
        final LinearLayout runBox = section("Последние запуски");
        k.load("Actions", () -> ActionsOps.runs(a.api, full), list -> {
            if (list.isEmpty()) k.line(runBox, "Запусков пока нет.", 13, false, a.MUTED);
            for (ActionsOps.Run r : list) {
                LinearLayout row = k.entry();
                k.line(row, r.title, 14, true, a.TEXT);
                String state = r.running() ? r.status : r.conclusion.isEmpty() ? r.status : r.conclusion;
                k.line(row, r.workflow + " · " + r.branch + " · " + state + (r.attempt > 1 ? " · попытка " + r.attempt : "") + " · " + shortDate(r.date), 12, false, r.failed() ? 0xFFC34F48 : r.running() ? 0xFFB07010 : a.MUTED);
                LinearLayout top = k.buttons(row), bottom = k.buttons(row);
                if (r.failed()) k.small(top, "Повторить упавшие", () -> k.run("Actions", "Повтор запущен", () -> actions(full), () -> ActionsOps.rerun(a.api, full, r.id, true)));
                if (!r.running()) k.small(top, "Повторить всё", () -> k.run("Actions", "Повтор запущен", () -> actions(full), () -> ActionsOps.rerun(a.api, full, r.id, false)));
                if (r.running()) k.small(top, "Отменить", () -> k.run("Actions", "Запуск отменяется", () -> actions(full), () -> ActionsOps.cancel(a.api, full, r.id)));
                k.small(bottom, "На сайте", () -> a.openWeb(r.url));
                if (!r.running()) { final Button del = k.small(bottom, "Удалить", () -> k.confirm("Удалить запуск?", r.title, "Удалить", () -> k.run("Actions", "Запуск удалён", () -> actions(full), () -> ActionsOps.deleteRun(a.api, full, r.id)))); del.setTextColor(0xFFC34F48); }
                runBox.addView(row);
            }
        });
        final LinearLayout cacheBox = section("Кэши");
        k.load("Actions", () -> ActionsOps.caches(a.api, full), list -> {
            long total = 0; for (ActionsOps.Cache c : list) total += c.size;
            k.line(cacheBox, list.isEmpty() ? "Кэшей нет." : "Показаны самые большие: " + list.size() + " · " + RepoReleases.size(total), 13, true, a.TEXT);
            if (!list.isEmpty()) {
                Button all = a.button("Очистить показанные кэши");
                all.setTextColor(0xFFC34F48);
                all.setOnClickListener(v -> k.confirm("Удалить кэши (" + list.size() + ")?", "Следующие сборки заново скачают зависимости, это безопасно.", "Удалить", () -> k.run("Actions", "Кэши удалены", () -> actions(full), () -> { for (ActionsOps.Cache c : list) ActionsOps.deleteCache(a.api, full, c.id); })));
                cacheBox.addView(all, new LinearLayout.LayoutParams(-1, a.dp(50)));
            }
            for (ActionsOps.Cache c : list) {
                LinearLayout row = k.entry();
                k.line(row, c.key, 13, true, a.TEXT);
                k.line(row, RepoReleases.size(c.size) + " · " + c.ref + " · " + shortDate(c.created), 12, false, a.MUTED);
                final Button del = k.small(k.buttons(row), "Удалить", () -> k.run("Actions", "Кэш удалён", () -> actions(full), () -> ActionsOps.deleteCache(a.api, full, c.id)));
                del.setTextColor(0xFFC34F48);
                cacheBox.addView(row);
            }
        });
    }

    private void dispatch(String full, ActionsOps.Workflow w) {
        a.io.execute(() -> {
            try {
                final String def = BranchCleanup.defaultBranch(a.api, full);
                List<ActionsOps.Input> found;
                try { found = ActionsOps.parseInputs(ActionsOps.workflowText(a.api, full, w.path, def)); } catch (Exception ignored) { found = new ArrayList<>(); }
                final List<ActionsOps.Input> inputs = found;
                a.runOnUiThread(() -> {
                    LinearLayout form = k.form();
                    final String[] ref = {def};
                    final Button branch = a.compactButton("Ветка: " + def);
                    branch.setOnClickListener(v -> k.pickBranch(full, name -> { ref[0] = name; branch.setText("Ветка: " + name); }));
                    form.addView(branch, new LinearLayout.LayoutParams(-1, a.dp(44)));
                    StringBuilder prefill = new StringBuilder();
                    for (ActionsOps.Input in : inputs) {
                        prefill.append(in.name).append('=').append(in.value).append('\n');
                        if (!in.description.isEmpty()) k.line(form, in.name + ": " + in.description, 12, false, a.MUTED);
                    }
                    k.line(form, inputs.isEmpty() ? "Параметры (по одному на строку: имя=значение), если workflow их принимает" : "Параметры workflow (имя=значение)", 12, true, a.TEXT);
                    final EditText params = k.field(form, "имя=значение", prefill.toString().trim(), true);
                    k.form("Запустить «" + w.name + "»", "Запустить", form, "Actions", "Запуск поставлен в очередь", () -> actions(full), () -> {
                        final Map<String, String> values = ActionsOps.inputLines(params.getText().toString());
                        final String branchName = ref[0];
                        return () -> ActionsOps.dispatch(a.api, full, w.id, branchName, values);
                    });
                });
            } catch (Exception error) {
                final String text = Gh.explain(error, "Actions");
                a.runOnUiThread(() -> a.errorDialog("Запуск", text));
            }
        });
    }

    // ---- Labels ----------------------------------------------------------------------------------------------------

    void labels(String full) {
        if (!k.open(full, "Метки", "Labels", () -> labels(full))) return;
        k.note("Метки issue и pull request: название, цвет и описание.");
        k.action("+  Новая метка", () -> labelForm(full, null));
        k.load("Issues", () -> RepoIssuesMeta.labels(a.api, full), list -> {
            LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); a.content.addView(box);
            if (list.isEmpty()) k.line(box, "Меток нет.", 13, false, a.MUTED);
            for (RepoIssuesMeta.Label l : list) {
                LinearLayout row = k.entry();
                int tint;
                try { tint = 0xFF000000 | Integer.parseInt(l.color, 16); } catch (NumberFormatException bad) { tint = a.TEXT; }
                k.line(row, "●  " + l.name, 15, true, tint);
                k.line(row, "#" + l.color + (l.description.isEmpty() ? "" : " · " + l.description), 12, false, a.MUTED);
                LinearLayout buttons = k.buttons(row);
                k.small(buttons, "Изменить", () -> labelForm(full, l));
                final Button del = k.small(buttons, "Удалить", () -> k.confirm("Удалить метку «" + l.name + "»?", "Она будет снята со всех issue и pull request.", "Удалить", () -> k.run("Issues", "Метка удалена", () -> labels(full), () -> RepoIssuesMeta.deleteLabel(a.api, full, l.name))));
                del.setTextColor(0xFFC34F48);
                box.addView(row);
            }
        });
    }

    private void labelForm(String full, RepoIssuesMeta.Label l) {
        LinearLayout form = k.form();
        final EditText name = k.field(form, "Название", l == null ? "" : l.name, false);
        final EditText color = k.field(form, "Цвет, например d73a4a", l == null ? "ededed" : l.color, false);
        final EditText description = k.field(form, "Описание (необязательно)", l == null ? "" : l.description, false);
        k.form(l == null ? "Новая метка" : "Метка «" + l.name + "»", l == null ? "Создать" : "Сохранить", form, "Issues", l == null ? "Метка создана" : "Метка обновлена", () -> labels(full), () -> {
            final String n = name.getText().toString(), c = color.getText().toString(), d = description.getText().toString();
            RepoIssuesMeta.checkLabelName(n); RepoIssuesMeta.checkColor(c);
            if (l == null) return () -> RepoIssuesMeta.createLabel(a.api, full, n, c, d);
            return () -> RepoIssuesMeta.updateLabel(a.api, full, l.name, n, c, d);
        });
    }

    // ---- Milestones ------------------------------------------------------------------------------------------------

    void milestones(String full) {
        if (!k.open(full, "Этапы", "Milestones", () -> milestones(full))) return;
        k.note("Этапы (milestones) группируют issue и pull request к сроку.");
        k.action("+  Новый этап", () -> milestoneForm(full, null));
        k.load("Issues", () -> RepoIssuesMeta.milestones(a.api, full), list -> {
            LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); a.content.addView(box);
            if (list.isEmpty()) k.line(box, "Этапов нет.", 13, false, a.MUTED);
            for (RepoIssuesMeta.Milestone m : list) {
                LinearLayout row = k.entry();
                k.line(row, m.title, 15, true, a.TEXT);
                k.line(row, ("open".equals(m.state) ? "Открыт" : "Закрыт") + (m.due.isEmpty() ? "" : " · срок " + shortDate(m.due)) + " · открыто " + m.open + ", закрыто " + m.closed, 12, false, "open".equals(m.state) ? a.GREEN : a.MUTED);
                LinearLayout buttons = k.buttons(row);
                k.small(buttons, "Изменить", () -> milestoneForm(full, m));
                final boolean open = "open".equals(m.state);
                k.small(buttons, open ? "Закрыть" : "Открыть", () -> k.run("Issues", open ? "Этап закрыт" : "Этап открыт", () -> milestones(full), () -> RepoIssuesMeta.updateMilestone(a.api, full, m.number, m.title, m.description, shortDate(m.due), !open)));
                final Button del = k.small(buttons, "Удалить", () -> k.confirm("Удалить этап «" + m.title + "»?", "Issue останутся, но потеряют привязку к этапу.", "Удалить", () -> k.run("Issues", "Этап удалён", () -> milestones(full), () -> RepoIssuesMeta.deleteMilestone(a.api, full, m.number))));
                del.setTextColor(0xFFC34F48);
                box.addView(row);
            }
        });
    }

    private void milestoneForm(String full, RepoIssuesMeta.Milestone m) {
        LinearLayout form = k.form();
        final EditText title = k.field(form, "Название", m == null ? "" : m.title, false);
        final EditText description = k.field(form, "Описание (необязательно)", m == null ? "" : m.description, true);
        final EditText due = k.field(form, "Срок ГГГГ-ММ-ДД (необязательно)", m == null ? "" : shortDate(m.due), false);
        k.form(m == null ? "Новый этап" : "Этап «" + m.title + "»", m == null ? "Создать" : "Сохранить", form, "Issues", m == null ? "Этап создан" : "Этап обновлён", () -> milestones(full), () -> {
            final String t = title.getText().toString(), d = description.getText().toString(), by = due.getText().toString();
            if (t.trim().isEmpty()) throw new IllegalArgumentException("Введите название этапа");
            if (m == null) return () -> RepoIssuesMeta.createMilestone(a.api, full, t, d, by);
            return () -> RepoIssuesMeta.updateMilestone(a.api, full, m.number, t, d, by, "open".equals(m.state));
        });
    }

    // ---- Community files and CODEOWNERS ----------------------------------------------------------------------------

    void files(String full) {
        if (!k.open(full, "Файлы сообщества", "CODEOWNERS, шаблоны, политики", () -> files(full))) return;
        k.note("Служебные файлы репозитория редактируются и коммитятся прямо в ветку по умолчанию. Если файла нет, откроется заготовка.");
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); a.content.addView(box);
        for (final String[] known : RepoFiles.KNOWN) {
            LinearLayout row = k.entry();
            k.line(row, known[1], 14, true, a.TEXT);
            k.line(row, known[0], 12, false, a.MUTED);
            row.setOnClickListener(v -> a.navigate(() -> editFile(full, known[0])));
            box.addView(row);
        }
    }

    void editFile(String full, String path) {
        if (!k.open(full, "Файл", path, () -> editFile(full, path))) return;
        k.load("Contents", () -> {
            RepoFiles.checkPath(path);
            String branch = BranchCleanup.defaultBranch(a.api, full);
            return new Object[]{branch, RepoFiles.get(a.api, full, path, branch)};
        }, data -> {
            final String branch = (String) data[0];
            final RepoFiles.File file = (RepoFiles.File) data[1];
            String starter = "";
            if (!file.exists) for (String[] known : RepoFiles.KNOWN) if (known[0].equals(path)) starter = known[2];
            k.note(file.exists ? "Файл есть в ветке «" + branch + "». Правка будет закоммичена туда." : "Файла в ветке «" + branch + "» нет: он будет создан.");
            LinearLayout form = k.form(); form.setPadding(0, 0, 0, 0);
            final EditText text = k.field(form, "Содержимое", file.exists ? file.text : starter, true);
            text.setMinLines(14); text.setTypeface(Typeface.MONOSPACE); text.setTextSize(13);
            final EditText message = k.field(form, "Сообщение коммита", (file.exists ? "Update " : "Add ") + path, false);
            Button save = a.button(file.exists ? "Сохранить в репозиторий" : "Создать файл");
            save.setOnClickListener(v -> {
                final String body = text.getText().toString(), note = message.getText().toString();
                k.run("Contents", "Файл сохранён", () -> editFile(full, path), () -> RepoFiles.put(a.api, full, path, branch, body, note, file.sha));
            });
            form.addView(save, new LinearLayout.LayoutParams(-1, a.dp(50)));
            if (path.endsWith("CODEOWNERS")) {
                Button check = a.button("Проверить CODEOWNERS");
                check.setOnClickListener(v -> a.io.execute(() -> {
                    try {
                        final List<String> errors = RepoFiles.codeownersErrors(a.api, full);
                        a.runOnUiThread(() -> a.errorDialog("CODEOWNERS", errors.isEmpty() ? "Ошибок нет." : String.join("\n", errors)));
                    } catch (Exception e) { final String m = Gh.explain(e, "Contents"); a.runOnUiThread(() -> a.errorDialog("CODEOWNERS", m)); }
                }));
                form.addView(check, new LinearLayout.LayoutParams(-1, a.dp(50)));
            }
            a.content.addView(form);
        });
    }

    // ---- Dependabot wizard -----------------------------------------------------------------------------------------

    void dependabot(String full) {
        if (!k.open(full, "Мастер Dependabot", "dependabot.yml", () -> dependabot(full))) return;
        k.note("Отметьте экосистемы, и приложение создаст .github/dependabot.yml в ветке по умолчанию. Найденные в корне репозитория отмечены сразу. Существующий файл будет заменён.");
        k.load("Contents", () -> new Object[]{RepoFiles.detectEcosystems(a.api, full), BranchCleanup.defaultBranch(a.api, full)}, data -> {
            @SuppressWarnings("unchecked") final java.util.Set<String> detected = (java.util.Set<String>) data[0];
            final String branch = (String) data[1];
            LinearLayout form = k.form(); form.setPadding(0, 0, 0, 0);
            final List<CheckBox> boxes = new ArrayList<>();
            for (String[] eco : DependabotConfig.ECOSYSTEMS) boxes.add(k.check(form, eco[1] + (detected.contains(eco[0]) ? "  ✓ найдено" : ""), detected.contains(eco[0])));
            k.line(form, "Как часто проверять", 13, true, a.TEXT);
            final RadioGroup interval = k.radios(form, new String[]{"Каждый день", "Раз в неделю", "Раз в месяц"}, 1);
            final EditText directory = k.field(form, "Папка с манифестами (от корня)", "/", false);
            final EditText limit = k.field(form, "Лимит открытых pull request (0–50)", "5", false);
            limit.setInputType(InputType.TYPE_CLASS_NUMBER);
            Button save = a.button("Создать dependabot.yml");
            save.setOnClickListener(v -> {
                final List<DependabotConfig.Entry> entries = new ArrayList<>();
                final String dir = directory.getText().toString(), rawLimit = limit.getText().toString().trim();
                final String every = DependabotConfig.INTERVALS[SettingsKit.selected(interval)];
                for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) entries.add(new DependabotConfig.Entry(DependabotConfig.ECOSYSTEMS[i][0], "github-actions".equals(DependabotConfig.ECOSYSTEMS[i][0]) ? "/" : dir, -1));
                k.run("Contents", "dependabot.yml записан", null, () -> {
                    int n;
                    try { n = Integer.parseInt(rawLimit); } catch (NumberFormatException bad) { throw new IllegalArgumentException("Лимит — целое число"); }
                    List<DependabotConfig.Entry> sized = new ArrayList<>();
                    for (DependabotConfig.Entry e : entries) sized.add(new DependabotConfig.Entry(e.ecosystem, e.directory, n));
                    String yaml = DependabotConfig.generate(sized, every);
                    RepoFiles.File current = RepoFiles.get(a.api, full, ".github/dependabot.yml", branch);
                    RepoFiles.put(a.api, full, ".github/dependabot.yml", branch, yaml, current.exists ? "Update dependabot.yml" : "Add dependabot.yml", current.sha);
                });
            });
            form.addView(save, new LinearLayout.LayoutParams(-1, a.dp(50)));
            a.content.addView(form);
        });
    }

    // ---- Traffic ---------------------------------------------------------------------------------------------------

    void traffic(String full) {
        if (!k.open(full, "Трафик", "Просмотры и клоны за 14 дней", () -> traffic(full))) return;
        k.note("GitHub показывает трафик только за последние 14 дней и только тем, у кого есть запись в репозиторий.");
        final LinearLayout views = section("Просмотры");
        k.load("Administration", () -> RepoTraffic.views(a.api, full), s -> series(views, s));
        final LinearLayout clones = section("Клоны");
        k.load("Administration", () -> RepoTraffic.clones(a.api, full), s -> series(clones, s));
        final LinearLayout paths = section("Популярные страницы");
        k.load("Administration", () -> RepoTraffic.paths(a.api, full), list -> items(paths, list));
        final LinearLayout referrers = section("Откуда приходят");
        k.load("Administration", () -> RepoTraffic.referrers(a.api, full), list -> items(referrers, list));
    }

    private void series(LinearLayout box, RepoTraffic.Series s) {
        LinearLayout row = k.entry();
        k.line(row, s.count + " всего · " + s.uniques + " уникальных", 15, true, a.TEXT);
        TextView spark = k.line(row, RepoTraffic.spark(s.perDay), 22, false, a.GREEN);
        spark.setTypeface(Typeface.MONOSPACE);
        box.addView(row);
    }

    private void items(LinearLayout box, List<RepoTraffic.Item> list) {
        if (list.isEmpty()) k.line(box, "Нет данных.", 13, false, a.MUTED);
        for (RepoTraffic.Item i : list) {
            LinearLayout row = k.entry();
            k.line(row, i.label, 13, true, a.TEXT);
            k.line(row, i.count + " · уникальных " + i.uniques, 12, false, a.MUTED);
            box.addView(row);
        }
    }
}
