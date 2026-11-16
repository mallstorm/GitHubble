package app.githubble.mobile.network;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Endpoint check: given a service address (or only the service name) and a key, finds every base URL
 * and wire protocol under which the model list answers, so the endpoint field can be filled in
 * automatically. The key only ever goes to hosts the user typed or to the known addresses of the
 * service they are connecting; redirects are never followed.
 */
public final class EndpointProbe {
    private EndpointProbe() {}

    /** One HTTP exchange; implemented over the app's own network stack in production and faked in tests. */
    public interface Fetcher {
        Reply send(String method, String url, Map<String, String> headers, String body) throws Exception;
    }

    public static final class Reply {
        public final int status;
        public final String body, server;
        public final long millis;
        public Reply(int status, String body) { this(status, body, "", 0); }
        public Reply(int status, String body, String server, long millis) {
            this.status = status; this.body = body == null ? "" : body; this.server = server == null ? "" : server; this.millis = millis;
        }
    }

    public enum Kind { OK, AUTH, RATE, MISS }

    public static final class Hit {
        public final EndpointDirectory.Candidate candidate;
        public final Kind kind;
        public final int status;
        public final List<String> models;
        public final int order;
        /** Response time of the model-list request. */
        public final long millis;
        /** Recognised gateway or server software ("LiteLLM", "Ollama", ...), or "". */
        public final String gateway;
        Hit(EndpointDirectory.Candidate candidate, Kind kind, int status, List<String> models, int order, long millis, String gateway) {
            this.candidate = candidate; this.kind = kind; this.status = status; this.models = models; this.order = order;
            this.millis = millis; this.gateway = gateway;
        }
    }

    public static final class Report {
        public final int tried;
        /** Working endpoints first (best first), then endpoints that answered but refused the key. */
        public final List<Hit> hits;
        public final boolean timedOut;
        /** Readable reason when nothing answered at all (host not found, TLS, timeout), else "". */
        public final String failure;
        Report(int tried, List<Hit> hits, boolean timedOut, String failure) {
            this.tried = tried; this.hits = hits; this.timedOut = timedOut; this.failure = failure;
        }

        public List<Hit> working() { return filter(Kind.OK); }
        /** Addresses that answered but refused the key; one entry per base URL. */
        public List<Hit> rejected() {
            List<Hit> out = new ArrayList<>();
            Set<String> bases = new LinkedHashSet<>();
            for (Hit hit : filter(Kind.AUTH)) if (bases.add(hit.candidate.base)) out.add(hit);
            return out;
        }
        public Hit best() { List<Hit> ok = working(); return ok.isEmpty() ? null : ok.get(0); }
        private List<Hit> filter(Kind kind) {
            List<Hit> out = new ArrayList<>();
            for (Hit hit : hits) if (hit.kind == kind) out.add(hit);
            return out;
        }
    }

    private static final String[] SUFFIXES = {"/v1", "", "/api/v1", "/openai/v1", "/api/openai/v1", "/v1beta/openai",
            "/api/v3", "/api/v4", "/v2", "/openai", "/compatible-mode/v1", "/anthropic", "/anthropic/v1", "/api/anthropic", "/v1beta"};
    private static final String[] TAIL_MISTAKES = {"/chat/completions", "/completions", "/responses", "/messages", "/models", "/embeddings"};
    private static final String[] UTILITY_PATHS = {"/check", "/health", "/healthz", "/ready", "/readiness", "/status",
            "/dashboard", "/console", "/admin", "/login", "/panel", "/docs", "/swagger", "/token", "/pricing", "/playground"};
    private static final int MAX_CANDIDATES = 40;

    // ------------------------------------------------------------------ candidates

    /**
     * Cleans what the user typed: adds https, strips a pasted request path (/chat/completions) and
     * trailing slashes. Returns "" when the value cannot be an API address.
     */
    public static String normalize(String typed) {
        String value = typed == null ? "" : typed.trim();
        if (value.isEmpty() || value.indexOf(' ') >= 0) return "";
        if (!value.contains("://")) value = (looksLocal(value) ? "http://" : "https://") + value;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return "";
            boolean local = looksLocal(host);
            if (!scheme.equals("https") && !(scheme.equals("http") && local)) return "";
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            boolean stripped = true;
            while (stripped) {
                stripped = false;
                while (path.endsWith("/")) { path = path.substring(0, path.length() - 1); stripped = true; }
                for (String tail : TAIL_MISTAKES) {
                    if (path.toLowerCase(Locale.ROOT).endsWith(tail)) { path = path.substring(0, path.length() - tail.length()); stripped = true; }
                }
            }
            if (isUtilityPath(path)) path = "";
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + port + path;
        } catch (java.net.URISyntaxException invalid) {
            return "";
        }
    }

    /** Dashboards, health pages and logins are not API roots: pasting them means "this host". */
    private static boolean isUtilityPath(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        for (String utility : UTILITY_PATHS) if (p.equals(utility) || p.startsWith(utility + "/")) return true;
        return false;
    }

    private static boolean looksLocal(String hostOrValue) {
        String h = hostOrValue.toLowerCase(Locale.ROOT);
        return h.startsWith("localhost") || h.startsWith("127.") || h.startsWith("[::1]") || h.equals("::1");
    }

    static String protocolForPath(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.contains("anthropic")) return EndpointDirectory.ANTHROPIC;
        if (p.contains("v1beta") && !p.contains("openai")) return EndpointDirectory.GEMINI;
        return EndpointDirectory.CHAT;
    }

    private static String originOf(String base) {
        int scheme = base.indexOf("://");
        int slash = base.indexOf('/', scheme + 3);
        return slash < 0 ? base : base.substring(0, slash);
    }

    private static String pathOf(String base) {
        String origin = originOf(base);
        return base.substring(origin.length());
    }

    /**
     * Addresses to try, in order of likelihood. A typed address yields itself, its parent paths and the
     * usual API suffixes on the same host; the known addresses of the service are added only when
     * nothing was typed or the typed host is one of them (the key is never sent to another service).
     */
    public static List<EndpointDirectory.Candidate> candidates(String provider, String typed) {
        LinkedHashSet<EndpointDirectory.Candidate> out = new LinkedHashSet<>();
        String base = normalize(typed);
        List<EndpointDirectory.Candidate> known = EndpointDirectory.candidates(provider);
        boolean typedIsKnown = false;
        for (EndpointDirectory.Candidate c : known) if (!base.isEmpty() && originOf(c.base).equals(originOf(base))) typedIsKnown = true;

        if (!base.isEmpty()) {
            String origin = originOf(base), path = pathOf(base);
            out.add(new EndpointDirectory.Candidate(base, protocolForPath(path)));
            if (EndpointDirectory.CHAT.equals(protocolForPath(path))) out.add(new EndpointDirectory.Candidate(base, EndpointDirectory.ANTHROPIC));
            String walk = path;
            while (walk.contains("/")) {
                walk = walk.substring(0, walk.lastIndexOf('/'));
                out.add(new EndpointDirectory.Candidate(origin + walk, protocolForPath(walk)));
            }
            for (String suffix : SUFFIXES) out.add(new EndpointDirectory.Candidate(origin + suffix, protocolForPath(suffix)));
            for (String suffix : new String[] {"/v1", ""}) out.add(new EndpointDirectory.Candidate(origin + suffix, EndpointDirectory.ANTHROPIC));
            if (looksLocal(origin.substring(origin.indexOf("://") + 3))) out.add(new EndpointDirectory.Candidate(origin, EndpointDirectory.OLLAMA));
        }
        if (base.isEmpty() || typedIsKnown) out.addAll(known);
        List<EndpointDirectory.Candidate> list = new ArrayList<>(out);
        return list.size() > MAX_CANDIDATES ? new ArrayList<>(list.subList(0, MAX_CANDIDATES)) : list;
    }

    // ------------------------------------------------------------------ classification

    static String probeUrl(EndpointDirectory.Candidate c) {
        return EndpointDirectory.OLLAMA.equals(c.protocol) ? c.base + "/api/tags" : c.base + "/models";
    }

    static Map<String, String> headers(EndpointDirectory.Candidate c, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("User-Agent", "GitHubble-AI-Workspace/1");
        if (key == null || key.isEmpty()) return h;
        if (EndpointDirectory.ANTHROPIC.equals(c.protocol)) {
            h.put("x-api-key", key);
            h.put("anthropic-version", "2023-06-01");
        } else if (EndpointDirectory.GEMINI.equals(c.protocol)) {
            h.put("x-goog-api-key", key);
        } else if (!EndpointDirectory.OLLAMA.equals(c.protocol)) {
            h.put("Authorization", "Bearer " + key);
            if (c.base.contains("azure")) h.put("api-key", key);
        }
        return h;
    }

    /** Judges one answer. */
    static Hit evaluate(EndpointDirectory.Candidate c, Reply reply, int order) {
        int s = reply.status;
        String body = reply.body.trim();
        boolean json = body.startsWith("{") || body.startsWith("[");
        String gateway = gatewayOf(reply);
        List<String> none = Collections.<String>emptyList();
        if (s >= 200 && s < 300) {
            List<String> ids = modelIds(body, c.protocol);
            if (ids != null) return new Hit(c, Kind.OK, s, ids, order, reply.millis, gateway);
            return new Hit(c, Kind.MISS, s, none, order, reply.millis, gateway);
        }
        if ((s == 401 || s == 403) && json) return new Hit(c, Kind.AUTH, s, none, order, reply.millis, gateway);
        if (s == 429 && json) return new Hit(c, Kind.RATE, s, none, order, reply.millis, gateway);
        return new Hit(c, Kind.MISS, s, none, order, reply.millis, gateway);
    }

    /** Recognises well-known gateway software from the Server header and body markers. */
    static String gatewayOf(Reply reply) {
        String text = (reply.server + " " + (reply.body.length() > 4000 ? reply.body.substring(0, 4000) : reply.body)).toLowerCase(Locale.ROOT);
        if (text.contains("litellm")) return "LiteLLM";
        if (text.contains("new-api") || text.contains("new api")) return "New API";
        if (text.contains("one-api") || text.contains("one api")) return "One API";
        if (text.contains("lm studio") || text.contains("lmstudio")) return "LM Studio";
        if (text.contains("ollama")) return "Ollama";
        if (text.contains("vllm")) return "vLLM";
        if (text.contains("localai")) return "LocalAI";
        if (text.contains("openrouter")) return "OpenRouter";
        if (text.contains("sub2api") || text.contains("claude-relay")) return "Relay";
        return "";
    }

    /** Model ids when the body is a model list of the wire's shape, otherwise null. */
    static List<String> modelIds(String body, String protocol) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            JSONArray items = null;
            String trimmed = body.trim();
            if (trimmed.startsWith("[")) {
                items = new JSONArray(trimmed);
            } else if (trimmed.startsWith("{")) {
                JSONObject root = new JSONObject(trimmed);
                items = root.optJSONArray("data");
                if (items == null) items = root.optJSONArray("models");
                if (items == null) items = root.optJSONArray("result");
            }
            if (items == null) return null;
            List<String> ids = new ArrayList<>();
            boolean anthropicShape = false;
            for (int i = 0; i < items.length() && ids.size() < 3000; i++) {
                Object item = items.opt(i);
                String id = "";
                if (item instanceof JSONObject) {
                    JSONObject o = (JSONObject) item;
                    id = o.optString("id", "");
                    if (id.isEmpty()) id = o.optString("name", "");
                    if (id.isEmpty()) id = o.optString("model", "");
                    if (o.has("display_name") || "model".equals(o.optString("type"))) anthropicShape = true;
                } else if (item instanceof String) {
                    id = (String) item;
                }
                if (id.startsWith("models/")) id = id.substring(7);
                if (!id.isEmpty()) ids.add(id);
            }
            if (EndpointDirectory.ANTHROPIC.equals(protocol) && !anthropicShape) return null;
            if (EndpointDirectory.GEMINI.equals(protocol) && !trimmed.contains("\"models\"")) return null;
            if (EndpointDirectory.OLLAMA.equals(protocol) && !trimmed.contains("\"models\"")) return null;
            return ids;
        } catch (JSONException notJson) {
            return null;
        }
    }

    // ------------------------------------------------------------------ run

    /**
     * Probes all candidates in parallel. {@code key} may be empty for keyless servers. The preferred wire
     * is that of the service's known default endpoint (OpenAI chat when unknown).
     */
    public static Report run(String provider, String typed, String key, Fetcher fetcher, ExecutorService pool, long deadlineMs) {
        final List<EndpointDirectory.Candidate> list = candidates(provider, typed);
        final String secret = key == null ? "" : key.trim();
        final List<String> failures = Collections.synchronizedList(new ArrayList<String>());
        List<Callable<Hit>> jobs = new LinkedList<>();
        for (int i = 0; i < list.size(); i++) {
            final int order = i;
            final EndpointDirectory.Candidate c = list.get(i);
            jobs.add(() -> {
                try {
                    return evaluate(c, fetcher.send("GET", probeUrl(c), headers(c, secret), null), order);
                } catch (Exception failed) {
                    failures.add(describeFailure(failed));
                    return new Hit(c, Kind.MISS, 0, Collections.<String>emptyList(), order, 0, "");
                }
            });
        }
        boolean timedOut = false;
        List<Hit> all = new ArrayList<>();
        try {
            List<Future<Hit>> futures = pool.invokeAll(jobs, Math.max(1000, deadlineMs), TimeUnit.MILLISECONDS);
            for (Future<Hit> f : futures) {
                if (f.isCancelled()) { timedOut = true; continue; }
                try { all.add(f.get()); } catch (Exception ignored) { timedOut = true; }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            timedOut = true;
        }
        final String preferred = EndpointDirectory.protocol(provider).isEmpty() ? EndpointDirectory.CHAT : EndpointDirectory.protocol(provider);
        // Keep the best answer per (base, wire) pair, then sort: working first, preferred wire first, then by candidate order.
        List<Hit> kept = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Hit h : all) if ((h.kind == Kind.OK || h.kind == Kind.AUTH) && seen.add(h.candidate.base + "|" + h.candidate.protocol)) kept.add(h);
        Collections.sort(kept, (a, b) -> {
            int ka = a.kind == Kind.OK ? 0 : 1, kb = b.kind == Kind.OK ? 0 : 1;
            if (ka != kb) return ka - kb;
            boolean pa = wireClass(a.candidate.protocol).equals(wireClass(preferred)), pb = wireClass(b.candidate.protocol).equals(wireClass(preferred));
            if (pa != pb) return pa ? -1 : 1;
            return a.order - b.order;
        });
        String failure = "";
        if (kept.isEmpty() && !failures.isEmpty() && failures.size() >= list.size() / 2) {
            // Report the most common transport failure: DNS, TLS, refused or timeout.
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String f : new ArrayList<>(failures)) counts.put(f, counts.containsKey(f) ? counts.get(f) + 1 : 1);
            int top = 0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) if (e.getValue() > top) { top = e.getValue(); failure = e.getKey(); }
        }
        return new Report(list.size(), Collections.unmodifiableList(kept), timedOut, failure);
    }

    private static String wireClass(String protocol) {
        return protocol.startsWith("openai") ? "openai" : protocol;
    }

    // ------------------------------------------------------------------ transport failures

    /** Short readable reason for a transport exception; never includes request headers or the key. */
    static String describeFailure(Throwable error) {
        String type = error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String text = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        if (type.contains("unknownhost") || text.contains("unable to resolve host") || text.contains("no address associated")) return "Хост не найден (DNS)";
        if (type.contains("timeout") || text.contains("timed out")) return "Сервер не отвечает (таймаут)";
        if (type.contains("ssl") || type.contains("certificate") || text.contains("trust anchor") || text.contains("handshake")) return "Ошибка TLS-сертификата";
        if (text.contains("refused") || type.contains("connectexception")) return "Соединение отклонено";
        if (text.contains("cleartext")) return "HTTP без шифрования запрещён";
        return "Сеть недоступна";
    }

    // ------------------------------------------------------------------ test request

    public static final class Ping {
        public final boolean ok;
        public final int status;
        public final long millis;
        public final String model, message;
        Ping(boolean ok, int status, long millis, String model, String message) {
            this.ok = ok; this.status = status; this.millis = millis; this.model = model; this.message = message;
        }
    }

    /** Cheapest-looking model of a catalog: small, fast and flash-class names first. */
    public static String pickPingModel(List<String> models) {
        if (models == null || models.isEmpty()) return "";
        String[] preferred = {"mini", "flash", "haiku", "nano", "lite", "small", "turbo", "instruct", "chat"};
        for (String hint : preferred) {
            for (String model : models) {
                String m = model.toLowerCase(Locale.ROOT);
                if (m.contains("embed") || m.contains("image") || m.contains("tts") || m.contains("whisper") || m.contains("moderation")) continue;
                for (String token : m.split("[^a-z0-9]+")) if (token.equals(hint)) return model;
            }
        }
        return models.get(0);
    }

    /**
     * One-token chat request: proves that generation (not just the model list) works with this key,
     * and turns the usual refusals into plain language.
     */
    public static Ping ping(EndpointDirectory.Candidate c, String key, String model, Fetcher fetcher) {
        String secret = key == null ? "" : key.trim();
        Map<String, String> h = headers(c, secret);
        h.put("Content-Type", "application/json");
        String url, body;
        try {
            if (EndpointDirectory.ANTHROPIC.equals(c.protocol)) {
                url = c.base + "/messages";
                body = new JSONObject().put("model", model).put("max_tokens", 1)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "ping"))).toString();
            } else if (EndpointDirectory.GEMINI.equals(c.protocol)) {
                url = c.base + "/models/" + model + ":generateContent";
                body = new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", "ping")))))
                        .put("generationConfig", new JSONObject().put("maxOutputTokens", 1)).toString();
            } else if (EndpointDirectory.OLLAMA.equals(c.protocol)) {
                url = c.base + "/api/chat";
                body = new JSONObject().put("model", model).put("stream", false)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "ping"))).toString();
            } else {
                url = c.base + "/chat/completions";
                body = new JSONObject().put("model", model).put("max_tokens", 1)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "ping"))).toString();
            }
        } catch (JSONException impossible) {
            return new Ping(false, 0, 0, model, "Не удалось собрать запрос");
        }
        try {
            Reply r = fetcher.send("POST", url, h, body);
            return new Ping(r.status >= 200 && r.status < 300, r.status, r.millis, model, explain(r.status, r.body));
        } catch (Exception failed) {
            return new Ping(false, 0, 0, model, describeFailure(failed));
        }
    }

    static String explain(int status, String body) {
        if (status >= 200 && status < 300) return "Модель ответила";
        switch (status) {
            case 400: return "Сервер принял ключ, но отклонил запрос: проверьте имя модели";
            case 401: return "Ключ отклонён";
            case 402: return "Нет средств или токенов на балансе";
            case 403: return "Доступ запрещён: ключ без прав на эту модель или регион закрыт";
            case 404: return "Модель или маршрут не найдены";
            case 429: return "Лимит запросов или квота исчерпаны";
            default: return status >= 500 ? "Ошибка на стороне сервиса (" + status + ")" : "HTTP " + status;
        }
    }
}
