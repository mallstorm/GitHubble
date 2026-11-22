package app.githubble.mobile.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.githubble.mobile.controller.TranslationController;
import app.githubble.mobile.translation.LanguageDetector;
import app.githubble.mobile.translation.TranslationCache;
import app.githubble.mobile.translation.TranslationEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * One small chip that translates a group of short texts (a description, the commit list, titles) in one request.
 * Foreign texts only; code is never in these strings. Without a chip (parent == null) it only translates silently.
 */
public final class InlineTranslation {
    public interface Host {
        /** Whether this group may be translated without a tap. */
        boolean auto();
        /** Private content needs a one-time confirmation; call proceed when it is given or not needed. */
        void allowSend(Runnable proceed);
        void toast(String message);
    }

    private final Activity activity;
    private final ExecutorService io;
    private final TranslationController translator;
    private final Host host;
    private final int muted, subtle;
    private final List<TextView> views = new ArrayList<>();
    private final List<String> sources = new ArrayList<>();
    private final List<String> translated = new ArrayList<>();
    private TextView chip;
    private boolean showing, working;

    public InlineTranslation(Activity activity, ExecutorService io, TranslationController translator, Host host, int muted, int subtle) {
        this.activity = activity; this.io = io; this.translator = translator; this.host = host; this.muted = muted; this.subtle = subtle;
    }

    /** Registers a text view whose current text is {@code source}. */
    public void add(TextView view, String source) {
        views.add(view); sources.add(source == null ? "" : source);
        TranslationCache.Entry cached = candidate(source) ? translator.cache().get(source, "", "ru", TranslationEngine.IDENTITY) : null;
        translated.add(cached == null ? null : cached.translated);
    }

    private static boolean candidate(String text) { return text != null && text.trim().length() >= 6 && LanguageDetector.needsRussianTranslation(text); }

    private boolean any() { for (String s : sources) if (candidate(s)) return true; return false; }

    private boolean allCached() { for (int i = 0; i < sources.size(); i++) if (candidate(sources.get(i)) && translated.get(i) == null) return false; return true; }

    /** Adds the chip to {@code parent} (null = no chip) and starts automatic translation when allowed. */
    public void bind(LinearLayout parent) {
        if (!any()) return;
        if (parent != null) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.END);
            chip = chip(activity, "", muted, subtle);
            chip.setOnClickListener(v -> toggle());
            row.addView(chip, new LinearLayout.LayoutParams(-2, -2));
            parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        boolean auto = host.auto();
        if (auto && allCached()) { showing = true; apply(); }
        else if (auto) run();
        label();
    }

    private void toggle() {
        if (working) return;
        if (allCached()) { showing = !showing; apply(); label(); }
        else host.allowSend(this::run);
    }

    private void run() {
        if (working) return;
        if (!translator.available()) { host.toast("Подключите ChatGPT, Grok или другую модель в «Подключениях»"); return; }
        working = true; label();
        final List<Integer> need = new ArrayList<>();
        final List<String> input = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) if (candidate(sources.get(i)) && translated.get(i) == null) { need.add(i); input.add(sources.get(i)); }
        io.execute(() -> {
            String failure = null;
            try {
                List<String> out = translator.engine().translateBatch(input, "ru", translator.completer());
                for (int k = 0; k < need.size(); k++) {
                    String value = out.get(k);
                    if (value != null && !value.trim().isEmpty()) {
                        translator.cache().put(input.get(k), "", "ru", TranslationEngine.IDENTITY, value, "");
                    }
                }
            } catch (Exception e) { failure = e.getMessage() == null ? "ошибка сети" : e.getMessage(); }
            final String error = failure;
            activity.runOnUiThread(() -> {
                working = false;
                for (int i = 0; i < sources.size(); i++) if (candidate(sources.get(i)) && translated.get(i) == null) {
                    TranslationCache.Entry e = translator.cache().get(sources.get(i), "", "ru", TranslationEngine.IDENTITY);
                    if (e != null) translated.set(i, e.translated);
                }
                if (error != null && !allCached()) { host.toast("Не удалось перевести: " + error); showing = false; if (chip != null) chip.setText("🌐 Не удалось · повторить"); return; }
                showing = true; apply(); label();
            });
        });
    }

    private void apply() {
        for (int i = 0; i < views.size(); i++) {
            String t = translated.get(i);
            views.get(i).setText(showing && t != null ? t : sources.get(i));
        }
    }

    private void label() {
        if (chip == null) return;
        chip.setText(working ? "🌐 Перевожу…" : showing ? "🌐 Перевод · оригинал" : allCached() ? "🌐 Оригинал · перевод" : "🌐 Перевести");
    }

    /** The small unobtrusive chip used everywhere translation is offered. */
    public static TextView chip(Activity activity, String label, int muted, int subtle) {
        TextView chip = new TextView(activity);
        chip.setText(label);
        chip.setTextSize(11);
        chip.setTextColor(muted);
        float d = activity.getResources().getDisplayMetrics().density;
        chip.setPadding((int) (9 * d), (int) (4 * d), (int) (9 * d), (int) (4 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(subtle); bg.setCornerRadius(12 * d); bg.setStroke(0, Color.TRANSPARENT);
        chip.setBackground(bg);
        chip.setMinHeight((int) (28 * d));
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine();
        chip.setContentDescription("Перевод");
        return chip;
    }
}
