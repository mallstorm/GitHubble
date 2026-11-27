package app.githubble.mobile;

import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import app.githubble.mobile.repository.Gh;
import app.githubble.mobile.repository.SettingsCopy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Copies settings from another repository into this one. */
final class RepoCopyScreen {
    private final MainActivity a;
    private final SettingsKit k;
    RepoCopyScreen(MainActivity activity, SettingsKit kit) { this.a = activity; this.k = kit; }

    void show(String full) {
        if (!k.open(full, "Копировать настройки", "Из другого репозитория", () -> show(full))) return;
        k.note("Настройки выбранного репозитория будут записаны в этот: «" + full + "». Секреты GitHub не отдаёт, поэтому их скопировать нельзя; приложение покажет, каких секретов здесь не хватает. "
            + "Нужны права администратора в обоих репозиториях.");
        LinearLayout form = k.form(); form.setPadding(0, 0, 0, 0);
        k.line(form, "Источник", 13, true, a.TEXT);
        final String owner = full.substring(0, full.indexOf('/') + 1);
        final EditText source = k.field(form, "владелец/репозиторий", owner, false);
        k.line(form, "Что копировать", 13, true, a.TEXT);
        final List<CheckBox> boxes = new ArrayList<>();
        final SettingsCopy.Part[] parts = SettingsCopy.Part.values();
        for (SettingsCopy.Part part : parts) boxes.add(k.check(form, part.label, part != SettingsCopy.Part.WEBHOOKS));
        Button run = a.button("Копировать");
        run.setOnClickListener(v -> {
            final String src;
            try { src = SettingsCopy.checkSource(source.getText().toString(), full); } catch (IllegalArgumentException bad) { source.setError(bad.getMessage()); return; }
            final Set<SettingsCopy.Part> chosen = EnumSet.noneOf(SettingsCopy.Part.class);
            for (int i = 0; i < parts.length; i++) if (boxes.get(i).isChecked()) chosen.add(parts[i]);
            if (chosen.isEmpty()) { a.toast("Ничего не выбрано"); return; }
            k.confirm("Скопировать из «" + src + "»?", "Выбранные настройки репозитория «" + full + "» будут перезаписаны.", "Копировать", () -> a.io.execute(() -> {
                final List<String> report = SettingsCopy.run(a.api, src, full, chosen);
                String missing;
                try { List<String> names = SettingsCopy.missingSecrets(a.api, src, full); missing = names.isEmpty() ? "" : "\n\nСекреты, которых нет здесь (задайте вручную): " + String.join(", ", names); }
                catch (Exception e) { missing = ""; }
                final String text = String.join("\n", report) + missing;
                a.runOnUiThread(() -> a.errorDialog("Результат копирования", text));
            }));
        });
        form.addView(run, new LinearLayout.LayoutParams(-1, a.dp(50)));
        a.content.addView(form);
    }
}
