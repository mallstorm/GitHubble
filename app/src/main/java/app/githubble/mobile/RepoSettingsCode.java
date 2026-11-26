package app.githubble.mobile;

import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import app.githubble.mobile.repository.ActionsPermissions;
import app.githubble.mobile.repository.BranchRules;
import app.githubble.mobile.repository.Collaborators;
import app.githubble.mobile.repository.Gh;
import app.githubble.mobile.repository.RepoWebhooks;
import app.githubble.mobile.ui.SheetDialog;
import java.util.List;

/** Settings → Branches and rules, Actions → General, Webhooks and Collaborators. */
final class RepoSettingsCode {
    private final MainActivity a;
    private final SettingsKit k;
    RepoSettingsCode(MainActivity activity, SettingsKit kit) { this.a = activity; this.k = kit; }

    private LinearLayout section(String title) {
        a.sectionTitle(title, null);
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        a.content.addView(box);
        return box;
    }

    // ---- Branches and rules ----------------------------------------------------------------------------------------

    void branches(String full) {
        if (!k.open(full, "Ветки и правила", "Branches и Rulesets", () -> branches(full))) return;
        k.note("Защита веток и правила репозитория (rulesets), как в Settings → Branches / Rules. Нажмите на ветку, чтобы изменить её защиту. Для приватных репозиториев на бесплатном тарифе GitHub защита недоступна.");
        k.action("+  Новое правило (ruleset)", () -> newRuleset(full));
        final LinearLayout branchBox = section("Ветки");
        k.load("Administration", () -> BranchRules.branches(a.api, full), list -> {
            if (list.isEmpty()) k.line(branchBox, "Веток нет.", 13, false, a.MUTED);
            for (BranchRules.Branch b : list) {
                LinearLayout row = k.entry();
                k.line(row, b.name, 15, true, a.TEXT);
                k.line(row, b.protectedBranch ? "🛡 Защищена" : "Без защиты", 12, false, b.protectedBranch ? a.GREEN : a.MUTED);
                final String name = b.name;
                row.setOnClickListener(v -> protection(full, name));
                branchBox.addView(row);
            }
        });
        final LinearLayout ruleBox = section("Правила (rulesets)");
        k.load("Administration", () -> BranchRules.rulesets(a.api, full), list -> {
            if (list.isEmpty()) k.line(ruleBox, "Правил нет.", 13, false, a.MUTED);
            for (BranchRules.Ruleset r : list) {
                LinearLayout row = k.entry();
                k.line(row, r.name, 15, true, a.TEXT);
                k.line(row, ("tag".equals(r.target) ? "Теги" : "Ветки") + " · " + enforcementLabel(r.enforcement), 12, false, a.MUTED);
                LinearLayout buttons = k.buttons(row);
                final long id = r.id;
                k.small(buttons, "Режим", () -> k.choose("Режим правила", new String[]{"Активно", "Только проверка", "Выключено"}, which -> {
                    final String value = new String[]{"active", "evaluate", "disabled"}[which];
                    k.run("Administration", "Режим изменён", () -> branches(full), () -> BranchRules.setEnforcement(a.api, full, id, value));
                }));
                final Button del = k.small(buttons, "Удалить", () -> k.confirm("Удалить правило «" + r.name + "»?", "Ограничения перестанут действовать.", "Удалить",
                    () -> k.run("Administration", "Правило удалено", () -> branches(full), () -> BranchRules.deleteRuleset(a.api, full, id))));
                del.setTextColor(0xFFC34F48);
                ruleBox.addView(row);
            }
        });
    }

    private static String enforcementLabel(String e) { return "active".equals(e) ? "активно" : "evaluate".equals(e) ? "только проверка" : "выключено"; }

    private void protection(String full, String branch) {
        a.io.execute(() -> {
            try {
                final BranchRules.Protection p = BranchRules.load(a.api, full, branch);
                a.runOnUiThread(() -> protectionDialog(full, branch, p));
            } catch (Exception error) {
                final String text = Gh.explain(error, "Administration");
                a.runOnUiThread(() -> a.errorDialog("Защита ветки", text));
            }
        });
    }

    private void protectionDialog(String full, String branch, BranchRules.Protection p) {
        LinearLayout form = k.form();
        final CheckBox pr = k.check(form, "Требовать pull request перед слиянием", p.requirePr);
        final EditText approvals = k.field(form, "Число одобрений (0–6)", String.valueOf(p.approvals), false);
        approvals.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final CheckBox stale = k.check(form, "Сбрасывать одобрения при новых коммитах", p.dismissStale);
        final CheckBox owners = k.check(form, "Требовать одобрение владельцев кода (CODEOWNERS)", p.codeOwners);
        final CheckBox checks = k.check(form, "Требовать успешные проверки (status checks)", p.requireChecks);
        final CheckBox strict = k.check(form, "Ветка должна быть актуальной перед слиянием", p.strict);
        final EditText contexts = k.field(form, "Названия проверок через запятую", String.join(", ", p.contexts), true);
        final CheckBox conv = k.check(form, "Требовать решения всех обсуждений", p.conversations);
        final CheckBox linear = k.check(form, "Линейная история (без merge-коммитов)", p.linearHistory);
        final CheckBox admins = k.check(form, "Действует и на администраторов", p.enforceAdmins);
        final CheckBox force = k.check(form, "Разрешить force push", p.allowForce);
        final CheckBox deletions = k.check(form, "Разрешить удаление ветки", p.allowDeletions);
        SheetDialog.Builder builder = new SheetDialog.Builder(a).setTitle("Защита «" + branch + "»").setView(k.scroll(form)).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null);
        if (p.exists) builder.setNeutralButton("Снять защиту", null);
        final SheetDialog dialog = builder.show();
        dialog.setOnShowListener(x -> {
            dialog.getButton(-1).setOnClickListener(w -> {
                final BranchRules.Protection next = new BranchRules.Protection();
                try { next.approvals = Integer.parseInt(approvals.getText().toString().trim()); }
                catch (NumberFormatException bad) { approvals.setError("Введите число"); return; }
                next.requirePr = pr.isChecked(); next.dismissStale = stale.isChecked(); next.codeOwners = owners.isChecked();
                next.requireChecks = checks.isChecked(); next.strict = strict.isChecked(); next.contexts.addAll(BranchRules.splitContexts(contexts.getText().toString()));
                next.conversations = conv.isChecked(); next.linearHistory = linear.isChecked(); next.enforceAdmins = admins.isChecked();
                next.allowForce = force.isChecked(); next.allowDeletions = deletions.isChecked();
                dialog.dismiss();
                k.run("Administration", "Защита сохранена", () -> branches(full), () -> BranchRules.save(a.api, full, branch, next));
            });
            if (p.exists && dialog.getButton(-3) != null) dialog.getButton(-3).setOnClickListener(w -> {
                dialog.dismiss();
                k.confirm("Снять защиту с «" + branch + "»?", "Ветку снова можно будет менять без ограничений.", "Снять",
                    () -> k.run("Administration", "Защита снята", () -> branches(full), () -> BranchRules.remove(a.api, full, branch)));
            });
        });
    }

    private void newRuleset(String full) {
        LinearLayout form = k.form();
        final EditText name = k.field(form, "Название, например protect-main", "", false);
        final RadioGroup target = k.radios(form, new String[]{"Ветки", "Теги"}, 0);
        final EditText patterns = k.field(form, "Шаблоны через запятую (пусто — ветка по умолчанию)", "", true);
        final CheckBox deletion = k.check(form, "Запретить удаление", true);
        final CheckBox force = k.check(form, "Запретить force push", true);
        final CheckBox linear = k.check(form, "Линейная история (только ветки)", false);
        final EditText approvals = k.field(form, "Одобрений в pull request (0 — не требовать)", "0", false);
        approvals.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        k.form("Новое правило", "Создать", k.scroll(form), "Administration", "Правило создано", () -> branches(full), () -> {
            final String title = name.getText().toString(), refs = patterns.getText().toString();
            final boolean tags = SettingsKit.selected(target) == 1;
            final int count;
            try { count = Integer.parseInt(approvals.getText().toString().trim().isEmpty() ? "0" : approvals.getText().toString().trim()); }
            catch (NumberFormatException bad) { throw new IllegalArgumentException("Число одобрений — целое"); }
            final boolean d = deletion.isChecked(), f = force.isChecked(), l = linear.isChecked();
            return () -> BranchRules.createRuleset(a.api, full, title, tags, refs, d, f, l, count);
        });
    }

    // ---- Actions → General -----------------------------------------------------------------------------------------

    private static final String[] ALLOWED = {"all", "local_only", "selected"};
    private static final String[] ALLOWED_LABELS = {"Все actions и reusable workflows", "Только из этого репозитория", "Только выбранные"};
    private static final String[] TOKEN = {"read", "write"};
    private static final String[] TOKEN_LABELS = {"Только чтение (рекомендуется)", "Чтение и запись"};
    private static final String[] FORK = {"first_time_contributors_new_to_github", "first_time_contributors", "all_external_contributors"};
    private static final String[] FORK_LABELS = {"Новые на GitHub контрибьюторы", "Все новые контрибьюторы", "Все внешние контрибьюторы"};

    private static int indexOf(String[] values, String v) { for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i; return 0; }

    private Button picker(LinearLayout form, String title, String[] labels, String[] values, String[] holder) {
        final Button b = a.compactButton(labels[indexOf(values, holder[0])]);
        b.setOnClickListener(v -> k.choose(title, labels, which -> { holder[0] = values[which]; b.setText(labels[which]); }));
        form.addView(b, new LinearLayout.LayoutParams(-1, a.dp(44)));
        return b;
    }

    void actionsGeneral(String full) {
        if (!k.open(full, "Actions: общие", "Actions → General", () -> actionsGeneral(full))) return;
        k.load("Actions", () -> ActionsPermissions.load(a.api, full), s -> {
            LinearLayout form = k.form(); form.setPadding(0, 0, 0, 0);
            final CheckBox enabled = k.check(form, "Разрешить GitHub Actions в этом репозитории", s.enabled);
            k.line(form, "Какие actions разрешены", 13, true, a.TEXT);
            final String[] allowed = {s.allowed}, token = {s.token}, fork = {s.fork.isEmpty() ? FORK[0] : s.fork};
            picker(form, "Разрешённые actions", ALLOWED_LABELS, ALLOWED, allowed);
            final CheckBox githubOwned = k.check(form, "Разрешить actions от GitHub", s.githubOwned);
            final CheckBox verified = k.check(form, "Разрешить actions от проверенных авторов", s.verified);
            final EditText patterns = k.field(form, "Шаблоны через запятую, например owner/*, actions/checkout@*", String.join(", ", s.patterns), true);
            k.line(form, "Права токена GITHUB_TOKEN по умолчанию", 13, true, a.TEXT);
            picker(form, "Права токена", TOKEN_LABELS, TOKEN, token);
            final CheckBox approve = k.check(form, "Разрешить Actions создавать и одобрять pull request", s.canApprove);
            if (!s.fork.isEmpty()) { k.line(form, "Запуск workflow из fork pull request", 13, true, a.TEXT); picker(form, "Кому нужно одобрение", FORK_LABELS, FORK, fork); }
            final boolean hasFork = !s.fork.isEmpty();
            Button save = a.button("Сохранить");
            save.setOnClickListener(v -> {
                final boolean on = enabled.isChecked(), owned = githubOwned.isChecked(), ver = verified.isChecked(), canApprove = approve.isChecked();
                final String mode = allowed[0], tokenValue = token[0], forkValue = fork[0];
                final List<String> list = ActionsPermissions.splitPatterns(patterns.getText().toString());
                k.run("Actions", "Настройки Actions сохранены", () -> actionsGeneral(full), () -> {
                    ActionsPermissions.saveGeneral(a.api, full, on, mode);
                    if (on && "selected".equals(mode)) ActionsPermissions.saveSelected(a.api, full, owned, ver, list);
                    ActionsPermissions.saveToken(a.api, full, tokenValue, canApprove);
                    if (hasFork) ActionsPermissions.saveFork(a.api, full, forkValue);
                });
            });
            form.addView(save, new LinearLayout.LayoutParams(-1, a.dp(50)));
            a.content.addView(form);
        });
    }

    // ---- Webhooks --------------------------------------------------------------------------------------------------

    void webhooks(String full) {
        if (!k.open(full, "Webhooks", "Webhooks", () -> webhooks(full))) return;
        k.note("GitHub будет отправлять события репозитория (push, pull_request, issues…) на ваш адрес. Секрет подписи GitHub не показывает: при изменении введите его заново, если он нужен.");
        k.action("+  Новый webhook", () -> hookForm(full, null));
        k.load("Webhooks", () -> RepoWebhooks.list(a.api, full), list -> {
            LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); a.content.addView(box);
            if (list.isEmpty()) k.line(box, "Webhooks пока нет.", 13, false, a.MUTED);
            for (RepoWebhooks.Hook h : list) {
                LinearLayout row = k.entry();
                k.line(row, h.url, 14, true, a.TEXT);
                k.line(row, (h.active ? "Включён" : "Выключен") + " · " + String.join(", ", h.events) + " · " + h.contentType, 12, false, h.active ? a.GREEN : a.MUTED);
                k.line(row, "Последний ответ: " + h.lastStatus, 12, false, a.MUTED);
                LinearLayout top = k.buttons(row), bottom = k.buttons(row);
                final long id = h.id;
                k.small(top, h.active ? "Выключить" : "Включить", () -> k.run("Webhooks", h.active ? "Выключен" : "Включён", () -> webhooks(full), () -> RepoWebhooks.setActive(a.api, full, id, !h.active)));
                k.small(top, "Тест", () -> k.run("Webhooks", "Тестовое событие отправлено", null, () -> {
                    if (h.events.contains("push") || h.events.contains("*")) RepoWebhooks.test(a.api, full, id); else RepoWebhooks.ping(a.api, full, id);
                }));
                k.small(top, "Доставки", () -> deliveries(full, id));
                k.small(bottom, "Изменить", () -> hookForm(full, h));
                final Button del = k.small(bottom, "Удалить", () -> k.confirm("Удалить webhook?", h.url, "Удалить", () -> k.run("Webhooks", "Webhook удалён", () -> webhooks(full), () -> RepoWebhooks.delete(a.api, full, id))));
                del.setTextColor(0xFFC34F48);
                box.addView(row);
            }
        });
    }

    private void hookForm(String full, RepoWebhooks.Hook hook) {
        LinearLayout form = k.form();
        final EditText url = k.field(form, "https://example.com/hook", hook == null ? "" : hook.url, false);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        final CheckBox formEncoded = k.check(form, "Формат application/x-www-form-urlencoded (иначе JSON)", hook != null && "form".equals(hook.contentType));
        final EditText secret = k.field(form, "Секрет подписи (необязательно)", "", false);
        final EditText events = k.field(form, "События через запятую: push, pull_request или *", hook == null ? "push" : String.join(", ", hook.events), true);
        final CheckBox active = k.check(form, "Активен", hook == null || hook.active);
        k.form(hook == null ? "Новый webhook" : "Изменить webhook", hook == null ? "Создать" : "Сохранить", k.scroll(form), "Webhooks", hook == null ? "Webhook создан" : "Webhook обновлён", () -> webhooks(full), () -> {
            final String address = RepoWebhooks.checkUrl(url.getText().toString());
            final List<String> list = RepoWebhooks.events(events.getText().toString());
            final String type = formEncoded.isChecked() ? "form" : "json", sec = secret.getText().toString();
            final boolean on = active.isChecked();
            if (hook == null) return () -> RepoWebhooks.create(a.api, full, address, type, sec, list);
            final long id = hook.id;
            return () -> RepoWebhooks.update(a.api, full, id, address, type, sec, on, list);
        });
    }

    private void deliveries(String full, long hook) {
        a.io.execute(() -> {
            try {
                final List<RepoWebhooks.Delivery> list = RepoWebhooks.deliveries(a.api, full, hook);
                a.runOnUiThread(() -> {
                    if (list.isEmpty()) { a.toast("Доставок пока нет"); return; }
                    final String[] labels = new String[list.size()];
                    for (int i = 0; i < labels.length; i++) { RepoWebhooks.Delivery d = list.get(i); labels[i] = (d.code >= 200 && d.code < 300 ? "✓ " : "✗ ") + d.code + " · " + d.event + " · " + d.at.replace('T', ' ').replace("Z", ""); }
                    k.choose("Доставки — нажмите, чтобы повторить", labels, which -> {
                        final long delivery = list.get(which).id;
                        k.run("Webhooks", "Повторная доставка запрошена", null, () -> RepoWebhooks.redeliver(a.api, full, hook, delivery));
                    });
                });
            } catch (Exception error) {
                final String text = Gh.explain(error, "Webhooks");
                a.runOnUiThread(() -> a.errorDialog("Доставки", text));
            }
        });
    }

    // ---- Collaborators ---------------------------------------------------------------------------------------------

    void collaborators(String full) {
        if (!k.open(full, "Доступ", "Collaborators", () -> collaborators(full))) return;
        k.note("Кто имеет доступ к репозиторию. Приглашённый получает письмо и должен принять приглашение. Нажмите на человека, чтобы сменить роль.");
        k.action("+  Пригласить человека", () -> invite(full));
        final LinearLayout peopleBox = section("Участники");
        k.load("Administration", () -> Collaborators.people(a.api, full), list -> {
            if (list.isEmpty()) k.line(peopleBox, "Список пуст.", 13, false, a.MUTED);
            for (Collaborators.Person p : list) {
                LinearLayout row = k.entry();
                k.line(row, p.login, 15, true, a.TEXT);
                k.line(row, Collaborators.label(p.role), 12, false, a.MUTED);
                LinearLayout buttons = k.buttons(row);
                final String login = p.login;
                k.small(buttons, "Роль", () -> k.choose("Роль для " + login, Collaborators.ROLE_LABELS, which -> k.run("Administration", "Роль изменена", () -> collaborators(full), () -> Collaborators.invite(a.api, full, login, Collaborators.ROLES[which]))));
                final Button del = k.small(buttons, "Убрать", () -> k.confirm("Убрать " + login + "?", "Доступ к репозиторию будет отозван.", "Убрать", () -> k.run("Administration", "Доступ отозван", () -> collaborators(full), () -> Collaborators.remove(a.api, full, login))));
                del.setTextColor(0xFFC34F48);
                peopleBox.addView(row);
            }
        });
        final LinearLayout inviteBox = section("Ожидают ответа");
        k.load("Administration", () -> Collaborators.invites(a.api, full), list -> {
            if (list.isEmpty()) k.line(inviteBox, "Приглашений нет.", 13, false, a.MUTED);
            for (Collaborators.Invite i : list) {
                LinearLayout row = k.entry();
                k.line(row, i.login, 15, true, a.TEXT);
                k.line(row, "Приглашён · " + Collaborators.label(i.role), 12, false, a.MUTED);
                final long id = i.id;
                final Button cancel = k.small(k.buttons(row), "Отозвать", () -> k.run("Administration", "Приглашение отозвано", () -> collaborators(full), () -> Collaborators.cancelInvite(a.api, full, id)));
                cancel.setTextColor(0xFFC34F48);
                inviteBox.addView(row);
            }
        });
    }

    private void invite(String full) {
        LinearLayout form = k.form();
        final EditText user = k.field(form, "Логин GitHub, например octocat", "", false);
        final RadioGroup role = k.radios(form, Collaborators.ROLE_LABELS, 2);
        k.form("Пригласить", "Отправить", k.scroll(form), "Administration", "Приглашение отправлено", () -> collaborators(full), () -> {
            final String login = Collaborators.checkUser(user.getText().toString());
            final String value = Collaborators.ROLES[SettingsKit.selected(role)];
            return () -> Collaborators.invite(a.api, full, login, value);
        });
    }
}
