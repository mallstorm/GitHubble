package app.githubble.mobile.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.githubble.mobile.R;

/**
 * Settings in one visual language.
 *
 * The root page lists a few categories; each category opens one screen that
 * holds everything related to it. The search field on the root page finds any
 * option inside any category, so nothing has to be looked for by guessing.
 * Rows share one look: grouped cards, a tinted icon tile, a title, the current
 * value and either a chevron or a switch.
 */
public final class SettingsScreen {

    /** Live value of a switch row. */
    public interface Toggle {
        boolean get();
        void set(boolean on);
    }

    /** Called whenever the search query changes, so the caller can keep it across re-renders. */
    public interface QueryListener {
        void onQueryChanged(String query);
    }

    public static final class Item {
        final int icon;
        final String title;
        final String subtitle;
        final String keywords;
        final Runnable action;
        final Toggle toggle;
        final boolean danger;
        String badge = "";
        Runnable longAction;

        private Item(int icon, String title, String subtitle, String keywords,
                     Runnable action, Toggle toggle, boolean danger) {
            this.icon = icon;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.keywords = keywords == null ? "" : keywords;
            this.action = action;
            this.toggle = toggle;
            this.danger = danger;
        }

        /** A row that opens a screen or a dialog. */
        public static Item action(int icon, String title, String subtitle, String keywords, Runnable action) {
            return new Item(icon, title, subtitle, keywords, action, null, false);
        }

        /** A row with a switch; tapping anywhere on the row flips it. */
        public static Item toggle(int icon, String title, String subtitle, String keywords, Toggle toggle) {
            return new Item(icon, title, subtitle, keywords, null, toggle, false);
        }

        /** A read-only row that shows a value. */
        public static Item info(int icon, String title, String value, String keywords) {
            return new Item(icon, title, value, keywords, null, null, false);
        }

        /** Adds a small status pill at the end of the row, for example «Активен». */
        public Item badge(String text) {
            this.badge = text == null ? "" : text;
            return this;
        }

        /** Action on a long press, for example removing a saved account. */
        public Item onLongPress(Runnable action) {
            this.longAction = action;
            return this;
        }

        /** A destructive action such as signing out. */
        public static Item danger(int icon, String title, String subtitle, String keywords, Runnable action) {
            return new Item(icon, title, subtitle, keywords, action, null, true);
        }
    }

    private static final class Note {
        final String text;
        Note(String text) { this.text = text == null ? "" : text; }
    }

    /** A titled block of a page: rows, custom cards and short notes, in the order added. */
    public static final class Section {
        final String title;
        final List<Object> entries = new ArrayList<>();

        public Section(String title) {
            this.title = title == null ? "" : title;
        }

        public Section add(Item item) {
            if (item != null) entries.add(item);
            return this;
        }

        /** A custom card (for example live quota figures); shown on the page, not in search. */
        public Section add(View view) {
            if (view != null) entries.add(view);
            return this;
        }

        public Section note(String text) {
            if (text != null && !text.isEmpty()) entries.add(new Note(text));
            return this;
        }
    }

    /** One entry of the root page; its sections are what the search looks through. */
    public static final class Category {
        final int icon;
        final String title;
        final String subtitle;
        final int accent;
        final Runnable open;
        final List<Section> sections;

        public Category(int icon, String title, String subtitle, int accent, Runnable open,
                        List<Section> sections) {
            this.icon = icon;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.accent = accent;
            this.open = open;
            this.sections = sections == null ? Collections.emptyList() : sections;
        }
    }

    /** Summary card at the top of a page. */
    public static final class Header {
        final View avatar;
        final String title;
        final String subtitle;
        final Runnable action;
        TextView subtitleView;

        public Header(View avatar, String title, String subtitle, Runnable action) {
            this.avatar = avatar;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.action = action;
        }

        /** Updates the subtitle after asynchronous data arrives. */
        public void setSubtitle(String value) {
            if (subtitleView != null) subtitleView.setText(value == null ? "" : value);
        }
    }

    private final Activity activity;
    private final LinearLayout content;
    private final HomeScreen.Palette palette;
    private final int danger;

    public SettingsScreen(Activity activity, LinearLayout content, HomeScreen.Palette palette) {
        this.activity = activity;
        this.content = content;
        this.palette = palette;
        this.danger = palette.dark ? Color.rgb(255, 128, 120) : Color.rgb(192, 57, 57);
    }

    // ---------------------------------------------------------------- root page

    public void renderRoot(Header header, List<Category> categories, String initialQuery,
                           QueryListener listener) {
        final List<Category> all = categories == null ? Collections.emptyList() : categories;
        if (header != null) renderHeader(header);
        // An invisible focus holder keeps the keyboard closed until the search field is tapped.
        View focusHolder = new View(activity);
        focusHolder.setFocusable(true);
        focusHolder.setFocusableInTouchMode(true);
        content.addView(focusHolder, new LinearLayout.LayoutParams(0, 0));
        focusHolder.requestFocus();
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        final String[] query = {initialQuery == null ? "" : initialQuery};
        renderSearch(query[0], value -> {
            query[0] = value;
            if (listener != null) listener.onQueryChanged(value);
            renderRootList(list, all, value);
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, -2));
        renderRootList(list, all, query[0]);
    }

    private void renderRootList(LinearLayout list, List<Category> categories, String query) {
        list.removeAllViews();
        String[] tokens = tokens(query);
        if (tokens.length == 0) {
            LinearLayout group = group();
            for (int i = 0; i < categories.size(); i++) {
                if (i > 0) group.addView(divider(70));
                group.addView(categoryRow(categories.get(i)));
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, dp(8), 0, 0);
            list.addView(group, params);
            return;
        }
        int shown = 0;
        for (Category category : categories) {
            for (Section section : category.sections) {
                List<Item> visible = new ArrayList<>();
                for (Object entry : section.entries) {
                    if (entry instanceof Item && matches(category.title + " " + section.title, (Item) entry, tokens))
                        visible.add((Item) entry);
                }
                if (visible.isEmpty()) continue;
                shown += visible.size();
                list.addView(heading(section.title.isEmpty() ? category.title
                        : category.title + " · " + section.title));
                LinearLayout group = group();
                for (int i = 0; i < visible.size(); i++) {
                    if (i > 0) group.addView(divider(64));
                    group.addView(row(visible.get(i), category.accent));
                }
                list.addView(group, new LinearLayout.LayoutParams(-1, -2));
            }
        }
        if (shown == 0) {
            TextView empty = label("Ничего не найдено. Попробуйте другое слово: «тема», «кэш», «квоты», «модель».",
                    13, palette.muted, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(28), dp(12), dp(28));
            list.addView(empty);
        }
    }

    private View categoryRow(Category category) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        ImageView icon = iconTile(category.icon, category.accent, 44, 10);
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(6), 0);
        TextView title = label(category.title, 16, palette.text, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        if (!category.subtitle.isEmpty()) {
            TextView subtitle = label(category.subtitle, 12, palette.muted, false);
            subtitle.setPadding(0, dp(3), 0, 0);
            subtitle.setMaxLines(2);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(subtitle);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(chevron(), new LinearLayout.LayoutParams(dp(22), dp(44)));
        row.setContentDescription(category.title + ". " + category.subtitle);
        if (category.open != null) row.setOnClickListener(v -> category.open.run());
        applyRipple(row);
        return row;
    }

    // ---------------------------------------------------------------- category page

    public void renderPage(Header header, int accent, List<Section> sections) {
        if (header != null) renderHeader(header);
        if (sections == null) return;
        boolean first = header == null;
        for (Section section : sections) {
            if (!section.title.isEmpty()) {
                TextView heading = heading(section.title);
                if (first) heading.setPadding(dp(6), dp(4), dp(6), dp(7));
                content.addView(heading);
            }
            first = false;
            LinearLayout group = null;
            for (Object entry : section.entries) {
                if (entry instanceof Item) {
                    if (group == null) {
                        group = group();
                        content.addView(group, new LinearLayout.LayoutParams(-1, -2));
                    } else {
                        group.addView(divider(64));
                    }
                    group.addView(row((Item) entry, accent));
                } else if (entry instanceof View) {
                    group = null;
                    View view = (View) entry;
                    if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                    params.setMargins(0, 0, 0, dp(8));
                    content.addView(view, params);
                } else if (entry instanceof Note) {
                    group = null;
                    TextView note = label(((Note) entry).text, 12, palette.muted, false);
                    note.setPadding(dp(6), dp(8), dp(6), dp(2));
                    content.addView(note);
                }
            }
        }
    }

    // ---------------------------------------------------------------- parts

    private void renderHeader(Header header) {
        LinearLayout card = new LinearLayout(activity);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(10), dp(12));
        card.setBackground(round(blend(palette.card, palette.green, palette.dark ? 0.12f : 0.06f),
                18, palette.border, 1));
        if (header.avatar != null) {
            if (header.avatar.getParent() instanceof ViewGroup)
                ((ViewGroup) header.avatar.getParent()).removeView(header.avatar);
            card.addView(header.avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(4), 0);
        TextView title = label(header.title, 17, palette.text, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        TextView subtitle = label(header.subtitle, 12, palette.muted, false);
        subtitle.setMaxLines(3);
        subtitle.setPadding(0, dp(2), 0, 0);
        labels.addView(subtitle);
        header.subtitleView = subtitle;
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (header.action != null) {
            card.addView(chevron(), new LinearLayout.LayoutParams(dp(24), dp(40)));
            card.setOnClickListener(v -> header.action.run());
            applyRipple(card);
        }
        card.setContentDescription(header.title + ". " + header.subtitle);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(4), 0, dp(10));
        content.addView(card, params);
    }

    private interface Changed { void on(String value); }

    private void renderSearch(String initial, Changed changed) {
        LinearLayout box = new LinearLayout(activity);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), 0, dp(4), 0);
        box.setBackground(round(palette.card, 14, palette.border, 1));
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_search);
        icon.setColorFilter(palette.muted);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        box.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setHint("Найти настройку");
        field.setText(initial);
        field.setTextSize(15);
        field.setTextColor(palette.text);
        field.setHintTextColor(palette.muted);
        field.setBackground(null);
        field.setPadding(dp(10), 0, dp(6), 0);
        field.setContentDescription("Найти настройку");
        box.addView(field, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView clear = label("×", 22, palette.muted, false);
        clear.setGravity(Gravity.CENTER);
        clear.setContentDescription("Очистить поиск");
        clear.setVisibility(initial.isEmpty() ? View.GONE : View.VISIBLE);
        clear.setOnClickListener(v -> field.setText(""));
        box.addView(clear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String value = s == null ? "" : s.toString();
                clear.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
                changed.on(value);
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.setMargins(0, 0, 0, dp(4));
        content.addView(box, params);
    }

    private View row(Item item, int sectionAccent) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(14), dp(10), dp(10), dp(10));

        int accent = item.danger ? danger : sectionAccent;
        row.addView(iconTile(item.icon, accent, 38, 8), new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(6), 0);
        TextView title = label(item.title, 15, item.danger ? danger : palette.text, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        if (!item.subtitle.isEmpty()) {
            TextView subtitle = label(item.subtitle, 12, palette.muted, false);
            subtitle.setPadding(0, dp(2), 0, 0);
            subtitle.setMaxLines(3);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(subtitle);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        String description = item.title + (item.subtitle.isEmpty() ? "" : ". " + item.subtitle);
        if (item.toggle != null) {
            Switch toggle = new Switch(activity);
            toggle.setChecked(item.toggle.get());
            tint(toggle, palette.green);
            toggle.setContentDescription(item.title);
            toggle.setOnCheckedChangeListener((view, on) -> item.toggle.set(on));
            row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(48)));
            row.setOnClickListener(v -> toggle.toggle());
            applyRipple(row);
        } else {
            if (!item.badge.isEmpty()) {
                row.addView(badge(item.badge), new LinearLayout.LayoutParams(-2, -2));
                description += ". " + item.badge;
            }
            if (item.action != null) {
                if (item.badge.isEmpty())
                    row.addView(chevron(), new LinearLayout.LayoutParams(dp(22), dp(40)));
                row.setOnClickListener(v -> item.action.run());
                applyRipple(row);
            }
            row.setContentDescription(description);
        }
        if (item.longAction != null) {
            row.setOnLongClickListener(v -> { item.longAction.run(); return true; });
            if (item.action == null && item.toggle == null) applyRipple(row);
        }
        return row;
    }

    private TextView badge(String text) {
        TextView pill = label(text, 12, palette.green, true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(10), dp(4), dp(10), dp(4));
        pill.setBackground(round(blend(palette.card, palette.green, palette.dark ? 0.26f : 0.14f), 12, 0, 0));
        pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return pill;
    }

    private ImageView iconTile(int iconRes, int accent, int sizeDp, int paddingDp) {
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(accent);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        icon.setBackground(round(blend(palette.card, accent, palette.dark ? 0.24f : 0.13f),
                sizeDp >= 44 ? 12 : 11, 0, 0));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private LinearLayout group() {
        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(round(palette.card, 16, palette.border, 1));
        group.setClipToOutline(true);
        return group;
    }

    private TextView heading(String text) {
        TextView heading = label(text.toUpperCase(new Locale("ru")), 12, palette.muted, true);
        heading.setLetterSpacing(0.06f);
        heading.setPadding(dp(6), dp(16), dp(6), dp(7));
        return heading;
    }

    private View divider(int startDp) {
        View line = new View(activity);
        line.setBackgroundColor(palette.border);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        params.setMargins(dp(startDp), 0, 0, 0);
        line.setLayoutParams(params);
        return line;
    }

    private TextView chevron() {
        TextView arrow = label("›", 24, palette.muted, false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return arrow;
    }

    private static boolean matches(String context, Item item, String[] tokens) {
        String haystack = (context + " " + item.title + " " + item.subtitle + " " + item.keywords)
                .toLowerCase(new Locale("ru")).replace('ё', 'е');
        for (String token : tokens) if (!haystack.contains(token)) return false;
        return true;
    }

    private static String[] tokens(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(new Locale("ru")).replace('ё', 'е');
        if (normalized.isEmpty()) return new String[0];
        return normalized.split("\\s+");
    }

    private void tint(Switch toggle, int accent) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        int offThumb = palette.dark ? Color.rgb(190, 200, 196) : Color.WHITE;
        int offTrack = blend(palette.card, palette.muted, palette.dark ? 0.45f : 0.35f);
        toggle.setThumbTintList(new ColorStateList(states, new int[]{accent, offThumb}));
        toggle.setTrackTintList(new ColorStateList(states,
                new int[]{blend(palette.card, accent, 0.45f), offTrack}));
    }

    private void applyRipple(View view) {
        TypedValue value = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                && value.resourceId != 0) {
            view.setForeground(activity.getDrawable(value.resourceId));
        }
        view.setClickable(true);
        view.setFocusable(true);
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT_BOLD);
        return text;
    }

    private GradientDrawable round(int fill, int radius, int stroke, int width) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(DesignSystem.radius(radius)));
        if (width > 0) shape.setStroke(Math.max(1, dp(width)), stroke);
        return shape;
    }

    private static int blend(int base, int accent, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                (int) (Color.red(base) * (1f - a) + Color.red(accent) * a),
                (int) (Color.green(base) * (1f - a) + Color.green(accent) * a),
                (int) (Color.blue(base) * (1f - a) + Color.blue(accent) * a));
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + .5f);
    }
}
