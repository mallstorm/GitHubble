package app.githubble.mobile;

import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.githubble.mobile.repository.BranchCleanup;
import app.githubble.mobile.repository.Gh;
import app.githubble.mobile.repository.PendingDeployments;
import app.githubble.mobile.repository.RepoReleases;
import java.util.ArrayList;
import java.util.List;

/** Releases and tags, deployments waiting for approval, branch cleanup. */
final class RepoSettingsOps {
    private final MainActivity a;
    private final SettingsKit k;
    private final List<BranchCleanup.Info> deleted = new ArrayList<>();
    RepoSettingsOps(MainActivity activity, SettingsKit kit) { this.a = activity; this.k = kit; }

    private LinearLayout section(String title) {
        a.sectionTitle(title, null);
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        a.content.addView(box);
        return box;
    }

    // ---- Releases and tags -----------------------------------------------------------------------------------------

    void releases(String full) {
        if (!k.open(full, "Релизы и теги", "Releases и Tags", () -> releases(full))) return;
        k.note("Релизы и теги репозитория, как на вкладке Releases. Загрузка файлов в релиз здесь пока недоступна, их можно удалять.");
        k.action("+  Новый релиз", () -> releaseForm(full, ""));
        k.action("+  Новый тег", () -> tagForm(full));
        final LinearLayout releaseBox = section("Релизы");
        k.load("Contents", () -> RepoReleases.list(a.api, full), list -> {
            if (list.isEmpty()) k.line(releaseBox, "Релизов пока нет.", 13, false, a.MUTED);
            for (RepoReleases.Release r : list) {
                LinearLayout row = k.entry();
                k.line(row, r.title(), 15, true, a.TEXT);
                k.line(row, (r.draft ? "Черновик · " : "") + (r.prerelease ? "Предрелиз · " : "") + r.tag + (r.date.isEmpty() ? "" : " · " + SettingsKit.date(r.date)), 12, false, r.draft ? 0xFFB07010 : a.MUTED);
                long total = 0; for (RepoReleases.Asset f : r.assets) total += f.size;
                if (!r.assets.isEmpty()) k.line(row, "Файлов: " + r.assets.size() + " · " + RepoReleases.size(total), 12, false, a.MUTED);
                LinearLayout top = k.buttons(row), bottom = k.buttons(row);
                k.small(top, "Править", () -> editRelease(full, r));
                k.small(top, "Файлы", () -> assets(full, r));
                k.small(top, "На сайте", () -> a.openWeb(r.url));
                final Button del = k.small(bottom, "Удалить", () -> deleteRelease(full, r));
                del.setTextColor(0xFFC34F48);
                releaseBox.addView(row);
            }
        });
        final LinearLayout tagBox = section("Теги");
        k.load("Contents", () -> RepoReleases.tags(a.api, full), list -> {
            if (list.isEmpty()) k.line(tagBox, "Тегов пока нет.", 13, false, a.MUTED);
            for (RepoReleases.Tag t : list) {
                LinearLayout row = k.entry();
                k.line(row, t.name, 15, true, a.TEXT);
                k.line(row, t.sha.length() > 7 ? t.sha.substring(0, 7) : t.sha, 12, false, a.MUTED);
                LinearLayout buttons = k.buttons(row);
                k.small(buttons, "Релиз из тега", () -> releaseForm(full, t.name));
                final Button del = k.small(buttons, "Удалить", () -> k.confirm("Удалить тег «" + t.name + "»?", "Релиз с этим тегом, если он есть, станет черновиком без тега.", "Удалить",
                    () -> k.run("Contents", "Тег удалён", () -> releases(full), () -> RepoReleases.deleteTag(a.api, full, t.name))));
                del.setTextColor(0xFFC34F48);
                tagBox.addView(row);
            }
        });
    }

    private void releaseForm(String full, String tag) {
        LinearLayout form = k.form();
        final EditText tagField = k.field(form, "Тег, например v1.2.0", tag, false);
        final String[] target = {""};
        final Button targetButton = a.compactButton("Ветка для нового тега: по умолчанию");
        targetButton.setOnClickListener(v -> k.pickBranch(full, name -> { target[0] = name; targetButton.setText("Ветка для нового тега: " + name); }));
        form.addView(targetButton, new LinearLayout.LayoutParams(-1, a.dp(44)));
        final EditText name = k.field(form, "Название релиза", "", false);
        final EditText body = k.field(form, "Описание (Markdown)", "", true);
        body.setMinLines(4);
        final CheckBox auto = k.check(form, "Сгенерировать заметки автоматически", true);
        final CheckBox draft = k.check(form, "Черновик", false), pre = k.check(form, "Предрелиз", false);
        k.form("Новый релиз", "Создать", form, "Contents", "Релиз создан", () -> releases(full), () -> {
            final String t = tagField.getText().toString(), branch = target[0], title = name.getText().toString(), text = body.getText().toString();
            final boolean notes = auto.isChecked(), d = draft.isChecked(), p = pre.isChecked();
            RepoReleases.checkTag(t);
            return () -> RepoReleases.create(a.api, full, t, branch, title, text, d, p, notes);
        });
    }

    private void editRelease(String full, RepoReleases.Release r) {
        LinearLayout form = k.form();
        final EditText name = k.field(form, "Название релиза", r.name, false);
        final EditText body = k.field(form, "Описание (Markdown)", r.body, true);
        body.setMinLines(4);
        final CheckBox draft = k.check(form, "Черновик", r.draft), pre = k.check(form, "Предрелиз", r.prerelease);
        k.form("Релиз " + r.tag, "Сохранить", form, "Contents", "Релиз обновлён", () -> releases(full), () -> {
            final String title = name.getText().toString(), text = body.getText().toString();
            final boolean d = draft.isChecked(), p = pre.isChecked();
            return () -> RepoReleases.update(a.api, full, r.id, title, text, d, p);
        });
    }

    private void deleteRelease(String full, RepoReleases.Release r) {
        LinearLayout form = k.form();
        k.line(form, "Релиз «" + r.title() + "» будет удалён вместе с файлами.", 13, false, a.TEXT);
        final CheckBox tag = k.check(form, "Удалить и тег " + r.tag, false);
        k.form("Удалить релиз?", "Удалить", form, "Contents", "Релиз удалён", () -> releases(full), () -> {
            final boolean alsoTag = tag.isChecked();
            return () -> RepoReleases.delete(a.api, full, r, alsoTag);
        });
    }

    private void assets(String full, RepoReleases.Release r) {
        if (r.assets.isEmpty()) { a.toast("В релизе нет файлов"); return; }
        final String[] labels = new String[r.assets.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = r.assets.get(i).name + " · " + RepoReleases.size(r.assets.get(i).size) + " · скачиваний " + r.assets.get(i).downloads;
        k.choose("Файл релиза — нажмите, чтобы удалить", labels, which -> {
            final RepoReleases.Asset f = r.assets.get(which);
            k.confirm("Удалить файл «" + f.name + "»?", "Он пропадёт из релиза.", "Удалить", () -> k.run("Contents", "Файл удалён", () -> releases(full), () -> RepoReleases.deleteAsset(a.api, full, f.id)));
        });
    }

    private void tagForm(String full) {
        LinearLayout form = k.form();
        final EditText tag = k.field(form, "Имя тега, например v1.2.0", "", false);
        final String[] branch = {""};
        final Button branchButton = a.compactButton("Ветка: выбрать");
        branchButton.setOnClickListener(v -> k.pickBranch(full, name -> { branch[0] = name; branchButton.setText("Ветка: " + name); }));
        form.addView(branchButton, new LinearLayout.LayoutParams(-1, a.dp(44)));
        final EditText sha = k.field(form, "или полный sha коммита (необязательно)", "", false);
        final EditText message = k.field(form, "Сообщение (если нужен аннотированный тег)", "", true);
        k.form("Новый тег", "Создать", form, "Contents", "Тег создан", () -> releases(full), () -> {
            final String t = tag.getText().toString(), s = sha.getText().toString().trim(), m = message.getText().toString(), ref = s.isEmpty() ? branch[0] : s;
            RepoReleases.checkTag(t);
            return () -> RepoReleases.createTag(a.api, full, t, ref, m);
        });
    }

    // ---- Deployments waiting for approval --------------------------------------------------------------------------

    void deployments(String full) {
        if (!k.open(full, "Деплои на одобрение", "Ожидают проверяющего", () -> deployments(full))) return;
        k.note("Запуски workflow, остановленные на окружении с обязательными проверяющими. Одобрить или отклонить может только назначенный проверяющий.");
        k.load("Actions", () -> PendingDeployments.load(a.api, full), list -> {
            LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); a.content.addView(box);
            if (list.isEmpty()) k.line(box, "Деплоев, ожидающих одобрения, нет.", 13, false, a.MUTED);
            for (PendingDeployments.Item item : list) {
                LinearLayout row = k.entry();
                k.line(row, "Окружение: " + item.env, 15, true, a.TEXT);
                k.line(row, item.run + (item.branch.isEmpty() ? "" : " · " + item.branch), 12, false, a.MUTED);
                if (!item.canApprove) k.line(row, "Вы не входите в список проверяющих этого окружения.", 12, false, 0xFFB07010);
                LinearLayout buttons = k.buttons(row);
                if (item.canApprove) {
                    k.small(buttons, "Одобрить", () -> review(full, item, true));
                    final Button no = k.small(buttons, "Отклонить", () -> review(full, item, false));
                    no.setTextColor(0xFFC34F48);
                }
                k.small(buttons, "На сайте", () -> a.openWeb(item.url));
                box.addView(row);
            }
        });
    }

    private void review(String full, PendingDeployments.Item item, boolean approve) {
        LinearLayout form = k.form();
        final EditText comment = k.field(form, "Комментарий (необязательно)", "", true);
        k.form((approve ? "Одобрить деплой в " : "Отклонить деплой в ") + item.env, approve ? "Одобрить" : "Отклонить", form, "Actions", approve ? "Деплой одобрен" : "Деплой отклонён", () -> deployments(full), () -> {
            final String text = comment.getText().toString();
            return () -> PendingDeployments.review(a.api, full, item.runId, item.envId, approve, text);
        });
    }

    // ---- Branch cleanup --------------------------------------------------------------------------------------------

    void cleanup(String full) {
        if (!k.open(full, "Очистка веток", "Влитые и устаревшие ветки", () -> cleanup(full))) return;
        k.note("Находит ветки, уже влитые в основную, и давно не менявшиеся. Удалённую ветку можно вернуть кнопкой «Восстановить» (коммиты остаются в GitHub). Ветка по умолчанию и защищённые не удаляются.");
        k.load("Contents", () -> new String[]{BranchCleanup.defaultBranch(a.api, full), BranchCleanup.isFork(a.api, full) ? "1" : ""}, info -> {
            final String base = info[0];
            LinearLayout form = k.form(); form.setPadding(0, 0, 0, 0);
            if (!info[1].isEmpty()) {
                Button sync = a.button("Синхронизировать форк с родителем («" + base + "»)");
                final String[] message = {""};
                sync.setOnClickListener(v -> k.run("Contents", null, () -> a.toast(message[0]), () -> message[0] = BranchCleanup.syncFork(a.api, full, base)));
                form.addView(sync, new LinearLayout.LayoutParams(-1, a.dp(50)));
            }
            k.line(form, "Устаревшей считать ветку без изменений дольше (дней)", 13, true, a.TEXT);
            final EditText days = k.field(form, "Дней", "90", false);
            days.setInputType(InputType.TYPE_CLASS_NUMBER);
            Button start = a.button("Проверить ветки");
            final LinearLayout results = new LinearLayout(a); results.setOrientation(LinearLayout.VERTICAL);
            start.setOnClickListener(v -> {
                int limit;
                try { limit = Integer.parseInt(days.getText().toString().trim()); if (limit < 1) throw new NumberFormatException(); } catch (NumberFormatException bad) { a.toast("Укажите число дней, например 90"); return; }
                analyze(full, base, limit, results);
            });
            form.addView(start, new LinearLayout.LayoutParams(-1, a.dp(50)));
            a.content.addView(form);
            a.content.addView(results);
            if (!deleted.isEmpty()) restoreButton(full, results);
        });
    }

    private void restoreButton(String full, LinearLayout results) {
        Button restore = a.button("Восстановить удалённые (" + deleted.size() + ")");
        restore.setOnClickListener(v -> {
            final List<BranchCleanup.Info> batch = new ArrayList<>(deleted);
            k.run("Contents", "Ветки восстановлены", () -> { deleted.clear(); cleanup(full); }, () -> { for (BranchCleanup.Info i : batch) BranchCleanup.restore(a.api, full, i); });
        });
        results.addView(restore, new LinearLayout.LayoutParams(-1, a.dp(50)));
    }

    private void analyze(String full, String base, int days, LinearLayout results) {
        results.removeAllViews();
        final TextView status = k.line(results, "Ищем ветки…", 12, false, a.MUTED);
        final long generation = a.screenGeneration.get();
        final LinearLayout host = a.content;
        a.io.execute(() -> {
            try {
                final List<BranchCleanup.Info> all = BranchCleanup.list(a.api, full, base);
                final List<BranchCleanup.Info> items = all.size() > 150 ? new ArrayList<>(all.subList(0, 150)) : all;
                for (int i = 0; i < items.size(); i++) {
                    final int done = i;
                    if (generation != a.screenGeneration.get()) return;
                    try { BranchCleanup.analyze(a.api, full, base, items.get(i)); } catch (Exception skip) { /* the branch stays unclassified */ }
                    if (i % 5 == 0) a.runOnUiThread(() -> status.setText("Проверено " + done + " из " + items.size()));
                }
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) drawList(full, base, items, days, results, all.size() - items.size()); });
            } catch (Exception error) {
                final String text = Gh.explain(error, "Contents");
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) { status.setText(text); status.setTextColor(0xFFC34F48); } });
            }
        });
    }

    private void drawList(String full, String base, List<BranchCleanup.Info> items, int days, LinearLayout results, int skipped) {
        results.removeAllViews();
        if (!deleted.isEmpty()) restoreButton(full, results);
        if (items.isEmpty()) { k.line(results, "Других веток нет: всё чисто.", 13, false, a.MUTED); return; }
        final long now = System.currentTimeMillis();
        final List<BranchCleanup.Info> shown = new ArrayList<>();
        final List<CheckBox> boxes = new ArrayList<>();
        int merged = 0, stale = 0;
        for (BranchCleanup.Info i : items) { if (i.merged()) merged++; if (BranchCleanup.stale(i, days, now)) stale++; }
        k.line(results, "Веток: " + items.size() + " · влито: " + merged + " · старше " + days + " дн.: " + stale + (skipped > 0 ? " · не проверено ещё " + skipped : ""), 13, true, a.TEXT);
        LinearLayout quick = k.buttons(results);
        k.small(quick, "Отметить влитые", () -> { for (int i = 0; i < shown.size(); i++) if (shown.get(i).merged() && boxes.get(i).isEnabled()) boxes.get(i).setChecked(true); });
        k.small(quick, "Старше " + days + " дн.", () -> { for (int i = 0; i < shown.size(); i++) if (BranchCleanup.stale(shown.get(i), days, now) && boxes.get(i).isEnabled()) boxes.get(i).setChecked(true); });
        k.small(quick, "Снять", () -> { for (CheckBox c : boxes) c.setChecked(false); });
        for (BranchCleanup.Info i : items) {
            LinearLayout row = k.entry();
            CheckBox c = new CheckBox(a);
            long age = BranchCleanup.ageDays(i.date, now);
            String state = i.ahead < 0 ? "не удалось проверить" : i.merged() ? "влита" : "впереди на " + i.ahead;
            c.setText(i.name); c.setTextColor(a.TEXT); c.setEnabled(!i.protectedBranch);
            row.addView(c);
            k.line(row, (i.protectedBranch ? "🛡 защищена · " : "") + state + (i.behind > 0 ? " · позади на " + i.behind : "") + (age >= 0 ? " · без изменений " + age + " дн." : ""), 12, false, i.merged() ? a.GREEN : a.MUTED);
            final String name = i.name;
            k.small(k.buttons(row), "Переименовать", () -> rename(full, name));
            results.addView(row);
            shown.add(i); boxes.add(c);
        }
        Button delete = a.button("Удалить отмеченные");
        delete.setTextColor(0xFFC34F48);
        delete.setOnClickListener(v -> {
            final List<BranchCleanup.Info> picked = new ArrayList<>();
            int unmerged = 0;
            for (int i = 0; i < shown.size(); i++) if (boxes.get(i).isChecked()) { picked.add(shown.get(i)); if (!shown.get(i).merged()) unmerged++; }
            if (picked.isEmpty()) { a.toast("Ничего не отмечено"); return; }
            final int notMerged = unmerged;
            k.confirm("Удалить веток: " + picked.size() + "?", (notMerged > 0 ? "Не влиты в «" + base + "»: " + notMerged + " (их коммиты останутся только в удалённой ветке). " : "") + "Сразу после удаления ветки можно восстановить.", "Удалить", () -> k.run("Contents", "Ветки удалены", () -> {
                items.removeAll(picked); drawList(full, base, items, days, results, skipped);
            }, () -> { for (BranchCleanup.Info i : picked) { BranchCleanup.delete(a.api, full, base, i); deleted.add(i); } }));
        });
        results.addView(delete, new LinearLayout.LayoutParams(-1, a.dp(50)));
    }

    private void rename(String full, String branch) {
        LinearLayout form = k.form();
        final EditText name = k.field(form, "Новое имя ветки", branch, false);
        k.form("Переименовать «" + branch + "»", "Переименовать", form, "Administration", "Ветка переименована", () -> cleanup(full), () -> {
            final String value = name.getText().toString();
            return () -> BranchCleanup.rename(a.api, full, branch, value);
        });
    }
}
