package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import app.githubble.mobile.dictation.DictationClient;
import app.githubble.mobile.dictation.DictationProviders;
import app.githubble.mobile.dictation.DictationSettings;

/** Dictation settings: choose the speech service, add keys, pick the language, allow fallback to other services. */
public final class DictationSheet {
    private final Activity activity;
    private final HomeScreen.Palette palette;
    private final DictationSettings settings;
    private final Runnable changed;
    private Dialog dialog;
    private LinearLayout list;

    private DictationSheet(Activity activity, HomeScreen.Palette palette, DictationSettings settings, Runnable changed) {
        this.activity = activity; this.palette = palette; this.settings = settings; this.changed = changed == null ? () -> { } : changed;
    }

    public static void show(Activity activity, HomeScreen.Palette palette, DictationSettings settings, Runnable changed) {
        new DictationSheet(activity, palette, settings, changed).open();
    }

    private void open() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(palette.background, 28, 0, 0));
        root.setClipToOutline(true);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(10), dp(18), dp(8));
        View grip = new View(activity);
        grip.setBackground(round(palette.muted, 3, 0, 0));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(32), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL; gp.bottomMargin = dp(14);
        header.addView(grip, gp);
        header.addView(text("Диктовка", 22, palette.text, true));
        header.addView(text("Голос превращается в текст в чате и в поиске. Подключите один или несколько сервисов — если основной не ответит, подойдёт запасной.", 12, palette.muted, false), lp(-1, -2, 0, 2, 0, 0));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18), dp(4), dp(18), dp(12));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout footer = new LinearLayout(activity);
        footer.setPadding(dp(18), dp(10), dp(18), dp(16));
        footer.addView(button("Готово", true, v -> dialog.dismiss()), lp(0, 48, 1f, 0, 0, 0, 0));
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        dialog = new Dialog(activity);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        render();
        dialog.show();
        if (w != null) {
            WindowManager.LayoutParams p = w.getAttributes();
            p.dimAmount = .38f; w.setAttributes(p); w.setGravity(Gravity.BOTTOM);
            w.setLayout(activity.getResources().getDisplayMetrics().widthPixels, activity.getResources().getDisplayMetrics().heightPixels - dp(56));
        }
    }

    private void render() {
        list.removeAllViews();
        list.addView(section("Сервис диктовки"), lp(-1, -2, 4, 6, 0, 6));
        String primary = settings.provider();
        for (final DictationProviders.Info info : DictationProviders.all()) list.addView(providerCard(info, info.id.equals(primary)), lp(-1, -2, 0, 0, 0, 8));

        list.addView(section("Язык"), lp(-1, -2, 4, 16, 0, 6));
        LinearLayout language = row(null);
        language.addView(body("Язык речи", DictationProviders.languageTitle(settings.language()) + (settings.language().equals("auto") ? " · определяет сервис" : "")), new LinearLayout.LayoutParams(0, -2, 1f));
        language.addView(text("›", 22, palette.muted, false), lp(-2, -2, 8, 0, 0, 0));
        language.setOnClickListener(v -> chooseLanguage());
        list.addView(language, lp(-1, -2, 0, 0, 0, 8));

        LinearLayout fallback = row(null);
        fallback.addView(body("Запасные сервисы", "Если основной сервис недоступен или ошибся — попробовать следующий из настроенных"), new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(activity);
        sw.setChecked(settings.fallback());
        tint(sw);
        sw.setOnCheckedChangeListener((b, on) -> { settings.setFallback(on); tint(sw); changed.run(); });
        fallback.addView(sw, lp(-2, -2, 8, 0, 0, 0));
        fallback.setOnClickListener(v -> sw.toggle());
        list.addView(fallback, lp(-1, -2, 0, 0, 0, 8));

        TextView hint = text("Нажмите на микрофон, говорите, нажмите ещё раз — текст появится в поле. Запись до 5 минут; длинная речь отправляется частями. Для облачных сервисов звук уходит на сервер выбранного провайдера.", 11, palette.muted, false);
        list.addView(hint, lp(-1, -2, 4, 8, 4, 0));
    }

    private View providerCard(final DictationProviders.Info info, boolean primary) {
        boolean ready = settings.configured(info.id);
        int tint = ServiceIcon.accentOf(info.icon);
        LinearLayout card = new LinearLayout(activity);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(6), dp(10));
        GradientDrawable wash = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(palette.card, tint, palette.dark ? .26f : .13f), blend(palette.card, tint, palette.dark ? .06f : .02f)});
        wash.setCornerRadius(dp(18));
        wash.setStroke(dp(primary ? 2 : 1), primary ? palette.green : blend(palette.border, tint, .25f));
        card.setBackground(wash);
        card.addView(ServiceIcon.view(activity, info.icon, info.title, 40), lp(40, 40, 0, 0, 12, 0));

        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(info.title, 15, palette.text, true);
        name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        words.addView(text(info.subtitle, 12, palette.muted, false), lp(-1, -2, 0, 2, 0, 0));
        String status;
        if (!info.needsKey && !info.endpoint) status = "Готов, ключ не нужен";
        else if (info.endpoint && settings.extra(info.id).isEmpty()) status = "Нужен адрес сервера";
        else if (info.needsKey && !ready) status = "Нужен ключ";
        else if ("connection".equals(settings.keySource(info.id))) status = "Ключ взят из подключения «" + (DictationProviders.GROQ.equals(info.id) ? "Groq API" : "OpenAI API") + "»";
        else status = info.needsKey ? "Ключ сохранён" : "Адрес задан";
        TextView state = text((ready ? "● " : "○ ") + status, 11, ready ? palette.green : palette.muted, true);
        words.addView(state, lp(-1, -2, 0, 4, 0, 0));
        card.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));

        if (primary) {
            TextView badge = text("основной", 11, palette.green, true);
            badge.setPadding(dp(9), dp(3), dp(9), dp(3));
            badge.setBackground(round(Color.argb(28, Color.red(palette.green), Color.green(palette.green), Color.blue(palette.green)), 10, 0, 0));
            card.addView(badge, lp(-2, -2, 6, 0, 0, 0));
        }
        if (info.needsKey || info.endpoint) {
            TextView gear = text("⚙", 20, palette.muted, false);
            gear.setGravity(Gravity.CENTER);
            gear.setOnClickListener(v -> configure(info));
            card.addView(gear, lp(44, 44, 2, 0, 0, 0));
        } else card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setOnClickListener(v -> {
            if (settings.configured(info.id)) { settings.setProvider(info.id); changed.run(); render(); }
            else configure(info);
        });
        return card;
    }

    private void chooseLanguage() {
        dialog.dismiss();
        final Runnable back = () -> show(activity, palette, settings, changed);
        ListSheet sheet = ListSheet.of(activity, palette).title("Язык диктовки", "Для Whisper, Яндекса, Google и системного распознавания");
        for (final String[] language : DictationProviders.LANGUAGES) {
            final String code = language[0];
            sheet.row("", language[1], "auto".equals(code) ? "Сервис сам определит язык (системное распознавание: русский + English)" : code,
                    code.equals(settings.language()) ? "выбран" : "", () -> { settings.setLanguage(code); changed.run(); back.run(); });
        }
        sheet.action("Назад", false, back);
        sheet.show();
    }

    // ---- provider configuration ----

    private void configure(final DictationProviders.Info info) {
        final Dialog box = new Dialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(palette.background, 28, 0, 0));
        root.setClipToOutline(true);

        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(16), dp(18), dp(6));
        head.addView(ServiceIcon.view(activity, info.icon, info.title, 44), lp(44, 44, 0, 0, 12, 0));
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(info.title, 18, palette.text, true));
        titles.addView(text(info.subtitle, 12, palette.muted, false), lp(-1, -2, 0, 2, 0, 0));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(head);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(6), dp(18), dp(8));
        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        form.addView(text(info.help, 12, palette.muted, false), lp(-1, -2, 0, 0, 0, 10));
        final EditText key = info.needsKey ? field(form, "API-ключ", keyHint(info), true, "") : null;
        final EditText extra = info.endpoint ? field(form, "Адрес сервера", "http://192.168.1.5:8000/v1", false, settings.extra(info.id))
                : info.folder ? field(form, "Каталог (folderId), необязательно", "b1g…", false, settings.extra(info.id)) : null;
        final EditText model = info.models.length == 0 ? null : field(form, "Модель", info.defaultModel, false, settings.model(info.id));
        if (model != null && info.models.length > 1) {
            LinearLayout chips = new LinearLayout(activity);
            chips.setOrientation(LinearLayout.VERTICAL);
            for (final String m : info.models) {
                TextView chip = text(m, 12, palette.text, false);
                chip.setPadding(dp(12), dp(7), dp(12), dp(7));
                chip.setBackground(round(palette.subtle, 12, palette.border, 1));
                chip.setOnClickListener(v -> model.setText(m));
                chips.addView(chip, lp(-2, -2, 0, 0, 0, 6));
            }
            form.addView(chips, lp(-1, -2, 0, 0, 0, 4));
        }
        final TextView status = text("", 12, palette.muted, false);
        form.addView(status, lp(-1, -2, 2, 6, 2, 0));

        final Runnable save = () -> {
            if (key != null && !key.getText().toString().trim().isEmpty()) settings.setKey(info.id, key.getText().toString());
            if (extra != null) settings.setExtra(info.id, extra.getText().toString());
            if (model != null) settings.setModel(info.id, model.getText().toString());
        };

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setPadding(dp(18), dp(8), dp(18), dp(16));
        buttons.setOrientation(LinearLayout.VERTICAL);
        LinearLayout first = new LinearLayout(activity);
        first.addView(button("Проверить", false, v -> {
            save.run();
            if (!settings.configured(info.id)) { status.setTextColor(palette.muted); status.setText("Сначала укажите " + (info.endpoint ? "адрес сервера" : "ключ")); return; }
            status.setTextColor(palette.muted); status.setText("Проверяем…");
            new Thread(() -> {
                String result; boolean ok;
                try { DictationClient.check(settings, info.id); ok = true; result = "✓ Сервис ответил — всё работает"; }
                catch (Exception e) { ok = false; result = "✕ " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
                final String shown = result; final boolean good = ok;
                activity.runOnUiThread(() -> { status.setTextColor(good ? palette.green : Color.rgb(200, 70, 60)); status.setText(shown); });
            }, "dictation-check").start();
        }), lp(0, 46, 1f, 0, 0, 0, 0));
        first.addView(button("Сохранить", true, v -> {
            save.run();
            if (!settings.configured(info.id)) { status.setTextColor(palette.muted); status.setText("Сначала укажите " + (info.endpoint ? "адрес сервера" : "ключ")); return; }
            if (DictationProviders.SYSTEM.equals(settings.provider())) settings.setProvider(info.id);
            changed.run();
            box.dismiss();
            render();
        }), lp(0, 46, 1f, 10, 0, 0, 0));
        buttons.addView(first, new LinearLayout.LayoutParams(-1, -2));
        if (!settings.ownKey(info.id).isEmpty() || info.endpoint) {
            buttons.addView(button(info.endpoint ? "Сбросить настройки" : "Удалить ключ", false, v -> {
                settings.clearKey(info.id);
                if (info.endpoint) { settings.setExtra(info.id, ""); settings.setModel(info.id, ""); }
                if (info.id.equals(settings.provider()) && !settings.configured(info.id)) settings.setProvider(DictationProviders.SYSTEM);
                changed.run();
                box.dismiss();
                render();
            }), lp(-1, 44, 0, 10, 0, 0));
        }
        root.addView(buttons);

        box.setContentView(root);
        box.setCanceledOnTouchOutside(true);
        Window w = box.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        box.show();
        if (w != null) {
            WindowManager.LayoutParams p = w.getAttributes();
            p.dimAmount = .38f; w.setAttributes(p); w.setGravity(Gravity.BOTTOM);
            w.setLayout(activity.getResources().getDisplayMetrics().widthPixels, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private String keyHint(DictationProviders.Info info) {
        String source = settings.keySource(info.id);
        if ("own".equals(source)) return "Ключ сохранён — пусто, чтобы не менять";
        if ("connection".equals(source)) return "Используется ключ из подключения — можно задать свой";
        return "Вставьте ключ";
    }

    private EditText field(LinearLayout form, String label, String hint, boolean secret, String value) {
        form.addView(text(label, 11, palette.muted, true), lp(-1, -2, 4, 6, 0, 4));
        EditText edit = new EditText(activity);
        edit.setSingleLine(true); edit.setHint(hint); edit.setTextSize(14);
        edit.setTextColor(palette.text); edit.setHintTextColor(palette.muted);
        edit.setPadding(dp(14), 0, dp(14), 0);
        edit.setBackground(round(palette.subtle, 14, palette.border, 1));
        edit.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setText(value);
        form.addView(edit, lp(-1, 46, 0, 0, 0, 6));
        return edit;
    }

    // ---- helpers ----

    private LinearLayout row(String unused) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        row.setBackground(round(palette.card, 16, palette.border, 1));
        return row;
    }

    private View body(String title, String subtitle) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(title, 15, palette.text, true));
        box.addView(text(subtitle, 12, palette.muted, false), lp(-1, -2, 0, 2, 0, 0));
        return box;
    }

    private void tint(Switch sw) {
        sw.setThumbTintList(ColorStateList.valueOf(sw.isChecked() ? palette.green : palette.muted));
        sw.setTrackTintList(ColorStateList.valueOf(Color.argb(90, Color.red(palette.green), Color.green(palette.green), Color.blue(palette.green))));
    }

    private TextView section(String title) { return text(title.toUpperCase(java.util.Locale.ROOT), 11, palette.muted, true); }

    private static int blend(int base, int accent, float amount) {
        return Color.rgb(Math.round(Color.red(base) + (Color.red(accent) - Color.red(base)) * amount),
                Math.round(Color.green(base) + (Color.green(accent) - Color.green(base)) * amount),
                Math.round(Color.blue(base) + (Color.blue(accent) - Color.blue(base)) * amount));
    }

    private TextView button(String label, boolean primary, View.OnClickListener click) {
        TextView b = text(label, 14, primary ? Color.WHITE : palette.text, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(primary ? round(palette.green, 16, 0, 0) : round(palette.subtle, 16, palette.border, 1));
        b.setOnClickListener(click);
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w > 0 ? dp(w) : w, h > 0 ? dp(h) : h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w > 0 ? dp(w) : w, h > 0 ? dp(h) : h, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private int dp(int v) { return (int) (v * activity.getResources().getDisplayMetrics().density + .5f); }
}
