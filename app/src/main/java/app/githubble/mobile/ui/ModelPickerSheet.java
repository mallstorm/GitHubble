package app.githubble.mobile.ui;

import android.app.Activity;
import app.githubble.mobile.ui.SheetDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.githubble.mobile.agent.ModelPicks;
import app.githubble.mobile.agent.OAuthSubscriptions;
import app.githubble.mobile.agent.OmniChatChatClient;
import app.githubble.mobile.agent.OmniChatConnectionStore;
import app.githubble.mobile.agent.PoolPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Full-height model picker: sticky search and filters, favourites and recents on top, services
 * as collapsible sections that show how many of their keys are switched on.
 */
public final class ModelPickerSheet {

    public interface Host {
        boolean isCurrent(String owner);
        /** The user chose a model; the host stores the selection and refreshes the chat. */
        void picked(String provider, String model);
        void openConnections();
        /** Straight to the add screen (sign in, API catalog, own endpoint). */
        void openAddConnection();
        void openProvider(String provider);
        void refreshCatalog();
    }

    private static final String[] FILTERS = {"Все", "★", "API", "Подписки", "Free", "Pass", "Billing"};
    private static final int RECENT_MAX = 6;

    private final Activity activity;
    private final HomeScreen.Palette palette;
    private final String owner;
    private final OmniChatConnectionStore store;
    private final SharedPreferences prefs;
    private final Host host;
    private final int warn, danger;

    private Dialog dialog;
    private LinearLayout list;
    private EditText search;
    private String filter = "Все";
    private final Set<String> expanded = new HashSet<>();
    private boolean expandedTouched;

    private ModelPickerSheet(Activity activity, HomeScreen.Palette palette, String owner,
                             OmniChatConnectionStore store, SharedPreferences prefs, Host host) {
        this.activity = activity;
        this.palette = palette;
        this.owner = owner;
        this.store = store;
        this.prefs = prefs;
        this.host = host;
        this.warn = palette.dark ? Color.rgb(240, 190, 90) : Color.rgb(176, 112, 16);
        this.danger = palette.dark ? Color.rgb(255, 128, 120) : Color.rgb(192, 57, 57);
    }

    public static void show(Activity activity, HomeScreen.Palette palette, String owner,
                            OmniChatConnectionStore store, SharedPreferences prefs, Host host) {
        new ModelPickerSheet(activity, palette, owner, store, prefs, host).open();
    }

    // ------------------------------------------------------------ layout

    private void open() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(palette.background, 28, 0, 0));
        root.setClipToOutline(true);

        // ---- sticky header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(10), dp(18), dp(8));
        View grip = new View(activity);
        grip.setBackground(round(palette.muted, 3, 0, 0));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(32), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(14);
        header.addView(grip, gp);

        String provider = store.selectedProvider(owner);
        String model = provider.isEmpty() ? "" : store.model(owner, provider);
        header.addView(text("Выбор модели", 22, palette.text, true));
        header.addView(text(model.isEmpty() ? "Модель не выбрана" : store.displayName(provider) + " · " + model, 12, palette.muted, false),
                lp(-1, -2, 0, 2, 0, 12));

        search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint("Поиск по моделям и сервисам");
        search.setTextSize(14);
        search.setTextColor(palette.text);
        search.setHintTextColor(palette.muted);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(round(palette.subtle, 14, palette.border, 1));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { fill(); }
        });
        header.addView(search, lp(-1, 46, 0, 0, 0, 10));
        header.addView(chips(), lp(-1, -2, 0, 0, 0, 0));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // ---- scrolling list
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18), dp(4), dp(18), dp(12));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- footer
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(18), dp(10), dp(18), dp(16));
        footer.addView(button("＋  Добавить подключение", true, v -> { dialog.dismiss(); host.openAddConnection(); }), lp(-1, 48, 0, 0, 0, 0, 0));
        LinearLayout second = new LinearLayout(activity);
        second.addView(button("Мои подключения", false, v -> { dialog.dismiss(); host.openConnections(); }), lp(0, 44, 1, 0, 0, 0, 0));
        second.addView(button("Обновить модели", false, v -> { dialog.dismiss(); host.refreshCatalog(); }), lp(0, 44, 1, 10, 0, 0, 0));
        footer.addView(second, lp(-1, -2, 0, 0, 10, 0, 0));
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
        expanded.add(provider);
        fill();
        dialog.show();
        if (w != null) {
            WindowManager.LayoutParams p = w.getAttributes();
            p.dimAmount = .38f;
            w.setAttributes(p);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(activity.getResources().getDisplayMetrics().widthPixels,
                    activity.getResources().getDisplayMetrics().heightPixels - dp(56));
        }
    }

    private View chips() {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(activity);
        final List<TextView> views = new ArrayList<>();
        for (int i = 0; i < FILTERS.length; i++) {
            final String value = FILTERS[i];
            TextView chip = text(value, 13, palette.text, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setOnClickListener(v -> {
                filter = value;
                for (int j = 0; j < views.size(); j++) styleChip(views.get(j), FILTERS[j].equals(filter));
                fill();
            });
            views.add(chip);
            styleChip(chip, value.equals(filter));
            row.addView(chip, lp(-2, 36, 0, i == 0 ? 0 : 8, 0, 0, 0));
        }
        scroll.addView(row);
        return scroll;
    }

    private void styleChip(TextView chip, boolean on) {
        chip.setTextColor(on ? Color.WHITE : palette.text);
        chip.setBackground(on ? round(palette.green, 12, 0, 0) : round(palette.subtle, 12, palette.border, 1));
    }

    // ------------------------------------------------------------ content

    private void fill() {
        if (list == null) return;
        list.removeAllViews();
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean searching = !query.isEmpty();
        List<String> favorites = ModelPicks.parse(prefs.getString(favoritesKey(), ""));
        List<String> recents = ModelPicks.parse(prefs.getString(recentsKey(), ""));
        String selectedProvider = store.selectedProvider(owner);
        String selectedModel = selectedProvider.isEmpty() ? "" : store.model(owner, selectedProvider);

        List<String> providers = new ArrayList<>();
        for (String id : OmniChatConnectionStore.supported().keySet()) {
            boolean pooled = OmniChatConnectionStore.poolable(id);
            if (pooled ? store.pool().slots(owner, id).isEmpty() : !store.hasKey(owner, id)) continue;
            providers.add(id);
        }
        if (providers.isEmpty()) {
            list.addView(text("Подключений пока нет", 16, palette.text, true), lp(-1, -2, 0, 16, 0, 4));
            list.addView(text("Добавьте API-ключи любого сервиса, свой endpoint или войдите через подписку — модели появятся здесь.", 13, palette.muted, false), lp(-1, -2, 0, 0, 0, 16));
            list.addView(button("Подключить сервис", true, v -> { dialog.dismiss(); host.openAddConnection(); }), lp(-1, 48, 0, 0, 0, 0));
            return;
        }

        int rows = 0;
        // ---- favourites and recents (hidden while searching or filtering by scope)
        if (!searching && ("Все".equals(filter) || "★".equals(filter))) {
            rows += quickSection("Избранное", favorites, query, selectedProvider, selectedModel, favorites);
            if ("Все".equals(filter)) {
                List<String> recentOnly = new ArrayList<>(recents);
                recentOnly.removeAll(favorites);
                rows += quickSection("Недавние", recentOnly, query, selectedProvider, selectedModel, favorites);
            }
        }
        if ("★".equals(filter)) {
            if (rows == 0) list.addView(text("Нажмите ☆ рядом с моделью, чтобы добавить её в избранное.", 13, palette.muted, false), lp(-1, -2, 0, 16, 0, 0));
            return;
        }

        // ---- services
        for (String provider : providers) {
            boolean subscription = OAuthSubscriptions.supports(provider) || provider.startsWith("proxy-");
            if ("Подписки".equals(filter) && !subscription || "API".equals(filter) && subscription) continue;
            List<String> models = new ArrayList<>();
            for (String model : store.models(owner, provider)) {
                String group = store.modelGroup(owner, provider, model);
                if (("Free".equals(filter) || "Pass".equals(filter) || "Billing".equals(filter)) && !filter.equals(group)) continue;
                if (searching && !(store.displayName(provider) + " " + model).toLowerCase(Locale.ROOT).contains(query)) continue;
                models.add(model);
            }
            boolean scoped = searching || "Free".equals(filter) || "Pass".equals(filter) || "Billing".equals(filter);
            if (scoped && models.isEmpty()) continue;
            boolean open = scoped || expanded.contains(provider) || (!expandedTouched && providers.size() == 1);
            list.addView(providerHeader(provider, models.size(), open), lp(-1, -2, 0, rows == 0 ? 8 : 12, 0, 4));
            rows++;
            if (!open) continue;
            if (models.isEmpty()) {
                list.addView(emptyCatalog(provider), lp(-1, -2, 0, 0, 0, 4));
                continue;
            }
            for (String model : models) list.addView(modelRow(provider, model, false, favorites, selectedProvider, selectedModel), lp(-1, -2, 0, 0, 0, 6));
        }
        if (rows == 0) list.addView(text("Ничего не найдено", 14, palette.muted, false), lp(-1, -2, 0, 24, 0, 0));
    }

    private int quickSection(String title, List<String> keys, String query, String selectedProvider, String selectedModel, List<String> favorites) {
        List<View> items = new ArrayList<>();
        for (String key : keys) {
            String provider = ModelPicks.providerOf(key), model = ModelPicks.modelOf(key);
            if (!OmniChatConnectionStore.supported().containsKey(provider)) continue;
            boolean pooled = OmniChatConnectionStore.poolable(provider);
            if (pooled ? store.pool().slots(owner, provider).isEmpty() : !store.hasKey(owner, provider)) continue;
            if (!query.isEmpty() && !(store.displayName(provider) + " " + model).toLowerCase(Locale.ROOT).contains(query)) continue;
            items.add(modelRow(provider, model, true, favorites, selectedProvider, selectedModel));
            if (items.size() >= (title.startsWith("Из") ? 50 : RECENT_MAX)) break;
        }
        if (items.isEmpty()) return 0;
        list.addView(heading(title), lp(-1, -2, 0, 8, 0, 4));
        for (View item : items) list.addView(item, lp(-1, -2, 0, 0, 0, 6));
        return items.size();
    }

    private View providerHeader(String provider, int count, boolean open) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(12), dp(8), dp(10), dp(8));
        int tint = ServiceIcon.accentOf(provider);
        GradientDrawable wash = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{blend(palette.card, tint, palette.dark ? 0.30f : 0.16f), blend(palette.card, tint, palette.dark ? 0.08f : 0.03f)});
        wash.setCornerRadius(dp(DesignSystem.radius(16)));
        wash.setStroke(dp(1), blend(palette.border, tint, 0.25f));
        row.setBackground(wash);
        row.addView(ServiceIcon.view(activity, provider, store.displayName(provider), 38), lp(38, 38, 0, 0, 12, 0));
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(store.displayName(provider), 15, palette.text, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        String status = "";
        int color = palette.muted;
        if (OmniChatConnectionStore.poolable(provider)) {
            PoolPolicy.Summary sum = store.pool().summary(owner, provider);
            status = sum.enabled + " из " + sum.total + (sum.total == 1 ? " ключ активен" : " ключей активно");
            if (sum.enabled == 0) { status = "все ключи выключены"; color = danger; }
            else if (sum.usable == 0) { status += " · сейчас недоступны"; color = warn; }
            else if (sum.hasTokenQuota()) status += " · осталось " + PoolPolicy.compact(sum.remainingTokens()) + " ток.";
        } else status = "подписка";
        words.addView(text(status + " · моделей: " + store.models(owner, provider).size(), 12, color, false));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(text(open ? "⌄" : "›", 20, palette.muted, false), lp(-2, -2, 8, 0, 4, 0));
        row.setOnClickListener(v -> {
            expandedTouched = true;
            if (!expanded.remove(provider)) expanded.add(provider);
            fill();
        });
        return row;
    }

    private View emptyCatalog(String provider) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(round(palette.subtle, 14, palette.border, 1));
        box.addView(text("Каталог пуст. Загрузите его с сервера или введите ID модели вручную.", 12, palette.muted, false));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.addView(button("Загрузить", false, v -> { dialog.dismiss(); host.refreshCatalog(); }), lp(0, 40, 1, 0, 0, 0, 0));
        buttons.addView(button("Ввести ID", false, v -> manualModel(provider)), lp(0, 40, 1, 8, 0, 0, 0));
        box.addView(buttons, lp(-1, -2, 0, 8, 0, 0));
        return box;
    }

    private void manualModel(String provider) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("ID модели, например gpt-4o-mini");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout box = new LinearLayout(activity);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        new SheetDialog.Builder(activity).setTitle(store.displayName(provider)).setView(box)
                .setPositiveButton("Выбрать", (d, w) -> {
                    String id = input.getText().toString().trim();
                    if (id.isEmpty() || !host.isCurrent(owner)) return;
                    dialog.dismiss();
                    choose(provider, id);
                }).setNegativeButton("Отмена", null).show();
    }

    private View modelRow(String provider, String model, boolean withProvider, List<String> favorites,
                          String selectedProvider, String selectedModel) {
        boolean selected = provider.equals(selectedProvider) && model.equals(selectedModel);
        boolean usable = !OmniChatConnectionStore.poolable(provider) || store.pool().hasEnabled(owner, provider);
        String key = ModelPicks.key(provider, model);
        boolean favorite = favorites.contains(key);

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(14), dp(8), dp(4), dp(8));
        int accent = selected ? palette.green : palette.border;
        row.setBackground(round(selected ? blend(palette.card, palette.green, palette.dark ? 0.2f : 0.1f) : palette.card, 14, accent, selected ? 2 : 1));

        if (withProvider) row.addView(ServiceIcon.view(activity, provider, store.displayName(provider), 32), lp(32, 32, 0, 0, 10, 0));
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = text((selected ? "✓  " : "") + model, 14, usable ? palette.text : palette.muted, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        words.addView(name);

        LinearLayout tags = new LinearLayout(activity);
        tags.setGravity(Gravity.CENTER_VERTICAL);
        if (withProvider) tags.addView(text(store.displayName(provider), 11, palette.muted, false), lp(-2, -2, 0, 0, 8, 0));
        String group = store.modelGroup(owner, provider, model);
        if (!group.isEmpty()) tags.addView(pill(group, group.equals("Free") ? palette.green : group.equals("Pass") ? Color.rgb(113, 60, 179) : warn), lp(-2, -2, 0, 0, 6, 0));
        if (!usable) tags.addView(pill("ключи выключены", danger), lp(-2, -2, 0, 0, 6, 0));
        if (tags.getChildCount() > 0) words.addView(tags, lp(-1, -2, 0, 4, 0, 0));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView star = text(favorite ? "★" : "☆", 22, favorite ? warn : palette.muted, false);
        star.setGravity(Gravity.CENTER);
        star.setOnClickListener(v -> {
            prefs.edit().putString(favoritesKey(), ModelPicks.serialize(ModelPicks.toggle(favorites, key))).apply();
            fill();
        });
        row.addView(star, new LinearLayout.LayoutParams(dp(48), dp(48)));

        row.setOnClickListener(v -> {
            if (!host.isCurrent(owner)) return;
            if (!usable) { dialog.dismiss(); host.openProvider(provider); return; }
            dialog.dismiss();
            choose(provider, model);
        });
        return row;
    }

    private void choose(String provider, String model) {
        List<String> recents = ModelPicks.parse(prefs.getString(recentsKey(), ""));
        prefs.edit().putString(recentsKey(), ModelPicks.serialize(ModelPicks.push(recents, ModelPicks.key(provider, model), RECENT_MAX))).apply();
        host.picked(provider, model);
    }

    private String favoritesKey() { return "model_favorites_v1_" + owner; }

    private String recentsKey() { return "model_recents_v1_" + owner; }

    // ------------------------------------------------------------ widgets

    private TextView heading(String value) {
        TextView t = text(value.toUpperCase(new Locale("ru")), 12, palette.muted, true);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(4), dp(6), dp(4), dp(2));
        return t;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pill(String value, int color) {
        TextView t = text(value, 10, color, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        t.setBackground(round(blend(palette.card, color, palette.dark ? 0.26f : 0.14f), 10, 0, 0));
        return t;
    }

    private TextView button(String label, boolean primary, View.OnClickListener action) {
        TextView b = text(label, 14, primary ? Color.WHITE : palette.green, true);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(primary ? round(palette.green, 12, 0, 0) : round(blend(palette.card, palette.green, 0.10f), 12, 0, 0));
        b.setOnClickListener(action);
        return b;
    }

    /** Width/height in dp (-1 match, -2 wrap; with weight 1 and width 0 for equal columns), margins in dp. */
    private LinearLayout.LayoutParams lp(int width, int height, float weight, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width > 0 ? dp(width) : width, height > 0 ? dp(height) : height, weight);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    private LinearLayout.LayoutParams lp(int width, int height, int left, int top, int right, int bottom) {
        return lp(width, height, 0, left, top, right, bottom);
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
        return Color.rgb((int) (Color.red(base) * (1f - a) + Color.red(accent) * a),
                (int) (Color.green(base) * (1f - a) + Color.green(accent) * a),
                (int) (Color.blue(base) * (1f - a) + Color.blue(accent) * a));
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
