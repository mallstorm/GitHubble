package app.githubble.mobile.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.githubble.mobile.R;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Locale;

/**
 * Card used by search results and repository feeds: language-coloured gradient, owner and name,
 * a description that can be translated in place, topics and a footer with language, stars, forks and age.
 */
public final class RepositoryFeedCard {
    private RepositoryFeedCard() {}

    /** Colours are passed in so the card follows the active theme. */
    public static final class Palette {
        public final int card, border, text, muted, link, accent;
        public Palette(int card, int border, int text, int muted, int link, int accent) {
            this.card = card; this.border = border; this.text = text; this.muted = muted; this.link = link; this.accent = accent;
        }
    }

    /** @param translation collects the description for batch translation; null leaves it as is. */
    public static LinearLayout build(Activity a, JSONObject repo, int smartMatches, Palette p, InlineTranslation translation) {
        float d = a.getResources().getDisplayMetrics().density;
        String full = repo.optString("full_name", "");
        int slash = full.indexOf('/');
        String owner = slash > 0 ? full.substring(0, slash) : "";
        String name = slash > 0 ? full.substring(slash + 1) : full;
        String language = JsonText.of(repo, "language", "");
        if ("null".equals(language)) language = "";
        int tint = LanguageColors.of(language, p.accent);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(d, 14), px(d, 13), px(d, 14), px(d, 12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{LanguageColors.mix(p.card, tint, .16f), LanguageColors.mix(p.card, tint, .04f), p.card});
        bg.setCornerRadius(px(d, 18));
        bg.setStroke(px(d, 1), LanguageColors.mix(p.border, tint, .30f));
        box.setBackground(bg);

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.ic_repository);
        icon.setColorFilter(Color.WHITE);
        int pad = px(d, 9);
        icon.setPadding(pad, pad, pad, pad);
        GradientDrawable badge = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{LanguageColors.mix(tint, Color.WHITE, .12f), LanguageColors.mix(tint, Color.BLACK, .28f)});
        badge.setCornerRadius(px(d, 12));
        icon.setBackground(badge);
        top.addView(icon, new LinearLayout.LayoutParams(px(d, 40), px(d, 40)));

        LinearLayout names = new LinearLayout(a);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(px(d, 11), 0, 0, 0);
        if (!owner.isEmpty()) names.addView(line(a, owner, 11, false, p.muted));
        names.addView(line(a, name, 16, true, p.link));
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(top);

        String source = JsonText.of(repo, "description", "");
        if ("null".equals(source)) source = "";
        TextView desc = new TextView(a);
        desc.setTextSize(13);
        desc.setLineSpacing(0, 1.08f);
        desc.setTextColor(source.isEmpty() ? p.muted : p.text);
        desc.setText(source.isEmpty() ? "Без описания" : source);
        desc.setMaxLines(4);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        desc.setPadding(0, px(d, 9), 0, 0);
        box.addView(desc);
        if (translation != null && !source.isEmpty()) translation.add(desc, source);

        JSONArray topics = repo.optJSONArray("topics");
        if (topics != null && topics.length() > 0) {
            LinearLayout row = new LinearLayout(a);
            row.setPadding(0, px(d, 9), 0, 0);
            int shown = 0;
            for (int i = 0; i < topics.length() && shown < 3; i++) {
                String t = topics.optString(i, "");
                if (t.isEmpty() || t.length() > 18) continue;
                row.addView(chip(a, d, t, p.muted, LanguageColors.mix(p.card, tint, .12f)), chipParams(d));
                shown++;
            }
            if (shown > 0) box.addView(row);
        }

        LinearLayout foot = new LinearLayout(a);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, px(d, 10), 0, 0);
        if (!language.isEmpty()) {
            LinearLayout lang = new LinearLayout(a);
            lang.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(a);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(tint);
            dot.setBackground(dotBg);
            lang.addView(dot, new LinearLayout.LayoutParams(px(d, 9), px(d, 9)));
            TextView l = line(a, " " + language, 12, true, p.text);
            lang.addView(l);
            foot.addView(lang, new LinearLayout.LayoutParams(-2, -2));
            foot.addView(space(a, d));
        }
        foot.addView(line(a, "★ " + compact(repo.optInt("stargazers_count", 0)), 12, true, p.muted));
        foot.addView(space(a, d));
        foot.addView(line(a, "⑂ " + compact(repo.optInt("forks_count", 0)), 12, false, p.muted));
        String age = age(repo.optString("pushed_at", repo.optString("updated_at", "")));
        if (!age.isEmpty()) {
            foot.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
            foot.addView(line(a, age, 11, false, p.muted));
        }
        box.addView(foot);

        if (smartMatches > 1) {
            TextView hint = chip(a, d, "RU ✦ ×" + smartMatches, p.muted, LanguageColors.mix(p.card, tint, .12f));
            LinearLayout.LayoutParams lp = chipParams(d);
            lp.setMargins(0, px(d, 8), 0, 0);
            box.addView(hint, lp);
        }
        return box;
    }

    private static int px(float d, int dp) { return (int) (dp * d + .5f); }

    private static TextView line(Activity a, String value, int sp, boolean bold, int color) {
        TextView v = new TextView(a);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }

    private static View space(Activity a, float d) {
        View s = new View(a);
        s.setLayoutParams(new LinearLayout.LayoutParams(px(d, 12), 1));
        return s;
    }

    private static TextView chip(Activity a, float d, String value, int color, int fill) {
        TextView c = line(a, value, 11, false, color);
        c.setGravity(Gravity.CENTER);
        c.setPadding(px(d, 9), px(d, 4), px(d, 9), px(d, 4));
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(d, 12));
        c.setBackground(g);
        return c;
    }

    private static LinearLayout.LayoutParams chipParams(float d) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, px(d, 6), 0);
        return lp;
    }

    static String compact(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 10_000) return (n / 1000) + "k";
        if (n >= 1000) return String.format(Locale.US, "%.1fk", n / 1000f);
        return Integer.toString(n);
    }

    static String age(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            long mins = (System.currentTimeMillis() - Instant.parse(iso).toEpochMilli()) / 60000;
            if (mins < 60) return "сейчас";
            if (mins < 1440) return (mins / 60) + " ч назад";
            if (mins < 43200) return (mins / 1440) + " д назад";
            if (mins < 525600) return (mins / 43200) + " мес назад";
            return (mins / 525600) + " г назад";
        } catch (Exception e) { return ""; }
    }
}
