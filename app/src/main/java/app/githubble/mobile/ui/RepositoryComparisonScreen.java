package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.mirror.MirrorPolicy;
import app.githubble.mobile.repository.RepositoryFileComparison;
import app.githubble.mobile.repository.RepositorySelectiveTransfer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Dedicated file-level comparison and selective PR workflow.
 * It deliberately does not duplicate Mirror Manager's full-history sync UI.
 */
public final class RepositoryComparisonScreen {
    private RepositoryComparisonScreen() { }

    private static int dp(Activity a, int value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }

    private static TextView text(Activity a, String value, int size,
                                 int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(a, 5), 0, dp(a, 5));
        return t;
    }

    private static void panel(Activity a, LinearLayout parent, View child,
                              int color, int stroke) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 16), dp(a, 13), dp(a, 16), dp(a, 13));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(a, 16));
        shape.setStroke(dp(a, 1), stroke);
        box.setBackground(shape);
        box.addView(child);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(a, 5), 0, dp(a, 9));
        parent.addView(box, lp);
    }

    private static void error(Activity a, String message) {
        new AlertDialog.Builder(a).setTitle("Сравнение репозиториев")
                .setMessage(message == null ? "Операция не выполнена" : message)
                .setPositiveButton("Закрыть", null).show();
    }

    public static void render(Activity activity, LinearLayout host,
                              GitHubAccountStore store, String source,
                              int card, int colorText, int muted, int accent, int border,
                              Executor background, Consumer<String> openUrl) {
        List<GitHubAccountStore.Account> connected = store.listAccounts();
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.addView(text(activity, "Сравнение по файлам", 20, colorText, true));
        header.addView(text(activity,
                "В отличие от Mirror Manager здесь можно выбрать отдельные изменения. "
                + "Они попадут в новую ветку и Draft PR, а не непосредственно в main.",
                13, muted, false));
        panel(activity, host, header, card, border);
        if (connected.isEmpty()) {
            host.addView(text(activity, "Подключите GitHub-аккаунт для сравнения",
                    14, muted, false));
            return;
        }

        RepositorySelectiveTransfer manager = new RepositorySelectiveTransfer(store);
        LinearLayout settings = new LinearLayout(activity);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.addView(text(activity, "Исходный репозиторий", 12, muted, true));
        settings.addView(text(activity, source, 15, colorText, true));

        List<String> labels = new ArrayList<>();
        int initialSource = 0;
        int initialDestination = 0;
        String owner = source.substring(0, source.indexOf('/'));
        for (int i = 0; i < connected.size(); i++) {
            GitHubAccountStore.Account a = connected.get(i);
            labels.add("@" + a.login);
            if (owner.equalsIgnoreCase(a.login)) initialSource = i;
        }
        if (connected.size() > 1) initialDestination = initialSource == 0 ? 1 : 0;
        settings.addView(text(activity, "Доступ к исходнику через аккаунт", 12, muted, false));
        Spinner sourceAccount = new Spinner(activity);
        sourceAccount.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        sourceAccount.setSelection(initialSource);
        settings.addView(sourceAccount, new LinearLayout.LayoutParams(-1, dp(activity, 48)));

        settings.addView(text(activity, "Целевой аккаунт", 12, muted, true));
        Spinner destinationAccount = new Spinner(activity);
        destinationAccount.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        destinationAccount.setSelection(initialDestination);
        settings.addView(destinationAccount, new LinearLayout.LayoutParams(-1, dp(activity, 48)));

        settings.addView(text(activity, "Существующий репозиторий назначения", 12, muted, true));
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setHint("Имя репозитория");
        name.setText(source.substring(source.indexOf('/') + 1));
        name.setSelectAllOnFocus(true);
        settings.addView(name);

        Button compare = new Button(activity);
        compare.setAllCaps(false);
        compare.setText("Сравнить содержимое");
        settings.addView(compare);
        panel(activity, host, settings, card, border);

        LinearLayout results = new LinearLayout(activity);
        results.setOrientation(LinearLayout.VERTICAL);
        host.addView(results, new LinearLayout.LayoutParams(-1, -2));

        compare.setOnClickListener(view -> {
            String destinationName = name.getText().toString().trim();
            try { MirrorPolicy.requireName(destinationName); }
            catch (Exception invalid) { name.setError(invalid.getMessage()); return; }
            int si = sourceAccount.getSelectedItemPosition();
            int di = destinationAccount.getSelectedItemPosition();
            if (si < 0 || di < 0 || si >= connected.size() || di >= connected.size()) return;
            String destination = connected.get(di).login + "/" + destinationName;
            try { MirrorPolicy.requireDifferent(source, destination); }
            catch (Exception invalid) { name.setError(invalid.getMessage()); return; }
            String srcAccountId = connected.get(si).id;
            String dstAccountId = connected.get(di).id;

            compare.setEnabled(false);
            results.removeAllViews();
            ProgressDialog spinner = ProgressDialog.show(activity, "Сравнение Git",
                    "Загружаются деревья обеих основных веток…", true, false);
            background.execute(() -> {
                try {
                    RepositorySelectiveTransfer.Comparison found = manager.compare(
                            source, srcAccountId, destination, dstAccountId);
                    activity.runOnUiThread(() -> {
                        spinner.dismiss(); compare.setEnabled(true);
                        if (host.getParent() != null && !activity.isFinishing())
                            renderDiffs(activity, results, found, manager,
                                    card, colorText, muted, accent, border, background, openUrl);
                    });
                } catch (Exception failure) {
                    activity.runOnUiThread(() -> {
                        spinner.dismiss(); compare.setEnabled(true);
                        error(activity, failure.getMessage());
                    });
                }
            });
        });
    }

    private static void renderDiffs(Activity activity, LinearLayout host,
                                    RepositorySelectiveTransfer.Comparison comparison,
                                    RepositorySelectiveTransfer manager,
                                    int card, int colorText, int muted, int accent, int border,
                                    Executor background, Consumer<String> openUrl) {
        host.removeAllViews();
        LinearLayout summary = new LinearLayout(activity);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.addView(text(activity, "Различия основных веток", 17, colorText, true));
        summary.addView(text(activity, comparison.source + " (" + comparison.sourceBranch + ")"
                + "\n→ " + comparison.destination + " (" + comparison.destinationBranch + ")",
                13, muted, false));
        int add = 0, change = 0, onlyDest = 0, available = 0;
        for (RepositoryFileComparison.Delta item : comparison.changes) {
            if (RepositoryFileComparison.ADD.equals(item.status)) add++;
            else if (RepositoryFileComparison.UPDATE.equals(item.status)) change++;
            else onlyDest++;
            if (item.selectable()) available++;
        }
        summary.addView(text(activity, "Новых: " + add + " · Изменённых: " + change
                + " · Только в назначении: " + onlyDest, 13, colorText, false));
        summary.addView(text(activity, "Доступно для выбора: " + available
                + " · максимум 12 файлов / 4 МиБ. Целевые файлы не удаляются.",
                12, muted, false));
        if (comparison.sourcePrivate && !comparison.destinationPrivate)
            summary.addView(text(activity, "Приватный источник → публичное назначение:"
                    + " выборочный перенос заблокирован.", 13, accent, true));
        panel(activity, host, summary, card, border);

        if (comparison.changes.isEmpty()) {
            host.addView(text(activity, "Содержимое совпадает. Перенос не требуется.",
                    14, muted, false));
            return;
        }
        Set<String> selected = new LinkedHashSet<>();
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        TextView counter = text(activity, "Выбрано: 0 файлов", 13, colorText, true);
        list.addView(counter);

        EditText filter = new EditText(activity);
        filter.setSingleLine(true);
        filter.setHint("Фильтр по пути файла или папки");
        filter.setTextSize(13);
        list.addView(filter);
        LinearLayout visible = new LinearLayout(activity);
        visible.setOrientation(LinearLayout.VERTICAL);
        list.addView(visible);
        final int pageLimit = 160;

        Runnable refresh = () -> {
            visible.removeAllViews();
            String needle = filter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            int shown = 0, matched = 0;
            for (RepositoryFileComparison.Delta change : comparison.changes) {
                if (!needle.isEmpty() && !change.path.toLowerCase(java.util.Locale.ROOT)
                        .contains(needle)) continue;
                matched++;
                if (shown >= pageLimit) continue;
                shown++;
                String mode = RepositoryFileComparison.ADD.equals(change.status) ? "Добавить"
                        : RepositoryFileComparison.UPDATE.equals(change.status) ? "Обновить"
                        : "Только в назначении";
                if (!change.selectable()) {
                    visible.addView(text(activity, mode + " · " + change.path
                            + "\n" + change.disabledReason, 12, muted, false));
                } else {
                    CheckBox item = new CheckBox(activity);
                    item.setText(mode + " · " + change.path + " (" + change.source.size + " Б)");
                    item.setTextSize(13);
                    item.setTextColor(colorText);
                    item.setPadding(0, dp(activity, 5), 0, dp(activity, 5));
                    item.setChecked(selected.contains(change.path));
                    item.setOnCheckedChangeListener((button, checked) -> {
                        if (checked) {
                            long total = change.source.size;
                            for (RepositoryFileComparison.Delta existing : comparison.changes)
                                if (selected.contains(existing.path)) total += existing.source.size;
                            if (selected.size() >= RepositoryFileComparison.MAX_SELECTED_FILES
                                    || total > RepositoryFileComparison.MAX_SELECTED_BYTES) {
                                button.setChecked(false);
                                Toast.makeText(activity, "Лимит 12 файлов / 4 МиБ",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            selected.add(change.path);
                        } else selected.remove(change.path);
                        counter.setText("Выбрано: " + selected.size() + " файлов");
                    });
                    visible.addView(item);
                }
            }
            if (matched > shown)
                visible.addView(text(activity, "Показаны первые " + shown + " из " + matched
                        + " совпадений. Уточните фильтр, чтобы выбрать другие файлы.",
                        12, muted, false));
            if (matched == 0)
                visible.addView(text(activity, "По этому фильтру файлов нет",
                        12, muted, false));
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refresh.run();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        refresh.run();
        panel(activity, host, list, card, border);

        Button stage = new Button(activity);
        stage.setAllCaps(false);
        stage.setText("Подготовить Draft Pull Request");
        host.addView(stage, new LinearLayout.LayoutParams(-1, dp(activity, 52)));
        if (comparison.sourcePrivate && !comparison.destinationPrivate) stage.setEnabled(false);
        stage.setOnClickListener(view -> {
            if (selected.isEmpty()) {
                Toast.makeText(activity, "Отметьте нужные файлы", Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> chosen = new ArrayList<>(selected);
            new AlertDialog.Builder(activity)
                    .setTitle("Перенести выбранные файлы?")
                    .setMessage("Создать отдельную ветку и черновик Pull Request в "
                            + comparison.destination + "?\n\nФайлов: " + chosen.size()
                            + "\nОсновные ветки не меняются, автоматического merge не будет.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Создать PR", (d, which) -> {
                        stage.setEnabled(false);
                        ProgressDialog progress = ProgressDialog.show(activity, "Выборочный перенос",
                                "Проверка SHA, передача файлов и создание PR…", true, false);
                        background.execute(() -> {
                            try {
                                RepositorySelectiveTransfer.PreparedPullRequest pr =
                                        manager.preparePullRequest(comparison, chosen);
                                activity.runOnUiThread(() -> {
                                    progress.dismiss(); stage.setEnabled(true);
                                    new AlertDialog.Builder(activity)
                                            .setTitle("Черновик PR создан")
                                            .setMessage("Перенесено файлов: " + pr.files + "\nВетка: "
                                                    + pr.branch + "\n\nИзменения ожидают проверки.")
                                            .setPositiveButton("Открыть PR",
                                                    (dialog, pressed) -> openUrl.accept(pr.url))
                                            .setNegativeButton("Закрыть", null)
                                            .show();
                                });
                            } catch (Exception failure) {
                                activity.runOnUiThread(() -> {
                                    progress.dismiss(); stage.setEnabled(true);
                                    error(activity, failure.getMessage());
                                });
                            }
                        });
                    }).show();
        });
    }
}
