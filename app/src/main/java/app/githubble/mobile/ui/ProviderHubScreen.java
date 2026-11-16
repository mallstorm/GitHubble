package app.githubble.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
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
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import app.githubble.mobile.agent.OmniChatChatClient;
import app.githubble.mobile.agent.OAuthSubscriptions;
import app.githubble.mobile.agent.OmniChatConnectionStore;
import app.githubble.mobile.agent.PoolPolicy;
import app.githubble.mobile.agent.ProviderPool;
import app.githubble.mobile.agent.QuotaProbe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;

/**
 * Connection combine: every service with all its keys on one screen. Each key has its own switch,
 * statistics and limits; switched-off keys leave both the rotation and the shared quota.
 * Screens: hub, one service (stats, keys, endpoint), add service, custom endpoint.
 */
public final class ProviderHubScreen {

    /** Navigation and app-level effects provided by the host activity. */
    public interface Host {
        boolean isCurrent(String owner);
        void openProvider(String provider);
        void openAddService();
        void openSubscriptions(boolean proxy);
        void openNetwork();
        void openModelPicker();
        void openUsage();
        /** Selection, keys or switches changed: the chat header must be refreshed. */
        void selectionChanged();
        /** Re-renders whichever screen is on top (used after background work finished). */
        void refreshCurrentScreen();
    }

    private static final String SENTINEL = "__local_no_auth__";
    private static final String[] PROTOCOL_IDS = {"openai-chat", "openai-responses", "anthropic", "gemini", "ollama"};
    private static final String[] PROTOCOL_NAMES = {"OpenAI Chat", "OpenAI Responses", "Anthropic", "Gemini", "Ollama"};
    private static final String[] POPULAR = {"openai", "anthropic", "gemini", "openrouter", "deepseek", "groq", "xai",
            "mistral", "nvidia", "together", "cerebras", "polza", "vsegpt"};
    private static final String[] OAUTH = {OAuthSubscriptions.OPENAI, "chatgpt-plan", "openrouter-account", "kilo-account",
            OAuthSubscriptions.CLINE, OAuthSubscriptions.KIMI, OAuthSubscriptions.GROK,
            "gemini-antigravity", "claude-subscription", "proxy-chatgpt", "proxy-gemini", "proxy-claude", "proxy-grok"};

    private final Activity activity;
    private final LinearLayout content;
    private final HomeScreen.Palette palette;
    private final OmniChatConnectionStore store;
    private final OmniChatChatClient client;
    private final ExecutorService io;
    private final Host host;
    private final String owner;
    private final int danger, warn;
    private Runnable rerender = () -> { };

    public ProviderHubScreen(Activity activity, LinearLayout content, HomeScreen.Palette palette, String owner,
                             OmniChatConnectionStore store, OmniChatChatClient client, ExecutorService io, Host host) {
        this.activity = activity;
        this.content = content;
        this.palette = palette;
        this.owner = owner;
        this.store = store;
        this.client = client;
        this.io = io;
        this.host = host;
        this.danger = palette.dark ? Color.rgb(255, 128, 120) : Color.rgb(192, 57, 57);
        this.warn = palette.dark ? Color.rgb(240, 190, 90) : Color.rgb(176, 112, 16);
    }

    private ProviderPool pool() { return store.pool(); }

    private void reload() {
        if (!host.isCurrent(owner)) return;
        content.removeAllViews();
        rerender.run();
    }

    // ================================================================== hub

    public void renderHub() {
        rerender = this::renderHub;
        List<String> services = connectedServices();

        // ---- shared totals over all switched-on keys
        long calls = 0, in = 0, out = 0;
        int keys = 0, active = 0;
        for (String provider : services) {
            PoolPolicy.Summary s = pool().summary(owner, provider);
            calls += s.calls; in += s.inTokens; out += s.outTokens; keys += s.total; active += s.enabled;
        }
        LinearLayout summary = card(palette.dark ? 0.12f : 0.06f);
        summary.addView(label("Общая сводка", 12, palette.muted, true));
        summary.addView(label(services.size() + " " + plural(services.size(), "сервис", "сервиса", "сервисов")
                + " · ключей включено " + active + " из " + keys, 17, palette.text, true), params(-1, -2, 0, 4, 0, 0));
        summary.addView(label("Запросов: " + calls + " · токены: " + PoolPolicy.compact(in) + " вход / "
                + PoolPolicy.compact(out) + " выход. Считаются только включённые ключи.", 12, palette.muted, false),
                params(-1, -2, 0, 4, 0, 0));
        content.addView(summary, params(-1, -2, 0, 4, 0, 10));

        LinearLayout actions = new LinearLayout(activity);
        actions.addView(button("＋  Сервис", true, v -> host.openAddService()), params(0, 48, 1, 0, 0, 0, 0));
        actions.addView(button("Свой endpoint", false, v -> customServiceSheet()), params(0, 48, 1, 8, 0, 0, 0));
        content.addView(actions, params(-1, -2, 0, 0, 0, 12));

        // ---- API services
        content.addView(heading("API-сервисы и ключи"));
        if (services.isEmpty()) {
            LinearLayout empty = card(0.03f);
            empty.addView(label("Подключений пока нет", 15, palette.text, true));
            empty.addView(label("Добавьте сервис из каталога или свой endpoint, например Keydealer.ru или Claudy.shop. "
                    + "К одному сервису можно подключить сколько угодно ключей.", 13, palette.muted, false), params(-1, -2, 0, 4, 0, 0));
            content.addView(empty, params(-1, -2, 0, 0, 0, 10));
        }
        for (String provider : services) content.addView(serviceCard(provider), params(-1, -2, 0, 0, 0, 10));

        // ---- OAuth subscriptions (single session each)
        content.addView(heading("Подписки"));
        LinearLayout subs = card(0.03f);
        int shown = 0;
        for (String provider : OAUTH) {
            if (!OmniChatConnectionStore.supported().containsKey(provider) || !store.hasKey(owner, provider)) continue;
            shown++;
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            LinearLayout words = column();
            words.addView(label(store.displayName(provider), 14, palette.text, true));
            words.addView(label(store.models(owner, provider).size() + " моделей"
                    + (store.isConfigured(owner, provider) ? "" : " · загрузите каталог"), 12, palette.muted, false));
            row.addView(words, params(0, -2, 1, 0, 0, 0, 0));
            row.addView(pill("Подключено", palette.green), params(-2, -2, 0, 8, 0, 0, 0));
            boolean proxy = provider.startsWith("proxy-");
            row.setOnClickListener(v -> host.openSubscriptions(proxy));
            ripple(row);
            subs.addView(row);
        }
        if (shown == 0) subs.addView(label("Вход через аккаунт ChatGPT, Claude, Gemini, Grok и другие — без API-ключа.", 13, palette.muted, false));
        LinearLayout subButtons = new LinearLayout(activity);
        subButtons.addView(button("Аккаунты", false, v -> host.openSubscriptions(false)), params(0, 44, 1, 0, 0, 0, 0));
        subButtons.addView(button("Subscription Proxy", false, v -> host.openSubscriptions(true)), params(0, 44, 1, 8, 0, 0, 0));
        subs.addView(subButtons, params(-1, -2, 0, 10, 0, 0));
        content.addView(subs, params(-1, -2, 0, 0, 0, 10));

        // ---- tools
        content.addView(heading("Инструменты"));
        LinearLayout tools = new LinearLayout(activity);
        tools.addView(button("Выбор модели", false, v -> host.openModelPicker()), params(0, 46, 1, 0, 0, 0, 0));
        tools.addView(button("Использование", false, v -> host.openUsage()), params(0, 46, 1, 8, 0, 0, 0));
        tools.addView(button("Сеть / DNS", false, v -> host.openNetwork()), params(0, 46, 1, 8, 0, 0, 0));
        content.addView(tools, params(-1, -2, 0, 0, 0, 12));
    }

    private LinearLayout serviceCard(String provider) {
        PoolPolicy.Summary sum = pool().summary(owner, provider);
        List<PoolPolicy.Slot> slots = pool().slots(owner, provider);
        long now = System.currentTimeMillis();
        LinearLayout card = card(0.03f);

        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(initialTile(store.displayName(provider), palette.green), params(40, 40, 0, 0, 0, 12, 0));
        LinearLayout words = column();
        TextView title = label(store.displayName(provider), 16, palette.text, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        words.addView(label("Включено " + sum.enabled + " из " + sum.total + " · доступно сейчас " + sum.usable
                + (provider.equals(store.selectedProvider(owner)) ? " · используется в чате" : ""), 12, palette.muted, false));
        head.addView(words, params(0, -2, 1, 0, 0, 0, 0));
        Switch master = toggle(sum.enabled > 0, on -> {
            pool().setAllEnabled(owner, provider, on);
            host.selectionChanged();
            reload();
        });
        head.addView(master, params(-2, 48, 0, 4, 0, 0, 0));
        head.setOnClickListener(v -> host.openProvider(provider));
        ripple(head);
        card.addView(head);

        View totals = quotaBlock(sum);
        if (totals != null) card.addView(totals, params(-1, -2, 0, 10, 0, 0));
        else card.addView(label("Запросов: " + sum.calls + " · токены " + PoolPolicy.compact(sum.inTokens) + "↑ "
                + PoolPolicy.compact(sum.outTokens) + "↓ · лимиты не заданы", 12, palette.muted, false), params(-1, -2, 0, 8, 0, 0));

        int limit = Math.min(slots.size(), 5);
        for (int i = 0; i < limit; i++) card.addView(keyRow(provider, slots.get(i), now), params(-1, -2, 0, i == 0 ? 10 : 0, 0, 0));
        LinearLayout footer = new LinearLayout(activity);
        if (slots.size() > limit) footer.addView(label("Ещё ключей: " + (slots.size() - limit), 12, palette.muted, false), params(0, -2, 1, 0, 0, 0, 0));
        else footer.addView(new View(activity), params(0, 1, 1, 0, 0, 0, 0));
        footer.addView(textButton("Открыть", v -> host.openProvider(provider)), params(-2, 40, 0, 0, 0, 0, 0));
        card.addView(footer, params(-1, -2, 0, 6, 0, 0));
        return card;
    }

    /** Compact key line with its own switch; used inside service cards. */
    private View keyRow(String provider, PoolPolicy.Slot slot, long now) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        LinearLayout words = column();
        words.addView(label(slot.label, 14, slot.enabled ? palette.text : palette.muted, true));
        words.addView(label(statusText(slot, now) + " · " + slot.calls + " запр. · " + PoolPolicy.compact(slot.totalTokens()) + " ток.",
                11, statusColor(slot, now), false));
        row.addView(words, params(0, -2, 1, 0, 0, 0, 0));
        row.addView(toggle(slot.enabled, on -> {
            pool().setEnabled(owner, provider, slot.id, on);
            host.selectionChanged();
            reload();
        }), params(-2, 48, 0, 4, 0, 0, 0));
        return row;
    }

    // ================================================================== service screen

    public void renderProvider(String provider) {
        rerender = () -> renderProvider(provider);
        if (!OmniChatConnectionStore.supported().containsKey(provider)) {
            content.addView(label("Сервис удалён", 15, palette.muted, false));
            return;
        }
        PoolPolicy.Summary sum = pool().summary(owner, provider);
        List<PoolPolicy.Slot> slots = pool().slots(owner, provider);
        long now = System.currentTimeMillis();
        JSONObject config = store.configuration(owner, provider);
        boolean custom = OmniChatConnectionStore.isCustom(provider);

        // ---- endpoint
        LinearLayout endpoint = card(palette.dark ? 0.12f : 0.06f);
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(initialTile(store.displayName(provider), palette.green), params(44, 44, 0, 0, 0, 12, 0));
        LinearLayout words = column();
        words.addView(label(store.displayName(provider), 18, palette.text, true));
        words.addView(label(custom ? "Свой сервис" : "Сервис из каталога", 12, palette.muted, false));
        head.addView(words, params(0, -2, 1, 0, 0, 0, 0));
        endpoint.addView(head);
        String base = config.optString("base_url");
        endpoint.addView(label((base.isEmpty() ? "Адрес не задан" : base) + "\nПротокол: " + protocolName(config.optString("protocol", "openai-chat")),
                12, palette.muted, false), params(-1, -2, 0, 10, 0, 0));
        LinearLayout endpointButtons = new LinearLayout(activity);
        endpointButtons.addView(button("Изменить endpoint", false, v -> endpointSheet(provider)), params(0, 44, 1, 0, 0, 0, 0));
        endpoint.addView(endpointButtons, params(-1, -2, 0, 10, 0, 0));
        content.addView(endpoint, params(-1, -2, 0, 4, 0, 10));

        // ---- statistics of switched-on keys
        content.addView(heading("Статистика · включённые ключи"));
        LinearLayout stats = card(0.03f);
        stats.addView(statLine("Включено ключей", sum.enabled + " из " + sum.total));
        stats.addView(statLine("Запросов", sum.calls + "  (успешно " + sum.okCalls + ", ошибок " + sum.failCalls + ")"));
        stats.addView(statLine("Токены", PoolPolicy.compact(sum.inTokens) + " вход · " + PoolPolicy.compact(sum.outTokens) + " выход"));
        View totals = quotaBlock(sum);
        if (totals != null) stats.addView(totals, params(-1, -2, 0, 8, 0, 0));
        else stats.addView(label("Лимиты не заданы. Задайте квоту у ключа, чтобы видеть общий остаток.", 12, palette.muted, false), params(-1, -2, 0, 8, 0, 0));
        LinearLayout statButtons = new LinearLayout(activity);
        statButtons.addView(button("Сбросить счётчики", false, v -> confirm("Сбросить счётчики?",
                "Обнулятся запросы, токены и ошибки всех ключей этого сервиса. Сами ключи сохранятся.",
                () -> { pool().resetStats(owner, provider, null); reload(); })), params(0, 44, 1, 0, 0, 0, 0));
        stats.addView(statButtons, params(-1, -2, 0, 10, 0, 0));
        content.addView(stats, params(-1, -2, 0, 0, 0, 10));

        // ---- routing
        content.addView(heading("Распределение запросов"));
        LinearLayout routing = card(0.03f);
        int strategy = pool().strategy(owner, provider);
        routing.addView(label(PoolPolicy.strategyName(strategy), 15, palette.text, true));
        routing.addView(label(PoolPolicy.strategyHint(strategy) + ". Если ключ отклонён, на паузе из-за лимита или без квоты — берётся следующий.", 12, palette.muted, false), params(-1, -2, 0, 4, 0, 0));
        routing.addView(button("Изменить стратегию", false, v -> strategySheet(provider)), params(-1, 44, 0, 10, 0, 0));
        content.addView(routing, params(-1, -2, 0, 0, 0, 10));

        // ---- keys
        content.addView(heading("Ключи и аккаунты · " + slots.size()));
        for (PoolPolicy.Slot slot : slots) content.addView(keyCard(provider, slot, now), params(-1, -2, 0, 0, 0, 10));
        content.addView(button("＋  Добавить ключ", true, v -> keySheet(provider, null)), params(-1, 48, 0, 0, 0, 12));

        // ---- models
        content.addView(heading("Модели"));
        LinearLayout models = card(0.03f);
        String current = store.model(owner, provider);
        models.addView(label(current.isEmpty() ? "Модель не выбрана" : current, 15, palette.text, true));
        models.addView(label("В каталоге: " + store.models(owner, provider).size(), 12, palette.muted, false), params(-1, -2, 0, 2, 0, 0));
        LinearLayout modelButtons = new LinearLayout(activity);
        modelButtons.addView(button("Обновить каталог", false, v -> discover(provider)), params(0, 44, 1, 0, 0, 0, 0));
        modelButtons.addView(button("Выбрать модель", false, v -> host.openModelPicker()), params(0, 44, 1, 8, 0, 0, 0));
        models.addView(modelButtons, params(-1, -2, 0, 10, 0, 0));
        content.addView(models, params(-1, -2, 0, 0, 0, 10));

        // ---- removal
        LinearLayout removal = new LinearLayout(activity);
        removal.addView(dangerButton(custom ? "Удалить сервис и ключи" : "Удалить все ключи", v -> confirm(
                custom ? "Удалить сервис?" : "Удалить все ключи?",
                custom ? "Сервис, его endpoint и все ключи будут удалены с устройства." : "Все ключи этого сервиса будут удалены с устройства. История чатов сохранится.",
                () -> {
                    if (custom) store.deleteCustomProvider(owner, provider); else store.disconnect(owner, provider);
                    host.selectionChanged();
                    toast("Удалено");
                    activity.onBackPressed();
                })), params(-1, 46, 1, 0, 0, 0, 0));
        content.addView(removal, params(-1, -2, 0, 4, 0, 12));
    }

    private LinearLayout keyCard(String provider, PoolPolicy.Slot slot, long now) {
        LinearLayout card = card(0.03f);
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = column();
        TextView name = label(slot.label, 15, slot.enabled ? palette.text : palette.muted, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        words.addView(pill(statusText(slot, now), statusColor(slot, now)), params(-2, -2, 0, 4, 0, 0));
        head.addView(words, params(0, -2, 1, 0, 0, 0, 0));
        head.addView(toggle(slot.enabled, on -> {
            pool().setEnabled(owner, provider, slot.id, on);
            host.selectionChanged();
            reload();
        }), params(-2, 48, 0, 4, 0, 0, 0));
        card.addView(head);

        card.addView(label("Запросов " + slot.calls + " (ошибок " + slot.failCalls + ") · токены "
                + PoolPolicy.compact(slot.inTokens) + "↑ " + PoolPolicy.compact(slot.outTokens) + "↓ · "
                + (slot.lastUsed == 0 ? "ещё не использовался" : "использован " + ago(slot.lastUsed, now)), 12, palette.muted, false), params(-1, -2, 0, 6, 0, 0));
        if (slot.quotaTokens > 0) card.addView(limitLine("Токены за период", slot.periodTokens, slot.quotaTokens), params(-1, -2, 0, 8, 0, 0));
        if (slot.quotaRequests > 0) card.addView(limitLine("Запросы за период", slot.periodRequests, slot.quotaRequests), params(-1, -2, 0, 8, 0, 0));
        if (!slot.lastError.isEmpty() && slot.lastErrorAt > 0)
            card.addView(label("Последняя ошибка (" + ago(slot.lastErrorAt, now) + "): " + slot.lastError, 11, danger, false), params(-1, -2, 0, 8, 0, 0));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.addView(button("Изменить", false, v -> keySheet(provider, slot)), params(0, 42, 1, 0, 0, 0, 0));
        if (QuotaProbe.supported(provider)) buttons.addView(button("Баланс", false, v -> balance(provider, slot)), params(0, 42, 1, 8, 0, 0, 0));
        buttons.addView(dangerButton("Удалить", v -> confirm("Удалить ключ «" + slot.label + "»?",
                "Ключ будет стёрт с устройства, статистика потеряна.", () -> {
                    pool().removeKey(owner, provider, slot.id);
                    host.selectionChanged();
                    reload();
                })), params(0, 42, 1, 8, 0, 0, 0));
        card.addView(buttons, params(-1, -2, 0, 10, 0, 0));
        return card;
    }

    // ================================================================== add service

    public void renderAddService() {
        rerender = this::renderAddService;
        LinearLayout custom = card(palette.dark ? 0.14f : 0.07f);
        custom.addView(label("Свой сервис", 16, palette.text, true));
        custom.addView(label("Любой endpoint: перепродавец ключей, свой прокси, локальный сервер. Укажите адрес, протокол и ключи.", 12, palette.muted, false), params(-1, -2, 0, 4, 0, 0));
        custom.addView(button("＋  Создать со своим endpoint", true, v -> customServiceSheet()), params(-1, 48, 0, 10, 0, 0));
        content.addView(custom, params(-1, -2, 0, 4, 0, 10));

        EditText search = field("Найти сервис", "", false, false);
        LinearLayout list = column();
        content.addView(search, params(-1, 48, 0, 0, 0, 8));
        content.addView(list, params(-1, -2, 0, 0, 0, 0));
        Runnable fill = () -> {
            list.removeAllViews();
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            Map<String, String> all = OmniChatConnectionStore.apiSupported();
            List<String> ordered = new ArrayList<>();
            for (String id : POPULAR) if (all.containsKey(id)) ordered.add(id);
            List<String> rest = new ArrayList<>();
            for (String id : all.keySet()) if (!ordered.contains(id)) rest.add(id);
            java.util.Collections.sort(rest, (a, b) -> all.get(a).compareToIgnoreCase(all.get(b)));
            ordered.addAll(rest);
            int shown = 0;
            boolean popularHeading = false, restHeading = false;
            for (String id : ordered) {
                String name = all.get(id);
                if (!query.isEmpty() && !(name + " " + id).toLowerCase(Locale.ROOT).contains(query)) continue;
                boolean popular = Arrays.asList(POPULAR).contains(id);
                if (query.isEmpty() && popular && !popularHeading) { list.addView(heading("Популярные")); popularHeading = true; }
                if (query.isEmpty() && !popular && !restHeading) { list.addView(heading("Все сервисы")); restHeading = true; }
                list.addView(serviceRow(id, name), params(-1, -2, 0, 0, 0, 8));
                shown++;
            }
            if (shown == 0) list.addView(label("Ничего не найдено. Можно создать свой сервис выше.", 13, palette.muted, false), params(-1, -2, 0, 12, 0, 0));
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { fill.run(); }
        });
        fill.run();
    }

    private View serviceRow(String id, String name) {
        LinearLayout row = card(0.02f);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(initialTile(name, palette.green), params(40, 40, 0, 0, 0, 12, 0));
        LinearLayout words = column();
        words.addView(label(name, 15, palette.text, true));
        int keys = pool().slots(owner, id).size();
        words.addView(label(keys > 0 ? "Подключён · ключей: " + keys : "Добавить ключ", 12, keys > 0 ? palette.green : palette.muted, false));
        row.addView(words, params(0, -2, 1, 0, 0, 0, 0));
        row.addView(label("›", 24, palette.muted, false));
        row.setOnClickListener(v -> {
            if (keys > 0) host.openProvider(id); else keySheet(id, null);
        });
        ripple(row);
        return row;
    }

    // ================================================================== sheets

    private GitAgentUi ui() {
        return new GitAgentUi(activity, palette.background, palette.card, palette.text, palette.muted, palette.green, palette.border);
    }

    /** Add (slot == null) or edit one key: label, secret, optional limits and reset period. */
    private void keySheet(String provider, PoolPolicy.Slot slot) {
        boolean keyless = app.githubble.mobile.network.NativeProviderCatalog.keyless(provider);
        GitAgentUi ui = ui();
        LinearLayout body = ui.body(slot == null ? "Новый ключ" : "Ключ «" + slot.label + "»", true);
        body.addView(label(store.displayName(provider) + (slot == null ? "" : ". Пустое поле ключа оставит прежний."), 13, palette.muted, false), params(-1, -2, 0, 0, 0, 12));
        EditText name = field("Название (необязательно)", slot == null ? "" : slot.label, false, false);
        EditText secret = field(keyless && slot == null ? "Ключ (можно оставить пустым)" : slot == null ? "API-ключ" : "Новый ключ (необязательно)", "", true, false);
        EditText tokens = field("Квота токенов, например 1m", slot == null || slot.quotaTokens == 0 ? "" : String.valueOf(slot.quotaTokens), false, true);
        EditText requests = field("Квота запросов, например 500", slot == null || slot.quotaRequests == 0 ? "" : String.valueOf(slot.quotaRequests), false, true);
        body.addView(fieldLabel("Название")); body.addView(name, params(-1, 48, 0, 0, 0, 12));
        body.addView(fieldLabel("Ключ")); body.addView(secret, params(-1, 48, 0, 0, 0, 12));
        body.addView(fieldLabel("Квота токенов за период (0 — без лимита)")); body.addView(tokens, params(-1, 48, 0, 0, 0, 12));
        body.addView(fieldLabel("Квота запросов за период (0 — без лимита)")); body.addView(requests, params(-1, 48, 0, 0, 0, 12));
        final int[] reset = {slot == null ? PoolPolicy.RESET_NEVER : slot.resetMode};
        body.addView(fieldLabel("Сброс счётчика периода"));
        body.addView(chips(new String[]{"Никогда", "Раз в день", "Раз в месяц"}, reset[0], i -> reset[0] = i), params(-1, -2, 0, 0, 0, 14));
        body.addView(label("Лимиты — ваши собственные: приложение считает расход и исключает ключ из ротации, когда лимит достигнут. "
                + "Они не заменяют реальный баланс у провайдера.", 11, palette.muted, false), params(-1, -2, 0, 0, 0, 14));
        final Dialog[] dialog = {null};
        LinearLayout buttons = new LinearLayout(activity);
        buttons.addView(button("Отмена", false, v -> dialog[0].dismiss()), params(0, 48, 1, 0, 0, 0, 0));
        buttons.addView(button("Сохранить", true, v -> {
            try {
                long tokenLimit = parseCount(tokens.getText().toString()), requestLimit = parseCount(requests.getText().toString());
                String value = secret.getText().toString().trim();
                String id;
                if (slot == null) {
                    if (value.isEmpty() && keyless) value = SENTINEL;
                    id = pool().addKey(owner, provider, name.getText().toString(), value);
                    pool().update(owner, provider, id, name.getText().toString().isEmpty() ? labelFor(provider, id) : name.getText().toString(),
                            "", tokenLimit, requestLimit, reset[0]);
                    store.saveApiConnection(owner, provider, "", "");
                } else {
                    pool().update(owner, provider, slot.id, name.getText().toString(), value, tokenLimit, requestLimit, reset[0]);
                }
                dialog[0].dismiss();
                host.selectionChanged();
                boolean first = slot == null && store.model(owner, provider).isEmpty();
                reload();
                if (first) discover(provider);
                else toast("Сохранено");
            } catch (RuntimeException error) {
                toast(error.getMessage() == null ? "Не удалось сохранить" : error.getMessage());
            }
        }), params(0, 48, 1, 8, 0, 0, 0));
        body.addView(buttons);
        dialog[0] = ui.present(body, true);
    }

    private String labelFor(String provider, String id) {
        for (PoolPolicy.Slot s : pool().slots(owner, provider)) if (s.id.equals(id)) return s.label;
        return "Ключ";
    }

    private void strategySheet(String provider) {
        String[] names = new String[4];
        for (int i = 0; i < 4; i++) names[i] = PoolPolicy.strategyName(i) + "\n" + PoolPolicy.strategyHint(i);
        new AlertDialog.Builder(activity).setTitle("Распределение запросов")
                .setSingleChoiceItems(names, pool().strategy(owner, provider), (d, which) -> {
                    pool().setStrategy(owner, provider, which);
                    d.dismiss();
                    reload();
                }).setNegativeButton("Закрыть", null).show();
    }

    private void endpointSheet(String provider) {
        boolean custom = OmniChatConnectionStore.isCustom(provider);
        JSONObject config = store.configuration(owner, provider);
        GitAgentUi ui = ui();
        LinearLayout body = ui.body("Endpoint", true);
        EditText name = field("Название", store.displayName(provider), false, false);
        EditText url = field("https://api.example.com/v1", config.optString("base_url"), false, false);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText scope = field("Folder / Account ID при необходимости", config.optString("auth_scope"), false, false);
        if (custom) { body.addView(fieldLabel("Название")); body.addView(name, params(-1, 48, 0, 0, 0, 12)); }
        body.addView(fieldLabel("Адрес API"));
        body.addView(url, params(-1, 48, 0, 0, 0, 6));
        body.addView(label("Для OpenAI-совместимых сервисов адрес обычно заканчивается на /v1. HTTP допускается только для localhost.", 11, palette.muted, false), params(-1, -2, 0, 0, 0, 12));
        final int[] protocol = {indexOf(PROTOCOL_IDS, config.optString("protocol", "openai-chat"))};
        body.addView(fieldLabel("Протокол"));
        body.addView(chips(PROTOCOL_NAMES, protocol[0], i -> protocol[0] = i), params(-1, -2, 0, 0, 0, 12));
        if (!custom) { body.addView(fieldLabel("Scope")); body.addView(scope, params(-1, 48, 0, 0, 0, 12)); }
        final Dialog[] dialog = {null};
        LinearLayout buttons = new LinearLayout(activity);
        buttons.addView(button("Отмена", false, v -> dialog[0].dismiss()), params(0, 48, 1, 0, 0, 0, 0));
        buttons.addView(button("Сохранить", true, v -> {
            try {
                if (custom) store.updateCustomProvider(provider, name.getText().toString(), url.getText().toString(), PROTOCOL_IDS[Math.max(0, protocol[0])]);
                else store.configure(owner, provider, url.getText().toString(), PROTOCOL_IDS[Math.max(0, protocol[0])], scope.getText().toString());
                dialog[0].dismiss();
                host.selectionChanged();
                reload();
                toast("Endpoint сохранён");
            } catch (RuntimeException error) {
                toast(error.getMessage() == null ? "Проверьте адрес и протокол" : error.getMessage());
            }
        }), params(0, 48, 1, 8, 0, 0, 0));
        body.addView(buttons);
        dialog[0] = ui.present(body, true);
    }

    /** New user-defined service: own endpoint, protocol and first key. */
    private void customServiceSheet() {
        GitAgentUi ui = ui();
        LinearLayout body = ui.body("Свой сервис", true);
        body.addView(label("Например Keydealer.ru или Claudy.shop: укажите адрес их API и ключ. Позже можно добавить любое число ключей.", 13, palette.muted, false), params(-1, -2, 0, 0, 0, 12));
        EditText name = field("Название сервиса", "", false, false);
        EditText url = field("https://api.example.com/v1", "", false, false);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText secret = field("API-ключ", "", true, false);
        body.addView(fieldLabel("Название")); body.addView(name, params(-1, 48, 0, 0, 0, 12));
        body.addView(fieldLabel("Адрес API (endpoint)")); body.addView(url, params(-1, 48, 0, 0, 0, 12));
        final int[] protocol = {0};
        body.addView(fieldLabel("Протокол"));
        body.addView(chips(PROTOCOL_NAMES, 0, i -> protocol[0] = i), params(-1, -2, 0, 0, 0, 12));
        body.addView(label("OpenAI Chat подходит большинству перепродавцов; для Claude-совместимых шлюзов выберите Anthropic.", 11, palette.muted, false), params(-1, -2, 0, 0, 0, 12));
        body.addView(fieldLabel("Ключ")); body.addView(secret, params(-1, 48, 0, 0, 0, 12));
        final boolean[] keyless = {false};
        LinearLayout keylessRow = new LinearLayout(activity);
        keylessRow.setGravity(Gravity.CENTER_VERTICAL);
        keylessRow.addView(label("Без ключа (локальный или открытый сервер)", 13, palette.text, false), params(0, -2, 1, 0, 0, 0, 0));
        keylessRow.addView(toggle(false, on -> keyless[0] = on), params(-2, 48, 0, 0, 0, 0, 0));
        body.addView(keylessRow, params(-1, -2, 0, 0, 0, 14));
        final Dialog[] dialog = {null};
        LinearLayout buttons = new LinearLayout(activity);
        buttons.addView(button("Отмена", false, v -> dialog[0].dismiss()), params(0, 48, 1, 0, 0, 0, 0));
        buttons.addView(button("Создать", true, v -> {
            String createdId = "";
            try {
                String value = secret.getText().toString().trim();
                if (!keyless[0] && value.isEmpty()) throw new IllegalArgumentException("Введите ключ или включите «Без ключа»");
                createdId = store.createCustomProvider(name.getText().toString(), url.getText().toString(), PROTOCOL_IDS[Math.max(0, protocol[0])], keyless[0]);
                pool().addKey(owner, createdId, keyless[0] ? "Без ключа" : "Ключ 1", keyless[0] ? SENTINEL : value);
                store.saveApiConnection(owner, createdId, "", "");
                dialog[0].dismiss();
                host.selectionChanged();
                host.openProvider(createdId);
                discover(createdId);
            } catch (RuntimeException error) {
                if (!createdId.isEmpty()) store.deleteCustomProvider(owner, createdId);
                toast(error.getMessage() == null ? "Не удалось создать сервис" : error.getMessage());
            }
        }), params(0, 48, 1, 8, 0, 0, 0));
        body.addView(buttons);
        dialog[0] = ui.present(body, true);
    }

    // ================================================================== operations

    /** Loads the model catalog of a service with its first available key. */
    public void discover(String provider) {
        toast("Загружаем модели…");
        io.execute(() -> {
            try {
                List<String> models = client.discoverModelsForAccount(owner, provider, store.credential(owner, provider));
                activity.runOnUiThread(() -> {
                    if (!host.isCurrent(owner)) return;
                    store.cacheModels(owner, provider, models);
                    if (!models.isEmpty() && store.model(owner, provider).isEmpty()) store.save(owner, provider, models.get(0), "");
                    host.selectionChanged();
                    host.refreshCurrentScreen();
                    toast(models.isEmpty() ? "Каталог пуст: укажите модель вручную в выборе модели" : "Моделей: " + models.size());
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> toast("Каталог не загружен: " + (error.getMessage() == null ? "ошибка сети" : error.getMessage())));
            }
        });
    }

    private void balance(String provider, PoolPolicy.Slot slot) {
        toast("Запрашиваем баланс…");
        io.execute(() -> {
            String result;
            try {
                result = QuotaProbe.fetch(provider, pool().secret(owner, provider, slot.id));
            } catch (Exception error) {
                result = "Не удалось получить баланс: " + (error.getMessage() == null ? "ошибка" : error.getMessage());
            }
            final String text = result;
            activity.runOnUiThread(() -> new AlertDialog.Builder(activity).setTitle("Баланс · " + slot.label)
                    .setMessage(text).setPositiveButton("ОК", null).show());
        });
    }

    private void confirm(String title, String message, Runnable action) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                .setPositiveButton("Да", (d, w) -> { if (host.isCurrent(owner)) action.run(); })
                .setNegativeButton("Отмена", null).show();
    }

    private List<String> connectedServices() {
        List<String> result = new ArrayList<>();
        for (String provider : OmniChatConnectionStore.supported().keySet()) {
            if (!OmniChatConnectionStore.poolable(provider)) continue;
            store.hasKey(owner, provider); // imports a pre-pool key once
            if (!pool().slots(owner, provider).isEmpty()) result.add(provider);
        }
        return result;
    }

    // ================================================================== formatting

    private View quotaBlock(PoolPolicy.Summary sum) {
        if (!sum.hasTokenQuota() && !sum.hasRequestQuota()) return null;
        LinearLayout block = column();
        if (sum.hasTokenQuota()) block.addView(limitLine("Общая квота токенов · осталось " + PoolPolicy.compact(sum.remainingTokens()),
                sum.usedOfQuotaTokens, sum.quotaTokens));
        if (sum.hasRequestQuota()) block.addView(limitLine("Общая квота запросов · осталось " + sum.remainingRequests(),
                sum.usedOfQuotaRequests, sum.quotaRequests), params(-1, -2, 0, sum.hasTokenQuota() ? 8 : 0, 0, 0));
        return block;
    }

    private View limitLine(String title, long used, long limit) {
        double fraction = limit <= 0 ? 0 : Math.min(1.0, (double) used / limit);
        LinearLayout line = column();
        LinearLayout head = new LinearLayout(activity);
        head.addView(label(title, 12, palette.muted, false), params(0, -2, 1, 0, 0, 0, 0));
        head.addView(label(PoolPolicy.compact(used) + " / " + PoolPolicy.compact(limit), 12, palette.text, true));
        line.addView(head);
        int color = fraction >= 1.0 ? danger : fraction >= 0.85 ? warn : palette.green;
        LinearLayout track = new LinearLayout(activity);
        track.setBackground(round(blend(palette.card, palette.muted, palette.dark ? 0.35f : 0.22f), 6, 0, 0));
        View fill = new View(activity);
        fill.setBackground(round(color, 6, 0, 0));
        float weight = (float) Math.max(0.02, fraction);
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(6), weight));
        track.addView(new View(activity), new LinearLayout.LayoutParams(0, dp(6), 1f - weight));
        line.addView(track, params(-1, 6, 0, 4, 0, 0));
        return line;
    }

    private View statLine(String key, String value) {
        LinearLayout row = new LinearLayout(activity);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(label(key, 13, palette.muted, false), params(0, -2, 1, 0, 0, 0, 0));
        TextView v = label(value, 13, palette.text, true);
        v.setGravity(Gravity.END);
        row.addView(v, params(0, -2, 2, 8, 0, 0, 0));
        return row;
    }

    private String statusText(PoolPolicy.Slot slot, long now) {
        switch (PoolPolicy.status(slot, now)) {
            case OFF: return "Выключен";
            case EXHAUSTED: return "Квота исчерпана";
            case LIMIT: return "Достигнут лимит";
            case AUTH_ERROR: return "Ключ отклонён";
            case COOLDOWN: return "Пауза " + Math.max(1, (slot.cooldownUntil - now + 999) / 1000) + " с";
            default: return "Активен";
        }
    }

    private int statusColor(PoolPolicy.Slot slot, long now) {
        switch (PoolPolicy.status(slot, now)) {
            case OFF: return palette.muted;
            case COOLDOWN: return warn;
            case OK: return palette.green;
            default: return danger;
        }
    }

    private static String ago(long time, long now) {
        long s = Math.max(0, (now - time) / 1000);
        if (s < 60) return "только что";
        if (s < 3600) return (s / 60) + " мин назад";
        if (s < 86400) return (s / 3600) + " ч назад";
        return (s / 86400) + " дн назад";
    }

    private static String plural(int n, String one, String few, String many) {
        int m = n % 100, d = n % 10;
        if (m >= 11 && m <= 14) return many;
        if (d == 1) return one;
        if (d >= 2 && d <= 4) return few;
        return many;
    }

    private static String protocolName(String id) {
        int index = indexOf(PROTOCOL_IDS, id);
        return PROTOCOL_NAMES[Math.max(0, index)];
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return 0;
    }

    /** "500", "1k", "2.5m" → count; empty means 0 (no limit). */
    static long parseCount(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace(",", ".");
        if (t.isEmpty()) return 0;
        double factor = 1;
        if (t.endsWith("k") || t.endsWith("к")) { factor = 1e3; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("m") || t.endsWith("м")) { factor = 1e6; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("b") || t.endsWith("г")) { factor = 1e9; t = t.substring(0, t.length() - 1); }
        try {
            double value = Double.parseDouble(t) * factor;
            if (value < 0 || value > 1e13) throw new IllegalArgumentException("Недопустимая квота");
            return Math.round(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Квота — число, например 500, 100k или 2m");
        }
    }

    // ================================================================== widgets

    private LinearLayout card(float tint) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(round(blend(palette.card, palette.green, tint), 16, palette.border, 1));
        return card;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private TextView heading(String text) {
        TextView heading = label(text.toUpperCase(new Locale("ru")), 12, palette.muted, true);
        heading.setLetterSpacing(0.06f);
        heading.setPadding(dp(6), dp(8), dp(6), dp(8));
        return heading;
    }

    private TextView fieldLabel(String text) {
        TextView label = label(text, 12, palette.muted, true);
        label.setPadding(dp(2), 0, 0, dp(4));
        return label;
    }

    private EditText field(String hint, String value, boolean secret, boolean number) {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(14);
        field.setTextColor(palette.text);
        field.setHintTextColor(palette.muted);
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setBackground(round(palette.subtle, 12, palette.border, 1));
        if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else if (number) field.setInputType(InputType.TYPE_CLASS_TEXT);
        else field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return field;
    }

    private TextView button(String text, boolean primary, View.OnClickListener action) {
        TextView button = label(text, 14, primary ? Color.WHITE : palette.green, true);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(primary ? round(palette.green, 12, 0, 0) : round(blend(palette.card, palette.green, 0.10f), 12, 0, 0));
        button.setOnClickListener(action);
        ripple(button);
        return button;
    }

    private TextView dangerButton(String text, View.OnClickListener action) {
        TextView button = label(text, 14, danger, true);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(round(blend(palette.card, danger, 0.10f), 12, 0, 0));
        button.setOnClickListener(action);
        ripple(button);
        return button;
    }

    private TextView textButton(String text, View.OnClickListener action) {
        TextView button = label(text, 13, palette.green, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(4), 0);
        button.setOnClickListener(action);
        return button;
    }

    private TextView pill(String text, int color) {
        TextView pill = label(text, 11, color, true);
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        pill.setPadding(dp(9), dp(3), dp(9), dp(3));
        pill.setBackground(round(blend(palette.card, color, palette.dark ? 0.26f : 0.14f), 12, 0, 0));
        return pill;
    }

    private View initialTile(String name, int color) {
        TextView tile = label(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT), 18, color, true);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(round(blend(palette.card, color, palette.dark ? 0.24f : 0.13f), 12, 0, 0));
        return tile;
    }

    private Switch toggle(boolean checked, java.util.function.Consumer<Boolean> change) {
        Switch toggle = new Switch(activity);
        toggle.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setThumbTintList(new ColorStateList(states, new int[]{palette.green, palette.dark ? Color.rgb(190, 200, 196) : Color.WHITE}));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{blend(palette.card, palette.green, 0.45f),
                blend(palette.card, palette.muted, palette.dark ? 0.45f : 0.35f)}));
        toggle.setOnCheckedChangeListener((view, on) -> change.accept(on));
        return toggle;
    }

    /** Single-choice chips in a horizontally scrollable row. */
    private View chips(String[] names, int selected, java.util.function.IntConsumer change) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(activity);
        final List<TextView> views = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView chip = label(names[i], 13, palette.text, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setOnClickListener(v -> {
                change.accept(index);
                for (int j = 0; j < views.size(); j++) styleChip(views.get(j), j == index);
            });
            views.add(chip);
            styleChip(chip, i == selected);
            row.addView(chip, params(-2, 40, 0, i == 0 ? 0 : 8, 0, 0, 0));
        }
        scroll.addView(row);
        return scroll;
    }

    private void styleChip(TextView chip, boolean on) {
        chip.setTextColor(on ? Color.WHITE : palette.text);
        chip.setBackground(on ? round(palette.green, 12, 0, 0) : round(palette.subtle, 12, palette.border, 1));
    }

    private void ripple(View view) {
        TypedValue value = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true) && value.resourceId != 0)
            view.setForeground(activity.getDrawable(value.resourceId));
        view.setClickable(true);
        view.setFocusable(true);
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(sp);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT_BOLD);
        return label;
    }

    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }

    /** Layout params: width/height in dp (-1 match, -2 wrap), weight, then left/top/right/bottom margins in dp. */
    private LinearLayout.LayoutParams params(int width, int height, float weight, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width > 0 ? dp(width) : width, height > 0 ? dp(height) : height, weight);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    /** Shorter form: width, height, then left/top/right/bottom margins with zero weight. */
    private LinearLayout.LayoutParams params(int width, int height, int left, int top, int right, int bottom) {
        return params(width, height, 0, left, top, right, bottom);
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
