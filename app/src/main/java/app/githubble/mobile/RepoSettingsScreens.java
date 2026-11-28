package app.githubble.mobile;

import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.githubble.mobile.repository.DeployKeys;
import app.githubble.mobile.repository.RepoSecrets;
import app.githubble.mobile.ui.SheetDialog;
import java.util.List;

/** Repository «Настройки» as on github.com: sections for general settings, Actions, Environments, Deploy keys and Secrets and variables. */
final class RepoSettingsScreens {
    private final MainActivity a;
    private final SettingsKit kit;
    private final RepoSettingsCode code;
    private final RepoSettingsRest rest;
    private final RepoRevertScreen revert;
    private final RepoSettingsOps ops;
    private final RepoCopyScreen copy;
    private final RepoSettingsMore more;
    RepoSettingsScreens(MainActivity activity) { this.a = activity; this.kit = new SettingsKit(activity); this.code = new RepoSettingsCode(activity, kit); this.rest = new RepoSettingsRest(activity, kit); this.revert = new RepoRevertScreen(activity, kit); this.ops = new RepoSettingsOps(activity, kit); this.copy = new RepoCopyScreen(activity, kit); this.more = new RepoSettingsMore(activity, kit); }

    void hub(String full) {
        if (!full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) { a.errorDialog("Настройки", "Некорректное имя репозитория"); return; }
        a.setCurrentScreen(() -> hub(full));
        a.shell("Настройки", false);
        a.repoSectionHeader(full, "Настройки", "Как на github.com → Settings", R.drawable.ic_settings);
        if (!a.isSignedIn()) { a.accountRequiredCard("Подключите GitHub, чтобы менять настройки репозитория."); return; }
        a.sectionTitle("Общие", null);
        GridLayout general = new GridLayout(a); general.setColumnCount(a.adaptiveColumns(2));
        a.addAction(general, "Общие: описание, темы, слияние", () -> a.navigate(() -> rest.general(full)));
        a.addAction(general, "Имя, видимость, удаление", () -> a.navigate(() -> a.openRepositoryAdmin(full)));
        a.addAction(general, "Доступ · Collaborators", () -> a.navigate(() -> code.collaborators(full)));
        a.addAction(general, "Branches и Rulesets", () -> a.navigate(() -> code.branches(full)));
        a.addAction(general, "Копировать настройки из…", () -> a.navigate(() -> copy.show(full)));
        a.addAction(general, "Очистка веток", () -> a.navigate(() -> ops.cleanup(full)));
        a.addAction(general, "Откат и отмена коммитов", () -> a.navigate(() -> revert.show(full)));
        a.addAction(general, "Custom properties", () -> a.navigate(() -> rest.properties(full)));
        a.content.addView(general);
        a.sectionTitle("Сообщество и аналитика", null);
        GridLayout community = new GridLayout(a); community.setColumnCount(a.adaptiveColumns(2));
        a.addAction(community, "Метки · Labels", () -> a.navigate(() -> more.labels(full)));
        a.addAction(community, "Этапы · Milestones", () -> a.navigate(() -> more.milestones(full)));
        a.addAction(community, "CODEOWNERS и шаблоны", () -> a.navigate(() -> more.files(full)));
        a.addAction(community, "Мастер Dependabot", () -> a.navigate(() -> more.dependabot(full)));
        a.addAction(community, "Трафик", () -> a.navigate(() -> more.traffic(full)));
        a.content.addView(community);
        a.sectionTitle("Код и автоматизация", null);
        GridLayout automation = new GridLayout(a); automation.setColumnCount(a.adaptiveColumns(2));
        a.addAction(automation, "Actions: запуски и кэши", () -> a.navigate(() -> more.actions(full)));
        a.addAction(automation, "Actions: общие", () -> a.navigate(() -> code.actionsGeneral(full)));
        a.addAction(automation, "Actions: хранилище", () -> a.navigate(() -> a.openActionsStorage(full)));
        a.addAction(automation, "Environments", () -> a.navigate(() -> a.actionsSecretsScreen().environments(full)));
        a.addAction(automation, "Релизы и теги", () -> a.navigate(() -> ops.releases(full)));
        a.addAction(automation, "Деплои на одобрение", () -> a.navigate(() -> ops.deployments(full)));
        a.addAction(automation, "Webhooks", () -> a.navigate(() -> code.webhooks(full)));
        a.addAction(automation, "Pages", () -> a.navigate(() -> rest.pages(full)));
        a.content.addView(automation);
        a.sectionTitle("Безопасность", null);
        GridLayout security = new GridLayout(a); security.setColumnCount(a.adaptiveColumns(2));
        a.addAction(security, "Code security и оповещения", () -> a.navigate(() -> rest.security(full)));
        a.addAction(security, "Deploy keys", () -> a.navigate(() -> deployKeys(full)));
        a.addAction(security, "Secrets · Actions", () -> a.navigate(() -> a.actionsSecretsScreen().settings(full, 0)));
        a.addAction(security, "Variables · Actions", () -> a.navigate(() -> a.actionsSecretsScreen().settings(full, 1)));
        a.addAction(security, "Secrets · Dependabot", () -> a.navigate(() -> a.actionsSecretsScreen().settings(full, 2)));
        a.addAction(security, "Secrets · Codespaces", () -> a.navigate(() -> a.actionsSecretsScreen().settings(full, 3)));
        a.content.addView(security);
        TextView note = a.text("Всё, что есть на сайте в Settings → Secrets and variables и Environments, выполняется здесь напрямую через GitHub API. Для приватных репозиториев нужны права администратора.", 12, false);
        note.setTextColor(a.MUTED); a.card(note);
    }

    void deployKeys(String full) {
        a.setCurrentScreen(() -> deployKeys(full));
        a.shell("Deploy keys", false);
        a.repoSectionHeader(full, "Настройки", "Deploy keys", R.drawable.ic_settings);
        if (!a.isSignedIn()) { a.accountRequiredCard("Подключите GitHub, чтобы управлять ключами."); return; }
        final long generation = a.screenGeneration.get();
        final LinearLayout host = a.content;
        TextView note = a.text("Открытый SSH-ключ даёт серверу или CI доступ к этому одному репозиторию. По умолчанию только чтение; запись включайте, только если она нужна. Закрытый ключ сюда вставлять нельзя — приложение его отклонит.", 12, false);
        note.setTextColor(a.MUTED); a.card(note);
        Button add = a.button("+  Новый deploy key");
        add.setOnClickListener(v -> addKey(full));
        a.content.addView(add, new LinearLayout.LayoutParams(-1, a.dp(50)));
        final TextView status = a.text("Загружаем ключи…", 12, false);
        status.setTextColor(a.MUTED); a.card(status);
        final LinearLayout rows = new LinearLayout(a); rows.setOrientation(LinearLayout.VERTICAL);
        a.content.addView(rows);
        a.io.execute(() -> {
            try {
                List<DeployKeys.Key> keys = DeployKeys.list(a.api, full);
                a.runOnUiThread(() -> {
                    if (generation != a.screenGeneration.get() || host != a.content) return;
                    status.setText("Ключей: " + keys.size());
                    if (keys.isEmpty()) { TextView empty = a.text("Ключей пока нет.", 13, false); empty.setTextColor(a.MUTED); rows.addView(empty); }
                    for (DeployKeys.Key key : keys) rows.addView(keyRow(full, key));
                });
            } catch (Exception error) {
                final String text = RepoSecrets.explain(error);
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) { status.setText(text); status.setTextColor(0xFFC34F48); } });
            }
        });
    }

    private View keyRow(String full, DeployKeys.Key key) {
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(14), a.dp(10), a.dp(12), a.dp(10)); box.setBackground(a.round(a.CARD, 14, a.BORDER, 1));
        TextView title = a.text(key.title, 15, true); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); box.addView(title);
        TextView body = a.text(DeployKeys.preview(key.key), 12, false); body.setTextColor(a.TEXT); body.setTypeface(android.graphics.Typeface.MONOSPACE); body.setPadding(0, a.dp(3), 0, 0); box.addView(body);
        String created = key.created.length() >= 10 ? key.created.substring(0, 10) : key.created;
        TextView meta = a.text((key.readOnly ? "Только чтение" : "Чтение и запись") + (created.isEmpty() ? "" : " · добавлен " + created), 12, false);
        meta.setTextColor(key.readOnly ? a.MUTED : 0xFFB07010); meta.setPadding(0, a.dp(3), 0, a.dp(6)); box.addView(meta);
        Button del = a.compactButton("Удалить"); del.setTextColor(0xFFC34F48);
        del.setOnClickListener(v -> new SheetDialog.Builder(a).setTitle("Удалить ключ «" + key.title + "»?")
            .setMessage("Сервер, который им пользуется, потеряет доступ к репозиторию.")
            .setPositiveButton("Удалить", (d, w) -> a.io.execute(() -> {
                try { DeployKeys.delete(a.api, full, key.id); a.runOnUiThread(() -> { a.toast("Ключ удалён"); deployKeys(full); }); }
                catch (Exception e) { final String m = RepoSecrets.explain(e); a.runOnUiThread(() -> a.errorDialog("Не удалено", m)); }
            })).setNegativeButton("Отмена", null).show());
        box.addView(del, new LinearLayout.LayoutParams(-1, a.dp(40)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, a.dp(8), 0, 0);
        box.setLayoutParams(lp);
        return box;
    }

    private void addKey(String full) {
        LinearLayout form = new LinearLayout(a); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(a.dp(20), 0, a.dp(20), 0);
        final EditText title = new EditText(a); title.setSingleLine(true); title.setHint("Название, например server-prod"); title.setTextColor(a.TEXT); title.setHintTextColor(a.MUTED);
        final EditText key = new EditText(a); key.setHint("ssh-ed25519 AAAA… комментарий"); key.setMinLines(4); key.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        key.setTextColor(a.TEXT); key.setHintTextColor(a.MUTED); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final CheckBox write = new CheckBox(a); write.setText("Разрешить запись (push)"); write.setTextColor(a.TEXT);
        form.addView(title); form.addView(key); form.addView(write);
        SheetDialog dialog = new SheetDialog.Builder(a).setTitle("Новый deploy key").setView(form).setPositiveButton("Добавить", null).setNegativeButton("Отмена", null).show();
        dialog.setOnShowListener(x -> dialog.getButton(-1).setOnClickListener(w -> {
            final String t = title.getText().toString(), k = key.getText().toString(); final boolean allowWrite = write.isChecked();
            try { if (t.trim().isEmpty()) throw new IllegalArgumentException("Введите название"); DeployKeys.checkPublicKey(k); }
            catch (IllegalArgumentException bad) { (t.trim().isEmpty() ? title : key).setError(bad.getMessage()); return; }
            dialog.dismiss();
            a.io.execute(() -> {
                try { DeployKeys.add(a.api, full, t, k, allowWrite); a.runOnUiThread(() -> { a.toast("Ключ добавлен"); deployKeys(full); }); }
                catch (Exception e) { final String m = RepoSecrets.explain(e); a.runOnUiThread(() -> a.errorDialog("Ключ не добавлен", m)); }
            });
        }));
    }
}
