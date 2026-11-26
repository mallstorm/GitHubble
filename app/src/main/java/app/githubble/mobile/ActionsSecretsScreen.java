package app.githubble.mobile;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.githubble.mobile.agent.ReleaseSigning;
import app.githubble.mobile.repository.RepoSecrets;
import app.githubble.mobile.repository.RepoSecrets.Kind;
import app.githubble.mobile.ui.SheetDialog;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Repository «Секреты и переменные»: Actions secrets and variables, Dependabot secrets and the Android signing key, all on the phone. */
final class ActionsSecretsScreen {
    private final MainActivity a;
    ActionsSecretsScreen(MainActivity activity) { this.a = activity; this.protection = new EnvironmentProtectionView(activity); }

    /** Segments of the repository level; the last one lists environments. Inside an environment only the first two exist. */
    private static final Kind[] KINDS = {Kind.ACTIONS_SECRET, Kind.ACTIONS_VARIABLE, Kind.DEPENDABOT_SECRET, Kind.CODESPACES_SECRET};
    private static final int PROTECTION = 2;
    /** True when opened from the Actions tabs; false when opened from the repository Settings. */
    private boolean actionsTabs = true;
    private final EnvironmentProtectionView protection;

    void show(String full, int segment) { actionsTabs = true; show(full, segment, null); }

    /** Entry from Settings → Secrets and variables (segments: Actions secrets, Actions variables, Dependabot, Codespaces). */
    void settings(String full, int segment) { actionsTabs = false; show(full, segment, null); }

    void show(String full, int segment, String env) {
        if (!full.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) { a.errorDialog("Секреты", "Некорректное имя репозитория"); return; }
        a.setCurrentScreen(() -> show(full, segment, env));
        a.shell(env == null ? "Секреты и переменные" : "Окружение", false);
        a.repoSectionHeader(full, actionsTabs ? "GitHub Actions" : "Настройки", env == null ? "Secrets and variables" : "Окружение " + env, R.drawable.ic_android);
        if (actionsTabs && env == null) a.addRepositoryActionsTabs(full, 2);
        if (!a.isSignedIn()) { a.accountRequiredCard("Подключите GitHub, чтобы управлять секретами."); return; }
        final Kind kind = KINDS[env != null && segment == PROTECTION ? 0 : segment];
        final long generation = a.screenGeneration.get();
        final String account = a.currentAccountId();
        final LinearLayout host = a.content;

        LinearLayout segments = new LinearLayout(a);
        String[] names = env == null ? new String[]{"Actions", "Variables", "Dependabot", "Codespaces"} : new String[]{"Секреты", "Переменные", "Защита"};
        for (int i = 0; i < names.length; i++) {
            final int index = i; boolean active = i == segment;
            Button b = a.compactButton(names[i]);
            b.setTextSize(env == null ? 11 : 12); b.setPadding(a.dp(2), 0, a.dp(2), 0); b.setMaxLines(1);
            b.setTextColor(active ? 0xFFFFFFFF : a.GREEN);
            b.setBackground(a.round(active ? a.GREEN : a.CARD, 13, active ? a.GREEN : a.BORDER, active ? 0 : 1));
            b.setOnClickListener(v -> { if (index != segment) show(full, index, env); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, a.dp(42), 1f);
            if (i > 0) p.setMargins(a.dp(8), 0, 0, 0);
            segments.addView(b, p);
        }
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, a.dp(42)); sp.setMargins(0, 0, 0, a.dp(10));
        a.content.addView(segments, sp);

        if (env != null) {
            Button back = a.compactButton("←  К списку окружений");
            back.setOnClickListener(v -> environments(full));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, a.dp(40)); bp.setMargins(0, 0, 0, a.dp(10));
            a.content.addView(back, bp);
            if (segment == PROTECTION) { protection.render(full, env, () -> show(full, segment, env)); return; }
        }
        TextView note = a.text(kind.variable
            ? "Переменные видны всем, кто имеет доступ к репозиторию, и подставляются в workflow как vars.ИМЯ. Для паролей и ключей используйте секреты."
            : (kind == Kind.DEPENDABOT_SECRET
                ? "Секреты Dependabot доступны только его обновлениям и не видны обычным workflow."
                : kind == Kind.CODESPACES_SECRET
                ? "Секреты Codespaces попадают в окружение разработки репозитория как переменные окружения; значение шифруется на телефоне."
                : "Значение шифруется на телефоне открытым ключом репозитория и в открытом виде никуда не уходит. После сохранения GitHub его никому не показывает: секрет можно только заменить или удалить. В workflow он доступен как secrets.ИМЯ."), 12, false);
        note.setTextColor(a.MUTED); a.card(note);

        if (kind == Kind.ACTIONS_SECRET && env == null) signingCard(full);

        Button add = a.button(kind.variable ? "+  Новая переменная" : "+  Новый секрет");
        add.setOnClickListener(v -> editDialog(full, kind, env, null, null, () -> show(full, segment, env)));
        a.content.addView(add, new LinearLayout.LayoutParams(-1, a.dp(50)));

        final TextView status = a.text("Загружаем список…", 12, false);
        status.setTextColor(a.MUTED); a.card(status);
        final LinearLayout rows = new LinearLayout(a); rows.setOrientation(LinearLayout.VERTICAL);
        a.content.addView(rows);
        a.io.execute(() -> {
            try {
                List<RepoSecrets.Item> items = RepoSecrets.list(a.api, full, kind, env);
                a.runOnUiThread(() -> {
                    if (generation != a.screenGeneration.get() || host != a.content || !account.equals(a.currentAccountId())) return;
                    status.setText((kind.variable ? "Переменных: " : "Секретов: ") + items.size());
                    if (items.isEmpty()) { TextView empty = a.text(kind.variable ? "Переменных пока нет." : "Секретов пока нет.", 13, false); empty.setTextColor(a.MUTED); rows.addView(empty); }
                    for (RepoSecrets.Item item : items) rows.addView(row(full, kind, env, item, () -> show(full, segment, env)));
                });
            } catch (Exception error) {
                final String text = RepoSecrets.explain(error, kind, env);
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) { status.setText(text); status.setTextColor(0xFFC34F48); } });
            }
        });
    }

    private View row(String full, Kind kind, String env, RepoSecrets.Item item, Runnable reload) {
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(14), a.dp(10), a.dp(12), a.dp(10)); box.setBackground(a.round(a.CARD, 14, a.BORDER, 1));
        TextView name = a.text(item.name, 15, true); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); box.addView(name);
        if (kind.variable) {
            TextView value = a.text(item.value.isEmpty() ? "(пустое значение)" : item.value, 13, false);
            value.setMaxLines(3); value.setEllipsize(android.text.TextUtils.TruncateAt.END); value.setTextColor(a.TEXT); value.setPadding(0, a.dp(3), 0, 0); box.addView(value);
        }
        String updated = item.updated.length() >= 10 ? item.updated.substring(0, 10) : item.updated;
        TextView meta = a.text(kind.variable ? "Обновлена " + updated : "Значение скрыто · обновлён " + updated, 12, false);
        meta.setTextColor(a.MUTED); meta.setPadding(0, a.dp(3), 0, a.dp(6)); box.addView(meta);
        LinearLayout actions = new LinearLayout(a);
        Button edit = a.compactButton(kind.variable ? "Изменить" : "Заменить значение");
        edit.setOnClickListener(v -> editDialog(full, kind, env, item.name, item.value, reload));
        Button del = a.compactButton("Удалить"); del.setTextColor(0xFFC34F48);
        del.setOnClickListener(v -> new SheetDialog.Builder(a).setTitle("Удалить «" + item.name + "»?")
            .setMessage(kind.variable ? "Переменная пропадёт из репозитория; workflow, которые её используют, получат пустое значение." : "Секрет будет удалён из репозитория. Workflow, которые его используют, перестанут работать. Вернуть значение нельзя — GitHub его не хранит в открытом виде.")
            .setPositiveButton("Удалить", (d, w) -> a.io.execute(() -> {
                try { RepoSecrets.delete(a.api, full, kind, env, item.name); a.runOnUiThread(() -> { a.toast("Удалено: " + item.name); reload.run(); }); }
                catch (Exception e) { final String m = RepoSecrets.explain(e, kind, env); a.runOnUiThread(() -> a.errorDialog("Не удалено", m)); }
            })).setNegativeButton("Отмена", null).show());
        actions.addView(edit, new LinearLayout.LayoutParams(0, a.dp(40), 1.5f));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, a.dp(40), 1f); dp.setMargins(a.dp(8), 0, 0, 0);
        actions.addView(del, dp);
        box.addView(actions);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, a.dp(8), 0, 0);
        box.setLayoutParams(lp);
        return box;
    }

    private EditText field(String hint, String value, int lines) {
        EditText e = new EditText(a); e.setHint(hint); e.setText(value == null ? "" : value); e.setTextColor(a.TEXT); e.setHintTextColor(a.MUTED);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | (lines > 1 ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (lines > 1) { e.setMinLines(lines); e.setGravity(Gravity.TOP | Gravity.START); } else e.setSingleLine(true);
        return e;
    }

    /** New secret/variable (name == null) or a new value for an existing one. */
    private void editDialog(String full, Kind kind, String env, String existingName, String existingValue, Runnable reload) {
        LinearLayout form = new LinearLayout(a); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(a.dp(20), 0, a.dp(20), 0);
        final EditText name = field("ИМЯ_СЕКРЕТА", existingName, 1);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        if (existingName != null) { name.setEnabled(false); name.setAlpha(.6f); }
        final EditText value = field(kind.variable ? "Значение" : "Значение (нигде не сохраняется)", existingValue, 5);
        form.addView(name); form.addView(value);
        if (!kind.variable) {
            Button file = a.compactButton("📎  Вставить файл как Base64");
            file.setOnClickListener(v -> a.pickFile(uri -> a.io.execute(() -> {
                try {
                    byte[] bytes = read(uri);
                    String encoded = Base64.getEncoder().encodeToString(bytes);
                    if (encoded.length() > RepoSecrets.MAX_VALUE_BYTES) throw new IllegalArgumentException("Файл слишком большой: после Base64 больше 48 КБ, GitHub такой секрет не примет");
                    a.runOnUiThread(() -> { value.setText(encoded); a.toast("Файл вставлен (" + bytes.length + " Б)"); });
                } catch (Exception e) { final String m = e.getMessage(); a.runOnUiThread(() -> a.errorDialog("Файл не прочитан", m)); }
            })));
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, a.dp(42)); fp.setMargins(0, a.dp(8), 0, 0);
            form.addView(file, fp);
        }
        SheetDialog dialog = new SheetDialog.Builder(a).setTitle(existingName == null ? (kind.variable ? "Новая переменная" : "Новый секрет") : existingName)
            .setView(form).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).show();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {
            final String n, val = value.getText().toString();
            try { n = RepoSecrets.checkName(name.getText().toString()); if (val.isEmpty()) throw new IllegalArgumentException("Введите значение"); }
            catch (IllegalArgumentException bad) { (val.isEmpty() ? value : name).setError(bad.getMessage()); return; }
            dialog.dismiss();
            a.toast("Сохраняем…");
            a.io.execute(() -> {
                try {
                    if (kind.variable) RepoSecrets.saveVariable(a.api, full, env, n, val, existingName != null || exists(full, env, n));
                    else RepoSecrets.putSecret(a.api, full, kind, env, n, val);
                    a.runOnUiThread(() -> { a.toast("Сохранено: " + n); reload.run(); });
                } catch (Exception e) { final String m = RepoSecrets.explain(e, kind, env); a.runOnUiThread(() -> a.errorDialog("Не сохранено", m)); }
            });
        }));
    }

    private boolean exists(String full, String env, String name) {
        try { for (RepoSecrets.Item i : RepoSecrets.list(a.api, full, Kind.ACTIONS_VARIABLE, env)) if (i.name.equals(name)) return true; }
        catch (Exception ignored) { /* the save itself reports a real problem */ }
        return false;
    }

    private byte[] read(android.net.Uri uri) throws Exception {
        try (InputStream in = a.getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("Файл недоступен");
            byte[] buffer = new byte[8192]; int n, total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > 64 * 1024) throw new IllegalArgumentException("Файл больше 64 КБ — GitHub ограничивает секрет 48 КБ");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    // ---- environments ---------------------------------------------------------------------------------------------

    /** Settings → Environments: the list; each environment opens its secrets, variables and protection rules. */
    void environments(String full) {
        a.setCurrentScreen(() -> environments(full));
        a.shell("Environments", false);
        a.repoSectionHeader(full, actionsTabs ? "GitHub Actions" : "Настройки", "Environments", R.drawable.ic_android);
        if (!a.isSignedIn()) { a.accountRequiredCard("Подключите GitHub, чтобы управлять окружениями."); return; }
        final long generation = a.screenGeneration.get();
        final String account = a.currentAccountId();
        final LinearLayout host = a.content;
        TextView note = a.text("Окружения (production, staging…) имеют свои секреты и переменные, видимые только заданиям workflow с `environment: имя`, и правила защиты: проверяющие, ожидание, допустимые ветки. Откройте окружение, чтобы настроить всё это.", 12, false);
        note.setTextColor(a.MUTED); a.card(note);
        Button add = a.button("+  Новое окружение");
        add.setOnClickListener(v -> {
            LinearLayout form = new LinearLayout(a); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(a.dp(20), 0, a.dp(20), 0);
            final EditText name = field("production", "", 1);
            form.addView(name);
            SheetDialog dialog = new SheetDialog.Builder(a).setTitle("Новое окружение").setView(form).setPositiveButton("Создать", null).setNegativeButton("Отмена", null).show();
            dialog.setOnShowListener(x -> dialog.getButton(-1).setOnClickListener(w -> {
                final String n;
                try { n = RepoSecrets.checkEnvironment(name.getText().toString()); }
                catch (IllegalArgumentException bad) { name.setError(bad.getMessage()); return; }
                dialog.dismiss();
                a.io.execute(() -> {
                    try { RepoSecrets.createEnvironment(a.api, full, n); a.runOnUiThread(() -> { a.toast("Окружение создано: " + n); environments(full); }); }
                    catch (Exception e) { final String m = RepoSecrets.explain(e, "Environments"); a.runOnUiThread(() -> a.errorDialog("Не создано", m)); }
                });
            }));
        });
        a.content.addView(add, new LinearLayout.LayoutParams(-1, a.dp(50)));
        final TextView status = a.text("Загружаем окружения…", 12, false);
        status.setTextColor(a.MUTED); a.card(status);
        final LinearLayout rows = new LinearLayout(a); rows.setOrientation(LinearLayout.VERTICAL);
        a.content.addView(rows);
        a.io.execute(() -> {
            try {
                List<RepoSecrets.Item> items = RepoSecrets.listEnvironments(a.api, full);
                a.runOnUiThread(() -> {
                    if (generation != a.screenGeneration.get() || host != a.content || !account.equals(a.currentAccountId())) return;
                    status.setText("Окружений: " + items.size());
                    if (items.isEmpty()) { TextView empty = a.text("Окружений пока нет.", 13, false); empty.setTextColor(a.MUTED); rows.addView(empty); }
                    for (RepoSecrets.Item item : items) rows.addView(environmentRow(full, item));
                });
            } catch (Exception error) {
                final String text = RepoSecrets.explain(error, "Environments");
                a.runOnUiThread(() -> { if (generation == a.screenGeneration.get() && host == a.content) { status.setText(text); status.setTextColor(0xFFC34F48); } });
            }
        });
    }

    private View environmentRow(String full, RepoSecrets.Item item) {
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(14), a.dp(10), a.dp(12), a.dp(10)); box.setBackground(a.round(a.CARD, 14, a.BORDER, 1));
        TextView name = a.text(item.name, 15, true); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); box.addView(name);
        String updated = item.updated.length() >= 10 ? item.updated.substring(0, 10) : item.updated;
        TextView meta = a.text(updated.isEmpty() ? "Окружение" : "Обновлено " + updated, 12, false);
        meta.setTextColor(a.MUTED); meta.setPadding(0, a.dp(3), 0, a.dp(6)); box.addView(meta);
        LinearLayout actions = new LinearLayout(a);
        Button open = a.compactButton("Настроить: секреты, защита");
        open.setOnClickListener(v -> show(full, 0, item.name));
        Button del = a.compactButton("Удалить"); del.setTextColor(0xFFC34F48);
        del.setOnClickListener(v -> new SheetDialog.Builder(a).setTitle("Удалить окружение «" + item.name + "»?")
            .setMessage("Вместе с окружением удалятся его секреты, переменные и правила защиты. Workflow, которые на него ссылаются, создадут пустое окружение заново или будут ждать одобрения. Вернуть удалённое нельзя.")
            .setPositiveButton("Удалить", (d, w) -> a.io.execute(() -> {
                try { RepoSecrets.deleteEnvironment(a.api, full, item.name); a.runOnUiThread(() -> { a.toast("Окружение удалено"); environments(full); }); }
                catch (Exception e) { final String m = RepoSecrets.explain(e, "Environments"); a.runOnUiThread(() -> a.errorDialog("Не удалено", m)); }
            })).setNegativeButton("Отмена", null).show());
        actions.addView(open, new LinearLayout.LayoutParams(0, a.dp(40), 1.8f));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, a.dp(40), 1f); dp.setMargins(a.dp(8), 0, 0, 0);
        actions.addView(del, dp);
        box.addView(actions);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, a.dp(8), 0, 0);
        box.setLayoutParams(lp);
        return box;
    }

    // ---- Android signing key -------------------------------------------------------------------------------------

    private void signingCard(String full) {
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12)); box.setBackground(a.round(a.CARD, 16, a.BORDER, 1));
        box.addView(a.text("🔑  Ключ подписи Android", 16, true));
        final TextView state = a.text("Проверяем, есть ли ключ в репозитории…", 12, false); state.setTextColor(a.MUTED); state.setPadding(0, a.dp(4), 0, a.dp(8));
        box.addView(state);
        Button create = a.compactButton("Создать новый ключ"), own = a.compactButton("Загрузить свой keystore");
        LinearLayout buttons = new LinearLayout(a);
        buttons.addView(create, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0, a.dp(42), 1f); op.setMargins(a.dp(8), 0, 0, 0);
        buttons.addView(own, op);
        box.addView(buttons);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, a.dp(10));
        a.content.addView(box, lp);
        final boolean[] exists = {false};
        a.io.execute(() -> {
            String text;
            try {
                exists[0] = ReleaseSigning.exists(a.api, full);
                text = exists[0] ? "В репозитории уже есть секреты подписи (" + ReleaseSigning.S_KEYSTORE + ", " + ReleaseSigning.S_STORE_PASSWORD + ", " + ReleaseSigning.S_ALIAS + ", " + ReleaseSigning.S_KEY_PASSWORD + "). Замена ключа сломает обновления уже подписанных приложений."
                    : "Ключа пока нет. Создайте новый на телефоне или загрузите свой .jks/.keystore: будут записаны четыре секрета " + ReleaseSigning.S_KEYSTORE + ", " + ReleaseSigning.S_STORE_PASSWORD + ", " + ReleaseSigning.S_ALIAS + ", " + ReleaseSigning.S_KEY_PASSWORD + ".";
            } catch (Exception e) { text = RepoSecrets.explain(e, "Secrets"); }
            final String shown = text;
            a.runOnUiThread(() -> state.setText(shown));
        });
        create.setOnClickListener(v -> confirmReplace(full, exists[0], "Создать ключ подписи?",
            "На телефоне будет создан ключ (RSA 2048, срок 30 лет) и записан в секреты репозитория. Копия сохранится в защищённом хранилище приложения.", () -> a.io.execute(() -> {
                try {
                    String shortName = full.substring(full.indexOf('/') + 1);
                    ReleaseSigning.Bundle bundle = ReleaseSigning.generate("release", shortName, "GitHubble");
                    ReleaseSigning.publish(a.api, full, bundle);
                    ReleaseSigning.backup(a, full, bundle);
                    a.runOnUiThread(() -> { a.errorDialog("Ключ записан", "Секреты подписи созданы в " + full + ".\nAlias: " + bundle.alias + "\nSHA-256 сертификата:\n" + bundle.sha256 + "\n\nКопия ключа хранится в защищённом хранилище приложения. Подключите секреты к workflow: ANDROID_KEYSTORE_BASE64 декодируется в файл, остальные три передаются в signingConfig."); show(full, 0); });
                } catch (Exception e) { final String m = RepoSecrets.explain(e, "Secrets"); a.runOnUiThread(() -> a.errorDialog("Ключ не создан", m)); }
            })));
        own.setOnClickListener(v -> a.pickFile(uri -> a.io.execute(() -> {
            try {
                final byte[] bytes = read(uri);
                a.runOnUiThread(() -> importDialog(full, exists[0], bytes));
            } catch (Exception e) { final String m = e.getMessage(); a.runOnUiThread(() -> a.errorDialog("Файл не прочитан", m)); }
        })));
    }

    private void importDialog(String full, boolean replace, byte[] keystore) {
        LinearLayout form = new LinearLayout(a); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(a.dp(20), 0, a.dp(20), 0);
        EditText storePassword = field("Пароль хранилища", "", 1), alias = field("Alias ключа", "", 1), keyPassword = field("Пароль ключа (пусто — такой же)", "", 1);
        storePassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(storePassword); form.addView(alias); form.addView(keyPassword);
        SheetDialog dialog = new SheetDialog.Builder(a).setTitle("Свой keystore")
            .setMessage("Файл " + keystore.length + " Б. Перед отправкой приложение откроет его локально и проверит пароли и alias." + (replace ? "\n\nВНИМАНИЕ: существующие секреты подписи будут заменены." : ""))
            .setView(form).setPositiveButton("Проверить и записать", null).setNegativeButton("Отмена", null).show();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {
            final String sp = storePassword.getText().toString(), al = alias.getText().toString().trim();
            final String kp = keyPassword.getText().toString().isEmpty() ? sp : keyPassword.getText().toString();
            if (sp.isEmpty()) { storePassword.setError("Введите пароль"); return; }
            if (al.isEmpty()) { alias.setError("Введите alias"); return; }
            dialog.dismiss();
            a.toast("Проверяем keystore…");
            a.io.execute(() -> {
                try {
                    String sha = ReleaseSigning.verifyKeystore(keystore, sp, al, kp);
                    ReleaseSigning.publishValues(a.api, full, keystore, sp, al, kp);
                    a.runOnUiThread(() -> { a.errorDialog("Keystore записан", "Четыре секрета подписи записаны в " + full + ".\nAlias: " + al + (sha.isEmpty() ? "" : "\nSHA-256 сертификата:\n" + sha)); show(full, 0); });
                } catch (Exception e) { final String m = e instanceof IllegalArgumentException ? e.getMessage() : RepoSecrets.explain(e); a.runOnUiThread(() -> a.errorDialog("Не записано", m)); }
            });
        }));
    }

    private void confirmReplace(String full, boolean exists, String title, String message, Runnable go) {
        new SheetDialog.Builder(a).setTitle(title)
            .setMessage(message + (exists ? "\n\nВНИМАНИЕ: существующие секреты подписи будут заменены — приложения, подписанные прежним ключом, перестанут обновляться поверх." : ""))
            .setPositiveButton(exists ? "Заменить" : "Создать", (d, w) -> go.run()).setNegativeButton("Отмена", null).show();
    }
}
