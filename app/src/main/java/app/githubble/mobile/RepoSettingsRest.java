package app.githubble.mobile;

import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.repository.BranchRules;
import app.githubble.mobile.repository.CustomProperties;
import app.githubble.mobile.repository.Gh;
import app.githubble.mobile.repository.RepoGeneral;
import app.githubble.mobile.repository.RepoPages;
import app.githubble.mobile.repository.RepoSecurity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Settings → General, Pages, Security (toggles, alerts, advisories) and Custom properties. */
final class RepoSettingsRest {
    private final MainActivity a;
    private final SettingsKit k;
    RepoSettingsRest(MainActivity activity, SettingsKit kit) { this.a = activity; this.k = kit; }

    private interface Apply { void apply(boolean on) throws Exception; }

    private void pickBranch(String full, Consumer<String> picked) {
        a.io.execute(() -> {
            try {
                final List<BranchRules.Branch> list = BranchRules.branches(a.api, full);
                final String[] names = new String[list.size()];
                for (int i = 0; i < names.length; i++) names[i] = list.get(i).name;
                a.runOnUiThread(() -> { if (names.length == 0) a.toast("В репозитории нет веток"); else k.choose("Ветка", names, which -> picked.accept(names[which])); });
            } catch (Exception error) {
                final String text = Gh.explain(error, "Contents");
                a.runOnUiThread(() -> a.errorDialog("Ветки", text));
            }
        });
    }

    // ---- General ---------------------------------------------------------------------------------------------------

    void general(String full) {
        if (!k.open(full, "Общие настройки", "General", () -> general(full))) return;
        k.load("Administration", () -> RepoGeneral.load(a.api, full), s -> {
            LinearLayout f = k.form(); f.setPadding(0, 0, 0, 0);
            final EditText description = k.field(f, "Описание репозитория", s.description, true);
            final EditText homepage = k.field(f, "Сайт (https://…)", s.homepage, false);
            final EditText topics = k.field(f, "Темы через пробел или запятую", String.join(", ", s.topics), true);
            k.line(f, "Возможности", 13, true, a.TEXT);
            final CheckBox issues = k.check(f, "Issues", s.issues), wiki = k.check(f, "Wiki", s.wiki), projects = k.check(f, "Projects", s.projects), discussions = k.check(f, "Discussions", s.discussions);
            final CheckBox template = k.check(f, "Репозиторий-шаблон", s.template);
            k.line(f, "Слияние pull request", 13, true, a.TEXT);
            final CheckBox squash = k.check(f, "Разрешить squash-слияние", s.squash), merge = k.check(f, "Разрешить merge-коммит", s.merge), rebase = k.check(f, "Разрешить rebase-слияние", s.rebase);
            final CheckBox deleteBranch = k.check(f, "Удалять ветку после слияния", s.deleteBranch), update = k.check(f, "Кнопка «Обновить ветку»", s.updateBranch);
            final CheckBox auto = k.check(f, "Разрешить авто-слияние", s.autoMerge), signoff = k.check(f, "Требовать sign-off коммитов в вебе", s.webSignoff);
            Button save = a.button("Сохранить");
            save.setOnClickListener(v -> {
                final RepoGeneral.State next = new RepoGeneral.State();
                next.description = description.getText().toString(); next.homepage = homepage.getText().toString();
                next.issues = issues.isChecked(); next.wiki = wiki.isChecked(); next.projects = projects.isChecked(); next.discussions = discussions.isChecked(); next.template = template.isChecked();
                next.squash = squash.isChecked(); next.merge = merge.isChecked(); next.rebase = rebase.isChecked(); next.deleteBranch = deleteBranch.isChecked();
                next.updateBranch = update.isChecked(); next.autoMerge = auto.isChecked(); next.webSignoff = signoff.isChecked();
                final String rawTopics = topics.getText().toString();
                k.run("Administration", "Настройки сохранены", () -> general(full), () -> { next.topics.addAll(RepoGeneral.topics(rawTopics)); RepoGeneral.save(a.api, full, next); });
            });
            f.addView(save, new LinearLayout.LayoutParams(-1, a.dp(50)));
            a.content.addView(f);
            a.sectionTitle("Ветка по умолчанию", null);
            Button branch = a.button("Ветка по умолчанию: " + s.defaultBranch);
            branch.setOnClickListener(v -> pickBranch(full, name -> {
                if (name.equals(s.defaultBranch)) return;
                k.confirm("Сделать «" + name + "» веткой по умолчанию?", "Новые pull request и клоны будут использовать её.", "Сделать", () -> k.run("Administration", "Ветка по умолчанию изменена", () -> general(full), () -> RepoGeneral.setDefaultBranch(a.api, full, name)));
            }));
            a.content.addView(branch, new LinearLayout.LayoutParams(-1, a.dp(50)));
            a.sectionTitle("Опасная зона", null);
            Button archive = a.button(s.archived ? "Разархивировать репозиторий" : "Архивировать репозиторий");
            archive.setTextColor(0xFFC34F48);
            archive.setOnClickListener(v -> k.confirm(s.archived ? "Разархивировать?" : "Архивировать репозиторий?", s.archived ? "Репозиторий снова станет доступным для записи." : "Репозиторий станет доступным только для чтения. Это можно отменить.",
                s.archived ? "Разархивировать" : "Архивировать", () -> k.run("Administration", s.archived ? "Разархивирован" : "Архивирован", () -> general(full), () -> RepoGeneral.setArchived(a.api, full, !s.archived))));
            a.content.addView(archive, new LinearLayout.LayoutParams(-1, a.dp(50)));
        });
    }

    // ---- Pages -----------------------------------------------------------------------------------------------------

    void pages(String full) {
        if (!k.open(full, "Pages", "GitHub Pages", () -> pages(full))) return;
        k.load("Pages", () -> RepoPages.load(a.api, full), s -> {
            LinearLayout f = k.form(); f.setPadding(0, 0, 0, 0);
            if (s.exists) {
                k.line(f, "Сайт: " + s.url, 13, true, a.GREEN);
                k.line(f, "Статус: " + (s.status.isEmpty() ? "—" : s.status), 12, false, a.MUTED);
            } else k.line(f, "GitHub Pages для этого репозитория выключены.", 13, false, a.MUTED);
            k.line(f, "Источник публикации", 13, true, a.TEXT);
            final RadioGroup source = k.radios(f, new String[]{"Из ветки", "Через GitHub Actions"}, s.workflow ? 1 : 0);
            final String[] branch = {s.branch.isEmpty() ? "main" : s.branch};
            final Button branchButton = a.compactButton("Ветка: " + branch[0]);
            branchButton.setOnClickListener(v -> pickBranch(full, name -> { branch[0] = name; branchButton.setText("Ветка: " + name); }));
            f.addView(branchButton, new LinearLayout.LayoutParams(-1, a.dp(44)));
            final RadioGroup path = k.radios(f, new String[]{"/ (корень)", "/docs"}, "/docs".equals(s.path) ? 1 : 0);
            final EditText domain = k.field(f, "Свой домен (необязательно)", s.cname, false);
            final CheckBox https = k.check(f, "Принудительно HTTPS", s.httpsEnforced || !s.exists);
            Button save = a.button(s.exists ? "Сохранить" : "Включить Pages");
            save.setOnClickListener(v -> {
                final boolean workflow = SettingsKit.selected(source) == 1;
                final String chosen = branch[0], folder = SettingsKit.selected(path) == 1 ? "/docs" : "/", cname = domain.getText().toString();
                final boolean secure = https.isChecked();
                k.run("Pages", s.exists ? "Pages обновлены" : "Pages включены", () -> pages(full), () -> {
                    if (s.exists) RepoPages.update(a.api, full, workflow, chosen, folder, cname, secure); else RepoPages.enable(a.api, full, workflow, chosen, folder);
                });
            });
            f.addView(save, new LinearLayout.LayoutParams(-1, a.dp(50)));
            a.content.addView(f);
            if (!s.exists) return;
            Button rebuild = a.button("Пересобрать сайт");
            rebuild.setOnClickListener(v -> k.run("Pages", "Сборка запущена", null, () -> RepoPages.rebuild(a.api, full)));
            a.content.addView(rebuild, new LinearLayout.LayoutParams(-1, a.dp(50)));
            Button latest = a.button("Статус последней сборки");
            latest.setOnClickListener(v -> a.io.execute(() -> {
                try { final String text = RepoPages.latestBuild(a.api, full); a.runOnUiThread(() -> a.errorDialog("Последняя сборка Pages", text.isEmpty() ? "Нет данных" : text)); }
                catch (Exception e) { final String m = Gh.explain(e, "Pages"); a.runOnUiThread(() -> a.errorDialog("Сборка Pages", m)); }
            }));
            a.content.addView(latest, new LinearLayout.LayoutParams(-1, a.dp(50)));
            Button off = a.button("Выключить Pages"); off.setTextColor(0xFFC34F48);
            off.setOnClickListener(v -> k.confirm("Выключить Pages?", "Сайт перестанет быть доступен.", "Выключить", () -> k.run("Pages", "Pages выключены", () -> pages(full), () -> RepoPages.disable(a.api, full))));
            a.content.addView(off, new LinearLayout.LayoutParams(-1, a.dp(50)));
        });
    }

    // ---- Security --------------------------------------------------------------------------------------------------

    private void toggle(LinearLayout box, String label, boolean on, String permission, Runnable reload, Apply apply) {
        LinearLayout row = k.entry();
        k.line(row, label, 14, true, a.TEXT);
        k.line(row, on ? "Включено" : "Выключено", 12, false, on ? a.GREEN : a.MUTED);
        k.small(k.buttons(row), on ? "Выключить" : "Включить", () -> k.run(permission, on ? "Выключено" : "Включено", reload, () -> apply.apply(!on)));
        box.addView(row);
    }

    void security(String full) {
        if (!k.open(full, "Безопасность", "Code security", () -> security(full))) return;
        k.note("Оповещения о уязвимостях и защита от утечки секретов. Часть функций (code scanning, secret scanning в приватных репозиториях) требует GitHub Advanced Security на вашем тарифе.");
        final Runnable reload = () -> security(full);
        final LinearLayout toggles = new LinearLayout(a); toggles.setOrientation(LinearLayout.VERTICAL);
        a.sectionTitle("Функции", null);
        a.content.addView(toggles);
        k.load("Administration", () -> RepoSecurity.features(a.api, full), f -> {
            toggle(toggles, "Dependabot alerts", f.vulnerabilityAlerts, "Dependabot alerts", reload, on -> RepoSecurity.setVulnerabilityAlerts(a.api, full, on));
            toggle(toggles, "Dependabot security updates", f.securityFixes, "Administration", reload, on -> RepoSecurity.setSecurityFixes(a.api, full, on));
            if (f.availableSecrets) {
                toggle(toggles, "Secret scanning", f.secretScanning, "Administration", reload, on -> RepoSecurity.setAnalysis(a.api, full, "secret_scanning", on));
                toggle(toggles, "Push protection", f.pushProtection, "Administration", reload, on -> RepoSecurity.setAnalysis(a.api, full, "secret_scanning_push_protection", on));
            }
        });
        a.sectionTitle("Оповещения", null);
        k.action("Dependabot alerts", () -> a.navigate(() -> alerts(full, RepoSecurity.Kind.DEPENDABOT, true)));
        k.action("Code scanning alerts", () -> a.navigate(() -> alerts(full, RepoSecurity.Kind.CODE, true)));
        k.action("Secret scanning alerts", () -> a.navigate(() -> alerts(full, RepoSecurity.Kind.SECRET, true)));
        k.action("Security advisories", () -> a.navigate(() -> advisories(full)));
    }

    private static String[] reasonValues(RepoSecurity.Kind kind) { return kind == RepoSecurity.Kind.DEPENDABOT ? RepoSecurity.DEPENDABOT_REASONS : kind == RepoSecurity.Kind.CODE ? RepoSecurity.CODE_REASONS : RepoSecurity.SECRET_RESOLUTIONS; }

    private static String[] reasonLabels(RepoSecurity.Kind kind) {
        switch (kind) {
            case DEPENDABOT: return new String[]{"Исправление начато", "Неточное оповещение", "Нет времени", "Код не используется", "Допустимый риск"};
            case CODE: return new String[]{"Ложное срабатывание", "Не буду исправлять", "Используется в тестах"};
            default: return new String[]{"Ложное срабатывание", "Не буду исправлять", "Секрет отозван", "Используется в тестах"};
        }
    }

    void alerts(String full, RepoSecurity.Kind kind, boolean open) {
        if (!k.open(full, kind.permission, kind.permission, () -> alerts(full, kind, open))) return;
        k.action(open ? "Показать закрытые" : "Показать открытые", () -> alerts(full, kind, !open));
        k.load(kind.permission, () -> RepoSecurity.alerts(a.api, full, kind, open), list -> {
            LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); a.content.addView(box);
            if (list.isEmpty()) k.line(box, open ? "Открытых оповещений нет." : "Закрытых оповещений нет.", 13, false, a.MUTED);
            for (RepoSecurity.Alert alert : list) {
                LinearLayout row = k.entry();
                k.line(row, "#" + alert.number + " " + alert.title, 14, true, a.TEXT);
                k.line(row, (alert.severity.isEmpty() ? "" : alert.severity + " · ") + alert.state, 12, false, "critical".equals(alert.severity) || "high".equals(alert.severity) ? 0xFFC34F48 : a.MUTED);
                if (!alert.detail.isEmpty()) k.line(row, alert.detail, 12, false, a.MUTED);
                LinearLayout buttons = k.buttons(row);
                final int number = alert.number;
                if (open) k.small(buttons, "Закрыть", () -> dismiss(full, kind, number));
                else k.small(buttons, "Открыть снова", () -> k.run(kind.permission, "Оповещение открыто", () -> alerts(full, kind, false), () -> RepoSecurity.reopen(a.api, full, kind, number)));
                if (!alert.url.isEmpty()) { final String url = alert.url; k.small(buttons, "На сайте", () -> a.openWeb(url)); }
                box.addView(row);
            }
        });
    }

    private void dismiss(String full, RepoSecurity.Kind kind, int number) {
        LinearLayout form = k.form();
        final RadioGroup reason = k.radios(form, reasonLabels(kind), 0);
        final EditText comment = k.field(form, "Комментарий (необязательно)", "", true);
        k.form("Закрыть оповещение #" + number, "Закрыть", form, kind.permission, "Оповещение закрыто", () -> alerts(full, kind, true), () -> {
            final String value = reasonValues(kind)[SettingsKit.selected(reason)], note = comment.getText().toString();
            return () -> RepoSecurity.dismiss(a.api, full, kind, number, value, note);
        });
    }

    void advisories(String full) {
        if (!k.open(full, "Security advisories", "Security advisories", () -> advisories(full))) return;
        k.load("Repository security advisories", () -> RepoSecurity.advisories(a.api, full), list -> {
            LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); a.content.addView(box);
            if (list.isEmpty()) k.line(box, "Advisories нет.", 13, false, a.MUTED);
            for (RepoSecurity.Alert alert : list) {
                LinearLayout row = k.entry();
                k.line(row, alert.title, 14, true, a.TEXT);
                k.line(row, alert.severity + " · " + alert.state + (alert.detail.isEmpty() ? "" : " · " + alert.detail), 12, false, a.MUTED);
                if (!alert.url.isEmpty()) { final String url = alert.url; k.small(k.buttons(row), "На сайте", () -> a.openWeb(url)); }
                box.addView(row);
            }
        });
    }

    // ---- Custom properties -----------------------------------------------------------------------------------------

    void properties(String full) {
        if (!k.open(full, "Custom properties", "Custom properties", () -> properties(full))) return;
        k.note("Свойства из схемы организации (например, команда, уровень критичности). Доступны только репозиториям организации.");
        k.load("Custom properties", () -> {
            try { return CustomProperties.load(a.api, full); }
            catch (GitHubApiClient.ApiException e) { if (e.statusCode == 404) return null; throw e; }
        }, list -> {
            if (list == null) { k.note("Для этого репозитория нет схемы свойств: он принадлежит личному аккаунту или у организации нет Custom properties."); return; }
            if (list.isEmpty()) { k.note("В схеме организации пока нет свойств."); return; }
            LinearLayout f = k.form(); f.setPadding(0, 0, 0, 0);
            final List<Supplier<String>> readers = new ArrayList<>();
            for (CustomProperties.Property p : list) {
                k.line(f, p.name + (p.required ? " *" : ""), 13, true, a.TEXT);
                if (!p.description.isEmpty()) k.line(f, p.description, 12, false, a.MUTED);
                if ("single_select".equals(p.type) || "true_false".equals(p.type)) {
                    final List<String> values = new ArrayList<>();
                    values.add("");
                    if ("true_false".equals(p.type)) { values.add("true"); values.add("false"); } else values.addAll(p.options);
                    final String[] labels = new String[values.size()];
                    for (int i = 0; i < labels.length; i++) labels[i] = values.get(i).isEmpty() ? "— не задано —" : values.get(i);
                    final String[] holder = {p.value == null ? "" : p.value};
                    final Button b = a.compactButton(holder[0].isEmpty() ? labels[0] : holder[0]);
                    b.setOnClickListener(v -> k.choose(p.name, labels, which -> { holder[0] = values.get(which); b.setText(labels[which]); }));
                    f.addView(b, new LinearLayout.LayoutParams(-1, a.dp(44)));
                    readers.add(() -> holder[0]);
                } else {
                    final EditText e = k.field(f, "multi_select".equals(p.type) ? "Значения через запятую" : "Значение", p.value, false);
                    readers.add(() -> e.getText().toString());
                }
            }
            Button save = a.button("Сохранить");
            save.setOnClickListener(v -> {
                for (int i = 0; i < list.size(); i++) list.get(i).value = readers.get(i).get();
                k.run("Custom properties", "Свойства сохранены", () -> properties(full), () -> CustomProperties.save(a.api, full, list));
            });
            f.addView(save, new LinearLayout.LayoutParams(-1, a.dp(50)));
            a.content.addView(f);
        });
    }
}
