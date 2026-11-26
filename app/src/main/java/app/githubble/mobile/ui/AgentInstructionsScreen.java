package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import app.githubble.mobile.agent.AgentInstructions;
import java.util.List;
import java.util.function.Consumer;

/**
 * Settings → «Инструкции агента»: tabs «Стандартный», «Расширенный», «Свои условия». Shows what the agent is told, lets the user
 * switch blocks off, rewrite them, add own situation rules and have the model write a precise rule from a plain description.
 */
public final class AgentInstructionsScreen {
    /** Writes a finished instruction from the meta-prompt with the selected model; callbacks run on the UI thread. */
    public interface Generator { void generate(String metaPrompt, Consumer<String> done, Consumer<String> failed); }

    private final Activity activity;
    private final Generator generator;
    private int bg = 0xFFF4F7F9, card = 0xFFFFFFFF, ink = 0xFF1B2733, muted = 0xFF6B7885, accent = 0xFF2E9D68, line = 0xFFD9E0E6;
    private int tab; // 0 standard, 1 extended, 2 own rules
    private LinearLayout list;
    private Dialog page;

    public AgentInstructionsScreen(Activity activity, Generator generator) { this.activity = activity; this.generator = generator; }

    public AgentInstructionsScreen palette(int bg, int card, int ink, int muted, int accent, int line) {
        this.bg = bg; this.card = card; this.ink = ink; this.muted = muted; this.accent = accent; this.line = line; return this;
    }

    private int dp(int v) { return (int) (v * activity.getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }
    private TextView text(String value, int sp, boolean bold, int color) {
        TextView v = new TextView(activity); v.setText(value); v.setTextSize(sp); v.setTextColor(color); if (bold) v.setTypeface(null, Typeface.BOLD); return v;
    }
    private void toast(String m) { android.widget.Toast.makeText(activity, m, android.widget.Toast.LENGTH_SHORT).show(); }

    public void show() {
        tab = AgentInstructions.extended() ? 1 : 0;
        page = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(bg);
        LinearLayout top = new LinearLayout(activity); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(4), dp(4), dp(10), dp(4));
        TextView back = text("←", 24, true, ink); back.setGravity(Gravity.CENTER); back.setContentDescription("Закрыть");
        back.setOnClickListener(v -> page.dismiss());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        top.addView(text("Инструкции агента", 19, true, ink), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(56)));
        final LinearLayout tabs = new LinearLayout(activity); tabs.setPadding(dp(12), dp(2), dp(12), dp(10));
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(activity); divider.setBackgroundColor(line); root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        ScrollView scroll = new ScrollView(activity);
        list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(14), dp(12), dp(14), dp(24));
        scroll.addView(list); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        final Runnable[] draw = {null};
        draw[0] = () -> { renderTabs(tabs, draw[0]); render(); };
        draw[0].run();
        page.setContentView(root);
        android.view.Window w = page.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(bg)); w.setStatusBarColor(bg); w.setNavigationBarColor(bg);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        page.show();
    }

    private void renderTabs(LinearLayout tabs, Runnable redraw) {
        tabs.removeAllViews();
        String[] names = {"Стандартный", "Расширенный", "Свои условия"};
        for (int i = 0; i < names.length; i++) {
            final int index = i; boolean on = tab == i;
            TextView t = text(names[i], 13, true, on ? 0xFFFFFFFF : ink); t.setGravity(Gravity.CENTER); t.setSingleLine();
            t.setBackground(shape(on ? accent : card, 18, on ? 0 : line));
            t.setOnClickListener(v -> { tab = index; redraw.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1); if (i > 0) lp.leftMargin = dp(8);
            tabs.addView(t, lp);
        }
    }

    private void render() {
        list.removeAllViews();
        if (tab == 2) renderRules(); else renderProfile(tab == 1);
    }

    private LinearLayout.LayoutParams gap() { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(10); return lp; }

    private void renderProfile(boolean extended) {
        boolean active = AgentInstructions.extended() == extended;
        LinearLayout head = new LinearLayout(activity); head.setOrientation(LinearLayout.VERTICAL); head.setPadding(dp(14), dp(12), dp(14), dp(12));
        head.setBackground(shape(card, 16, active ? accent : line));
        head.addView(text(extended ? "Расширенный профиль" : "Стандартный профиль", 16, true, ink));
        TextView about = text(extended
            ? "Всё из стандартного, плюс: план перед работой, проверка результата, уточняющие вопросы, минимальные правки, официальные источники и итоговый отчёт. Агент работает вдумчивее, но расходует больше токенов."
            : "То, с чем агент работал до этого раздела: самостоятельно читает репозитории, спрашивает только перед изменениями, честно проверяет результат.", 13, false, muted);
        about.setPadding(0, dp(4), 0, dp(8)); head.addView(about);
        TextView use = text(active ? "✓ Используется сейчас" : "Использовать этот профиль", 13, true, active ? accent : 0xFFFFFFFF);
        use.setGravity(Gravity.CENTER); use.setBackground(shape(active ? card : accent, 18, active ? accent : 0));
        use.setOnClickListener(v -> { AgentInstructions.setExtended(extended); toast(extended ? "Включён расширенный профиль" : "Включён стандартный профиль"); render(); });
        head.addView(use, new LinearLayout.LayoutParams(-1, dp(38)));
        list.addView(head, gap());

        LinearLayout direct = new LinearLayout(activity); direct.setGravity(Gravity.CENTER_VERTICAL); direct.setPadding(dp(14), dp(10), dp(10), dp(10));
        direct.setBackground(shape(card, 16, line));
        LinearLayout lab = new LinearLayout(activity); lab.setOrientation(LinearLayout.VERTICAL);
        lab.addView(text("Быстрые ответы без модели", 15, true, ink));
        TextView sub = text("Списки, счётчики и таблицы репозиториев по аккаунтам агент собирает прямо из GitHub, не тратя токены. Выключите — тогда на такие вопросы отвечает модель по вашим инструкциям.", 12, false, muted);
        sub.setPadding(0, dp(2), dp(8), 0); lab.addView(sub);
        direct.addView(lab, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(activity); sw.setChecked(AgentInstructions.directAnswers());
        sw.setOnCheckedChangeListener((b, on) -> AgentInstructions.setDirectAnswers(on));
        direct.addView(sw);
        list.addView(direct, gap());

        for (AgentInstructions.Block b : AgentInstructions.blocksOf(extended)) list.addView(blockCard(b), gap());
    }

    private View blockCard(AgentInstructions.Block b) {
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(10), dp(10), dp(10));
        box.setBackground(shape(card, 16, line));
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(activity); labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text((b.locked ? "🔒 " : "") + b.title + (b.extended ? "  · расширенный" : ""), 15, true, ink));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (!b.locked) {
            Switch sw = new Switch(activity); sw.setChecked(AgentInstructions.blockEnabled(b));
            sw.setOnCheckedChangeListener((x, on) -> { AgentInstructions.setBlockEnabled(b.id, on); render(); });
            row.addView(sw);
        }
        box.addView(row);
        boolean on = AgentInstructions.blockEnabled(b);
        TextView s = text(b.summary + (AgentInstructions.blockChanged(b) ? "\nИзменено вами." : ""), 12, false, on ? muted : 0xFFA5AFB8);
        s.setPadding(0, dp(3), dp(8), 0); box.addView(s);
        if (!b.locked) {
            LinearLayout actions = new LinearLayout(activity); actions.setPadding(0, dp(8), 0, 0);
            TextView edit = text("Изменить текст", 13, true, accent); edit.setPadding(0, dp(4), dp(18), dp(4));
            edit.setOnClickListener(v -> editBlock(b)); actions.addView(edit);
            if (AgentInstructions.blockChanged(b)) {
                TextView reset = text("Вернуть исходный", 13, true, muted); reset.setPadding(0, dp(4), 0, dp(4));
                reset.setOnClickListener(v -> { AgentInstructions.setBlockText(b.id, null); render(); }); actions.addView(reset);
            }
            box.addView(actions);
        } else {
            TextView lock = text("Защищает ваши данные и доступы, поэтому не отключается и не редактируется.", 11, false, muted);
            lock.setPadding(0, dp(4), 0, 0); box.addView(lock);
        }
        return box;
    }

    private EditText field(String hint, String value, int minLines) {
        EditText e = new EditText(activity); e.setHint(hint); e.setText(value); e.setTextColor(ink); e.setHintTextColor(muted); e.setTextSize(14);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setMinLines(minLines); e.setGravity(Gravity.TOP | Gravity.START); e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(shape(bg, 12, line)); return e;
    }

    private void editBlock(AgentInstructions.Block b) {
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), 0);
        EditText e = field("Инструкция", AgentInstructions.blockText(b).trim(), 8);
        form.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new SheetDialog.Builder(activity).setTitle(b.title)
            .setMessage("Пишите прямо — агент получит этот текст как есть. Пустое поле вернёт исходный текст.")
            .setView(form).setPositiveButton("Сохранить", (d, w) -> { AgentInstructions.setBlockText(b.id, e.getText().toString()); render(); })
            .setNegativeButton("Отмена", null).show();
    }

    private void renderRules() {
        LinearLayout head = new LinearLayout(activity); head.setOrientation(LinearLayout.VERTICAL); head.setPadding(dp(14), dp(12), dp(14), dp(12));
        head.setBackground(shape(card, 16, line));
        head.addView(text("Свои условия", 16, true, ink));
        TextView about = text("Опишите ситуацию своими словами — модель составит из неё подробную готовую инструкцию, которую агент будет выполнять каждый раз, когда ситуация повторится.", 13, false, muted);
        about.setPadding(0, dp(4), 0, dp(10)); head.addView(about);
        LinearLayout buttons = new LinearLayout(activity);
        TextView gen = text("✨  Описать ситуацию", 13, true, 0xFFFFFFFF); gen.setGravity(Gravity.CENTER); gen.setBackground(shape(accent, 18, 0));
        gen.setOnClickListener(v -> generate());
        buttons.addView(gen, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView manual = text("+ Вручную", 13, true, ink); manual.setGravity(Gravity.CENTER); manual.setBackground(shape(card, 18, line));
        manual.setOnClickListener(v -> editRule(null, "", "", ""));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, dp(38), 1); ml.leftMargin = dp(8);
        buttons.addView(manual, ml);
        head.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        list.addView(head, gap());
        List<AgentInstructions.Rule> rules = AgentInstructions.rules();
        if (rules.isEmpty()) {
            TextView empty = text("Условий пока нет. Например: «когда я прошу сделать красивее — сначала посмотри дизайн-систему проекта, потом меняй только стили».", 13, false, muted);
            empty.setPadding(dp(8), dp(12), dp(8), 0); list.addView(empty);
            return;
        }
        for (AgentInstructions.Rule r : rules) list.addView(ruleCard(r), gap());
    }

    private View ruleCard(AgentInstructions.Rule r) {
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(10), dp(10), dp(10));
        box.setBackground(shape(card, 16, line));
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(r.title.isEmpty() ? "Условие" : r.title, 15, true, ink); title.setSingleLine(); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(activity); sw.setChecked(r.enabled);
        sw.setOnCheckedChangeListener((x, on) -> { AgentInstructions.saveRule(new AgentInstructions.Rule(r.id, r.title, r.situation, r.text, on)); render(); });
        row.addView(sw); box.addView(row);
        if (!r.situation.trim().isEmpty()) { TextView s = text("Когда: " + r.situation.trim(), 12, false, muted); s.setMaxLines(2); s.setEllipsize(android.text.TextUtils.TruncateAt.END); s.setPadding(0, dp(3), dp(8), 0); box.addView(s); }
        TextView preview = text(r.text.trim(), 12, false, r.enabled ? ink : 0xFFA5AFB8); preview.setMaxLines(4); preview.setEllipsize(android.text.TextUtils.TruncateAt.END); preview.setPadding(0, dp(4), dp(8), 0);
        box.addView(preview);
        LinearLayout actions = new LinearLayout(activity); actions.setPadding(0, dp(8), 0, 0);
        TextView edit = text("Изменить", 13, true, accent); edit.setPadding(0, dp(4), dp(18), dp(4));
        edit.setOnClickListener(v -> editRule(r, r.title, r.situation, r.text)); actions.addView(edit);
        TextView del = text("Удалить", 13, true, 0xFFC75B66); del.setPadding(0, dp(4), 0, dp(4));
        del.setOnClickListener(v -> new SheetDialog.Builder(activity).setTitle("Удалить условие?").setMessage(r.title)
            .setPositiveButton("Удалить", (d, w) -> { AgentInstructions.deleteRule(r.id); render(); }).setNegativeButton("Отмена", null).show());
        actions.addView(del); box.addView(actions);
        return box;
    }

    private void editRule(AgentInstructions.Rule existing, String title, String situation, String body) {
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), 0);
        EditText t = field("Название", title, 1), s = field("Когда это нужно (ситуация)", situation, 2), x = field("Что должен делать агент", body, 7);
        form.addView(t, gap()); form.addView(s, gap()); form.addView(x, new LinearLayout.LayoutParams(-1, -2));
        SheetDialog dialog = new SheetDialog.Builder(activity).setTitle(existing == null ? "Новое условие" : "Условие")
            .setView(form).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).show();
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {
            String text = x.getText().toString().trim();
            if (text.isEmpty()) { x.setError("Опишите, что делать"); return; }
            String name = t.getText().toString().trim();
            if (name.isEmpty()) name = AgentInstructions.titleFrom(s.getText().toString().trim().isEmpty() ? text : s.getText().toString());
            AgentInstructions.saveRule(new AgentInstructions.Rule(existing == null ? java.util.UUID.randomUUID().toString() : existing.id,
                name, s.getText().toString().trim(), text, existing == null || existing.enabled));
            dialog.dismiss(); tab = 2; render();
        }));
    }

    /** The user describes a situation in plain words; the model turns it into a complete professional instruction. */
    private void generate() {
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), 0, dp(20), 0);
        EditText about = field("Например: когда я прошу сделать красивее — что именно менять и чего не трогать", "", 3);
        form.addView(about, gap());
        TextView make = text("Сгенерировать инструкцию", 13, true, 0xFFFFFFFF); make.setGravity(Gravity.CENTER); make.setBackground(shape(accent, 18, 0));
        form.addView(make, new LinearLayout.LayoutParams(-1, dp(38)));
        TextView state = text("", 12, false, muted); state.setPadding(0, dp(8), 0, dp(4)); form.addView(state);
        EditText result = field("Готовая инструкция появится здесь — её можно поправить", "", 8); result.setVisibility(View.GONE);
        form.addView(result, new LinearLayout.LayoutParams(-1, -2));
        SheetDialog dialog = new SheetDialog.Builder(activity).setTitle("Инструкция по описанию")
            .setMessage("Пишите как удобно, хоть коротко и общо: модель уточнит, расставит шаги, проверки и запреты.")
            .setView(form).setPositiveButton("Сохранить как условие", null).setNegativeButton("Закрыть", null).show();
        make.setOnClickListener(v -> {
            String d = about.getText().toString().trim();
            if (d.length() < 4) { about.setError("Опишите ситуацию"); return; }
            make.setEnabled(false); state.setText("Модель пишет инструкцию…");
            generator.generate(AgentInstructions.builderPrompt(d), out -> {
                make.setEnabled(true); make.setText("Сгенерировать заново");
                String clean = out == null ? "" : out.trim();
                if (clean.startsWith("```")) clean = clean.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
                if (clean.isEmpty()) { state.setText("Модель вернула пустой ответ. Попробуйте ещё раз."); return; }
                result.setText(clean); result.setVisibility(View.VISIBLE); state.setText("Готово. Проверьте текст и сохраните.");
            }, err -> { make.setEnabled(true); state.setText(err == null || err.isEmpty() ? "Не удалось получить ответ модели" : err); });
        });
        dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {
            String body = result.getText().toString().trim();
            if (body.isEmpty()) { toast("Сначала сгенерируйте инструкцию"); return; }
            String situation = about.getText().toString().trim();
            AgentInstructions.saveRule(new AgentInstructions.Rule(java.util.UUID.randomUUID().toString(), AgentInstructions.titleFrom(situation), situation, body, true));
            dialog.dismiss(); tab = 2; render(); toast("Условие сохранено");
        }));
    }
}
