package app.githubble.mobile.controller;

import android.content.SharedPreferences;
import app.githubble.mobile.agent.OmniChatChatClient;
import app.githubble.mobile.agent.OmniChatConnectionStore;
import app.githubble.mobile.translation.TranslationCache;
import app.githubble.mobile.translation.TranslationEngine;
import app.githubble.mobile.translation.TranslationRoute;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Translation through the AI connections the user already has: no endpoints, keys or models to type in. */
public final class TranslationController {
    public static final String SERVICE_PREF = "translator_service", AUTO_PREF = "translator_auto", SCOPES_PREF = "translator_scopes";
    public static final String[] SCOPE_IDS = {"description", "readme", "discussions", "commits"};
    public static final String[] SCOPE_TITLES = {"Описание репозитория", "README", "Issues, PR, релизы", "Сообщения коммитов"};

    public static final class Candidate {
        public final String provider, model, title;
        Candidate(String provider, String model, String title) { this.provider = provider; this.model = model; this.title = title; }
    }

    private final SharedPreferences prefs;
    private final TranslationCache cache;
    private final TranslationEngine engine;
    private final OmniChatConnectionStore store;
    private final OmniChatChatClient client;
    private final Supplier<String> owner;
    private volatile String lastWorking = "";

    public TranslationController(SharedPreferences prefs, TranslationCache cache, TranslationEngine engine,
                                 OmniChatConnectionStore store, OmniChatChatClient client, Supplier<String> owner) {
        this.prefs = prefs; this.cache = cache; this.engine = engine; this.store = store; this.client = client; this.owner = owner;
    }

    public TranslationCache cache() { return cache; }
    public TranslationEngine engine() { return engine; }

    public String service() { return prefs.getString(SERVICE_PREF, TranslationRoute.AUTO); }
    public void setService(String id) { prefs.edit().putString(SERVICE_PREF, id == null || id.isEmpty() ? TranslationRoute.AUTO : id).apply(); }

    public boolean autoEnabled() { return prefs.getBoolean(AUTO_PREF, false); }
    public boolean scopeEnabled(String scope) { return prefs.getBoolean("translator_scope_" + scope, true); }
    public void setScope(String scope, boolean on) { prefs.edit().putBoolean("translator_scope_" + scope, on).apply(); }

    /** Connected services in the order they will be tried. */
    public List<Candidate> candidates() { return candidates(service()); }

    /** Every connected service, ignoring the chosen one (for the picker). */
    public List<Candidate> connected() { return candidates(TranslationRoute.AUTO); }

    private List<Candidate> candidates(String preferred) {
        String account = owner.get();
        List<String> configured = new ArrayList<>();
        for (String id : OmniChatConnectionStore.supported().keySet()) {
            try { if (store.isConfigured(account, id)) configured.add(id); } catch (RuntimeException skip) { /* foreign provider */ }
        }
        String selected = "";
        try { selected = store.selectedProvider(account); } catch (RuntimeException ignored) { }
        List<String> ordered = TranslationRoute.order(preferred, configured, selected);
        if (!lastWorking.isEmpty() && ordered.remove(lastWorking)) ordered.add(0, lastWorking);
        List<Candidate> result = new ArrayList<>();
        for (String id : ordered) {
            String model = TranslationRoute.pickModel(store.model(account, id), store.models(account, id));
            if (!model.isEmpty()) result.add(new Candidate(id, model, store.displayName(id)));
        }
        return result;
    }

    public boolean available() { return !candidates().isEmpty(); }

    /** Short settings label: "Авто · ChatGPT" or the chosen service. */
    public String serviceLabel() {
        List<Candidate> list = candidates();
        boolean auto = TranslationRoute.AUTO.equals(service());
        if (list.isEmpty()) return auto ? "Авто · нет подключённых моделей" : "Сервис не подключён";
        return (auto ? "Авто · " : "") + list.get(0).title;
    }

    /** One completion, trying the next connected service when one is rejected, empty or out of quota. */
    public TranslationEngine.Completer completer() {
        final String account = owner.get();
        return prompt -> {
            List<Candidate> list = candidates();
            if (list.isEmpty()) throw new IllegalStateException("Подключите ChatGPT, Grok или другую модель в «Подключениях»");
            Exception last = null;
            for (Candidate c : list) {
                try {
                    JSONArray transcript = new JSONArray().put(new JSONObject().put("role", "user").put("body", prompt));
                    String answer = client.completeForAccount(account, c.provider, c.model, store.credential(account, c.provider),
                            transcript, "Translation", "");
                    OmniChatChatClient.takeUsage();
                    if (answer != null && !answer.trim().isEmpty()) { lastWorking = c.provider; return answer; }
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException || Thread.currentThread().isInterrupted()) throw failure;
                    last = failure;
                }
            }
            throw last == null ? new IllegalStateException("Модель вернула пустой ответ") : last;
        };
    }
}
