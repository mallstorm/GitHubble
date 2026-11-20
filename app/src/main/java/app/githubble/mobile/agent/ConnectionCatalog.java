package app.githubble.mobile.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the two connection sections:
 * <ul>
 *   <li><b>API-ключи и endpoint</b> — every service the user connects with a key or a custom endpoint;</li>
 *   <li><b>Аккаунты и подписки</b> — sign-in connections (OAuth in the browser, device code) and the
 *       external subscription proxy. The former "Subscription Proxy" list lives here, merged with the
 *       identical account connections.</li>
 * </ul>
 */
public final class ConnectionCatalog {
    private ConnectionCatalog() {}

    /** How the user signs in: one tap to the provider page, or a code typed on another page. */
    public static final String BROWSER = "browser", DEVICE = "device", ENDPOINT = "endpoint";

    public static final class Account {
        public final String id, title, subtitle, method;
        public final boolean freeModels;
        Account(String id, String title, String subtitle, String method, boolean freeModels) {
            this.id = id; this.title = title; this.subtitle = subtitle; this.method = method; this.freeModels = freeModels;
        }
    }

    private static final List<Account> ACCOUNTS;
    private static final Map<String, String> LEGACY = new LinkedHashMap<>();
    static {
        List<Account> list = new ArrayList<>();
        // Free models first: these connect in one tap and need no card.
        list.add(new Account("kilo-account", "Kilo Code", "Вход в одно касание · бесплатные модели", DEVICE, true));
        list.add(new Account("openrouter-account", "OpenRouter", "Вход через браузер · есть бесплатные модели, Gemini и другие", BROWSER, true));
        list.add(new Account(OAuthSubscriptions.CLINE, "Cline", "Код-вход · бесплатные модели и Cline Pass", DEVICE, true));
        list.add(new Account("github-copilot", "GitHub Copilot", "Код-вход · модели вашей подписки Copilot", DEVICE, false));
        list.add(new Account("chatgpt-plan", "ChatGPT · вход в браузере", "Plus / Pro / Team · без API-ключа", BROWSER, false));
        list.add(new Account(OAuthSubscriptions.OPENAI, "ChatGPT · Codex", "Код-вход · модели Codex по подписке ChatGPT", DEVICE, false));
        list.add(new Account("claude-subscription", "Claude", "Pro / Max · вход через браузер", BROWSER, false));
        list.add(new Account(OAuthSubscriptions.GROK, "Grok (xAI)", "SuperGrok / X Premium+ · код-вход", DEVICE, false));
        list.add(new Account(OAuthSubscriptions.KIMI, "Kimi Code", "Код-вход · подписка Kimi", DEVICE, false));
        list.add(new Account("proxy-external", "Свой прокси подписок", "CLIProxyAPI и подобные · свой endpoint", ENDPOINT, false));
        ACCOUNTS = Collections.unmodifiableList(list);

        // alias -> canonical id (null = discontinued, dropped). Same OAuth client and wire = one connection.
        LEGACY.put("proxy-chatgpt", "chatgpt-plan");
        LEGACY.put("proxy-claude", "claude-subscription");
        LEGACY.put("proxy-grok", OAuthSubscriptions.GROK);
        LEGACY.put("proxy-nvidia", "nvidia");
        LEGACY.put("proxy-gemini", null);
        LEGACY.put("gemini-antigravity", null);
    }

    public static List<Account> accounts() { return ACCOUNTS; }

    public static boolean isAccount(String id) {
        if (id == null) return false;
        for (Account a : ACCOUNTS) if (a.id.equals(id)) return true;
        return false;
    }

    public static Account account(String id) {
        for (Account a : ACCOUNTS) if (a.id.equals(id)) return a;
        return null;
    }

    public static Map<String, String> legacyAliases() { return Collections.unmodifiableMap(LEGACY); }

    /** proxy-nvidia held plain API keys, everything else held OAuth sessions. */
    public static boolean isApiAlias(String alias) { return "proxy-nvidia".equals(alias); }

    // ---------------------------------------------------------------- API services: where to get a key

    private static final Map<String, String[]> KEYS = new LinkedHashMap<>();
    static {
        // id -> {page where the key is created, short prefix of the key (for clipboard detection), free-tier note}
        KEYS.put("gemini", new String[]{"https://aistudio.google.com/apikey", "AIza", "Бесплатный тариф: ключ из Google AI Studio"});
        KEYS.put("openai", new String[]{"https://platform.openai.com/api-keys", "sk-", ""});
        KEYS.put("anthropic", new String[]{"https://console.anthropic.com/settings/keys", "sk-ant-", ""});
        KEYS.put("openrouter", new String[]{"https://openrouter.ai/settings/keys", "sk-or-", "Есть бесплатные модели (:free)"});
        KEYS.put("groq", new String[]{"https://console.groq.com/keys", "gsk_", "Бесплатный тариф с лимитами"});
        KEYS.put("deepseek", new String[]{"https://platform.deepseek.com/api_keys", "sk-", ""});
        KEYS.put("xai", new String[]{"https://console.x.ai", "xai-", ""});
        KEYS.put("mistral", new String[]{"https://console.mistral.ai/api-keys", "", "Бесплатный тариф Experiment"});
        KEYS.put("together", new String[]{"https://api.together.ai/settings/api-keys", "", ""});
        KEYS.put("cerebras", new String[]{"https://cloud.cerebras.ai/platform", "csk-", "Бесплатный тариф с лимитами"});
        KEYS.put("nvidia", new String[]{"https://build.nvidia.com/settings/api-keys", "nvapi-", "Бесплатные кредиты NVIDIA NIM"});
        KEYS.put("huggingface", new String[]{"https://huggingface.co/settings/tokens", "hf_", "Бесплатные кредиты Inference Providers"});
        KEYS.put("github-models", new String[]{"https://github.com/settings/personal-access-tokens/new", "github_pat_", "Бесплатно для аккаунта GitHub: нужен доступ Models → Read"});
        KEYS.put("cohere", new String[]{"https://dashboard.cohere.com/api-keys", "", "Пробный бесплатный ключ"});
        KEYS.put("cloudflare", new String[]{"https://dash.cloudflare.com/profile/api-tokens", "", "Workers AI: бесплатная квота"});
        KEYS.put("sambanova", new String[]{"https://cloud.sambanova.ai/apis", "", "Бесплатный тариф"});
        KEYS.put("zai", new String[]{"https://z.ai/manage-apikey/apikey-list", "", ""});
    }

    /** Page where the user creates a key for the service, or null. */
    public static String keyPage(String service) { String[] e = KEYS.get(service); return e == null ? null : e[0]; }
    /** Leading characters a key of this service starts with (clipboard detection), or "". */
    public static String keyPrefix(String service) { String[] e = KEYS.get(service); return e == null ? "" : e[1]; }
    /** Short note about a free tier, or "". */
    public static String freeNote(String service) { String[] e = KEYS.get(service); return e == null ? "" : e[2]; }
    public static boolean hasFreeTier(String service) { return !freeNote(service).isEmpty(); }
}
