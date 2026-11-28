package app.githubble.mobile;

import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.githubble.mobile.repository.BulkSettings;
import app.githubble.mobile.repository.Gh;
import app.githubble.mobile.repository.RepoList;
import app.githubble.mobile.repository.SecuritySummary;
import app.githubble.mobile.repository.SettingsCopy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Screens that work on all repositories of the account: security summary and bulk settings. */
final class RepoAccountScreens {
    private static final int SCAN_LIMIT = 60, BULK_LIMIT = 100;
    private final MainActivity a;
    private final SettingsKit k;
    private final java.util.function.Consumer<String> openSecurity;
    RepoAccountScreens(MainActivity activity, SettingsKit kit, java.util.function.Consumer<String> openSecurity) { this.a = activity; this.k = kit; this.openSecurity = openSecurity; }

    private boolean open(String title, Runnable self) {
        a.setCurrentScreen(self);
        a.shell(title, false);
        if (!a.isSignedIn()) { a.accountRequiredCard("Подключите GitHub, чтобы работать с репозиториями."); return false; }
        return true;
    }

    // ---- Security summary ------------------------------------------------------------------------------------------

    void securitySummary() {
        if (!open("Сводка безопасности", this::securitySummary)) return;
        k.note("Открытые оповещения Dependabot, code scanning и secret scanning по " + SCAN_LIMIT + " последним репозиториям. Нажмите на репозиторий, чтобы открыть его раздел безопасности. «Нет доступа» значит, что у приложения или у вас нет нужного права.");
        final long generation = a.screenGeneration.get();
        final LinearLayout host = a.content;
        final TextView status = k.line(a.content, "Загружаем список репозиториев…", 12, false, a.MUTED);
        final LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        a.content.addView(box);
        a.io.execute(() -> {
            try {
                final List<RepoList.Item> repos = new ArrayList<>();
                for (RepoList.Item r : RepoList.load(a.api, SCAN_LIMIT + 40)) if (!r.archived && repos.size() < SCAN_LIMIT) repos.add(r);
                final List<SecuritySummary.Row> rows = new ArrayList<>();
                for (int i = 0; i < repos.size(); i++) {
                    if (generation != a.screenGeneration.get()) return;
                    final int done = i;
                    a.runOnUiThread(() -> status.setText("Проверено " + done + " из " + repos.size()));
                    try { rows.add(SecuritySummary.scan(a.api, repos.get(i).full)); } catch (Exception skip) { /* leave this repository out */ }
                }
                Collections.sort(rows, (x, y) -> Integer.compare(y.weight(), x.weight()));
                a.runOnUiThread(() -> {
                    if (generation != a.screenGeneration.get() || host != a.content) return;
                    int dirty = 0; for (SecuritySummary.Row r : rows) if (!r.clean()) dirty++;
                    status.setText("Репозиториев: " + rows.size() + " · с открытыми оповещениями: " + dirty);
                    for (SecuritySummary.Row r : rows) {
                        LinearLayout row = k.entry();
                        k.line(row, r.full, 14, true, a.TEXT);
                        k.line(row, r.line(), 12, false, r.critical > 0 || r.high > 0 || r.secret > 0 ? 0xFFC34F48 : r.clean() ? a.GREEN : a.MUTED);
                        final String full = r.full;
                        row.setOnClickListener(v -> a.navigate(() -> openSecurity.accept(full)));
                        box.addView(row);
                    }
                });
            } catch (Exception error) {
                final String text = Gh.explain(error, "Metadata");
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) { status.setText(text); status.setTextColor(0xFFC34F48); } });
            }
        });
    }

    // ---- Bulk settings ---------------------------------------------------------------------------------------------

    void bulk() {
        if (!open("Массовые настройки", this::bulk)) return;
        k.note("Одна и та же настройка сразу для выбранных репозиториев, при желании вместе с копированием настроек из репозитория-шаблона. Архивные репозитории и те, где у вас нет прав администратора, отмечены и по умолчанию не выбираются.");
        k.load("Metadata", () -> RepoList.load(a.api, BULK_LIMIT), repos -> {
            LinearLayout form = k.form(); form.setPadding(0, 0, 0, 0);
            k.line(form, "Что сделать", 13, true, a.TEXT);
            final BulkSettings.Action[] actions = BulkSettings.Action.values();
            final List<CheckBox> actionBoxes = new ArrayList<>();
            for (BulkSettings.Action action : actions) actionBoxes.add(k.check(form, action.label, false));
            k.line(form, "Скопировать из репозитория-шаблона (необязательно)", 13, true, a.TEXT);
            final EditText template = k.field(form, "владелец/репозиторий-шаблон", "", false);
            final SettingsCopy.Part[] parts = SettingsCopy.Part.values();
            final List<CheckBox> partBoxes = new ArrayList<>();
            for (SettingsCopy.Part part : parts) partBoxes.add(k.check(form, part.label, false));
            k.line(form, "Репозитории", 13, true, a.TEXT);
            final List<CheckBox> repoBoxes = new ArrayList<>();
            LinearLayout quick = k.buttons(form);
            k.small(quick, "Все доступные", () -> { for (int i = 0; i < repos.size(); i++) repoBoxes.get(i).setChecked(!repos.get(i).archived && repos.get(i).admin); });
            k.small(quick, "Без форков", () -> { for (int i = 0; i < repos.size(); i++) repoBoxes.get(i).setChecked(!repos.get(i).archived && repos.get(i).admin && !repos.get(i).fork); });
            k.small(quick, "Снять", () -> { for (CheckBox c : repoBoxes) c.setChecked(false); });
            for (RepoList.Item r : repos) {
                CheckBox c = k.check(form, r.full + "  ·  " + r.tags(), false);
                c.setEnabled(!r.archived);
                repoBoxes.add(c);
            }
            Button run = a.button("Применить");
            run.setTextColor(0xFFC34F48);
            run.setOnClickListener(v -> {
                final Set<BulkSettings.Action> chosen = EnumSet.noneOf(BulkSettings.Action.class);
                for (int i = 0; i < actions.length; i++) if (actionBoxes.get(i).isChecked()) chosen.add(actions[i]);
                final Set<SettingsCopy.Part> chosenParts = EnumSet.noneOf(SettingsCopy.Part.class);
                for (int i = 0; i < parts.length; i++) if (partBoxes.get(i).isChecked()) chosenParts.add(parts[i]);
                final List<String> targets = new ArrayList<>();
                for (int i = 0; i < repos.size(); i++) if (repoBoxes.get(i).isChecked()) targets.add(repos.get(i).full);
                String source = template.getText().toString().trim();
                if (!source.isEmpty()) {
                    try { RepoSettingsSource.check(source); } catch (IllegalArgumentException bad) { template.setError(bad.getMessage()); return; }
                } else chosenParts.clear();
                if (targets.isEmpty()) { a.toast("Не выбрано ни одного репозитория"); return; }
                if (chosen.isEmpty() && chosenParts.isEmpty()) { a.toast("Выберите, что сделать"); return; }
                final String templateName = source;
                k.confirm("Применить к " + targets.size() + " репозиториям?", "Настройки будут изменены у каждого выбранного репозитория. Это можно отменить только вручную.", "Применить", () -> a.io.execute(() -> {
                    final Map<String, List<String>> result = BulkSettings.run(a.api, targets, chosen, templateName, chosenParts);
                    final String text = BulkSettings.report(result);
                    a.runOnUiThread(() -> a.errorDialog("Результат", text));
                }));
            });
            form.addView(run, new LinearLayout.LayoutParams(-1, a.dp(50)));
            a.content.addView(form);
        });
    }
}
