package app.githubble.mobile.agent;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native OpenAI-compatible chat transport for the explicitly supported API-key subset
 * of OmniChat's provider catalog. No WebView cookies or ChatGPT subscription tokens.
 *
 * All requests are sent directly to pinned HTTPS vendor endpoints. This bridge cannot
 * accept an arbitrary URL from chat text, provider metadata or untrusted repositories.
 */
public final class OmniChatChatGateway {
    private static final int READ_LIMIT = 5 * 1024 * 1024;
    private static final int MAX_TURNS = 16;

    public static final class Reply {
        public final String body, provider, model;
        Reply(String body, String provider, String model) {
            this.body = body; this.provider = provider; this.model = model;
        }
    }

    private static final class Endpoint {
        final String base;
        final boolean responses;
        Endpoint(String base, boolean responses) { this.base = base; this.responses = responses; }
    }

    private static Endpoint endpoint(String id) {
        if (id == null) throw new IllegalArgumentException("Выберите провайдера");
        switch (id) {
            case "openai": return new Endpoint("https://api.openai.com/v1", true);
            case "openrouter": return new Endpoint("https://openrouter.ai/api/v1", false);
            case "deepseek": return new Endpoint("https://api.deepseek.com/v1", false);
            case "groq": return new Endpoint("https://api.groq.com/openai/v1", false);
            case "xai": return new Endpoint("https://api.x.ai/v1", false);
            case "mistral": return new Endpoint("https://api.mistral.ai/v1", false);
            case "together": return new Endpoint("https://api.together.ai/v1", false);
            case "fireworks": return new Endpoint("https://api.fireworks.ai/inference/v1", false);
            case "cerebras": return new Endpoint("https://api.cerebras.ai/v1", false);
            case "sambanova": return new Endpoint("https://api.sambanova.ai/v1", false);
            case "perplexity": return new Endpoint("https://api.perplexity.ai", false);
            case "alibaba": return new Endpoint("https://dashscope-intl.aliyuncs.com/compatible-mode/v1", false);
            case "kimi": return new Endpoint("https://api.moonshot.ai/v1", false);
            case "minimax": return new Endpoint("https://api.minimax.io/v1", false);
            case "zai": return new Endpoint("https://api.z.ai/api/paas/v4", false);
            case "siliconflow": return new Endpoint("https://api.siliconflow.com/v1", false);
            case "nvidia": return new Endpoint("https://integrate.api.nvidia.com/v1", false);
            default: throw new IllegalArgumentException("Транспорт провайдера ещё не перенесён: " + id);
        }
    }

    public Reply reply(AiConnectionStore.Credentials credentials, JSONArray history,
                       String projectName, String repository, boolean agentMode) throws Exception {
        if (credentials == null || credentials.apiKey == null || credentials.apiKey.isEmpty())
            throw new IllegalStateException("AI-подключение не настроено");
        Endpoint route = endpoint(credentials.provider);
        JSONObject payload = new JSONObject().put("model", credentials.model)
                .put("store", false);
        JSONArray entries = new JSONArray();
        String context = "You are an AI assistant working inside the user's native GitHubble Android app."
                + " Current project: " + restricted(projectName, 120)
                + "; repository: " + restricted(repository, 180) + "."
                + " You may explain code and prepare changes, but you do not currently have tools"
                + " to write files, commit, launch builds or create a repository from this model call."
                + " Never claim you performed GitHub operations without an actual tool result.";
        if (agentMode) context += " The user selected Agent mode, but model function-calling is not active yet.";
        entries.put(new JSONObject().put("role", route.responses ? "developer" : "system")
                .put("content", context));
        int from = Math.max(0, history.length() - MAX_TURNS * 2);
        for (int i = from; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            String role = item.optString("role");
            if (!"user".equals(role) && !"agent".equals(role)) continue;
            String body = item.optString("body").trim();
            if (body.isEmpty()) continue;
            if (body.length() > 6000) body = body.substring(0, 6000);
            entries.put(new JSONObject().put("role", "agent".equals(role) ? "assistant" : "user")
                    .put("content", body));
        }
        if (entries.length() < 2) throw new IllegalArgumentException("Нет сообщения для AI-модели");
        // The Responses and ChatCompletions protocols differ in accepted fields.
        payload.put(route.responses ? "input" : "messages", entries);
        if (!route.responses) payload.remove("store");
        String url = route.base + (route.responses ? "/responses" : "/chat/completions");
        JSONObject result = doPost(url, credentials.apiKey, payload);
        String output = route.responses ? parseResponses(result) : parseChat(result);
        if (output.trim().isEmpty()) throw new IllegalStateException("Провайдер вернул пустой ответ");
        return new Reply(output.trim(), credentials.provider, credentials.model);
    }

    private static String restricted(String value, int limit) {
        if (value == null) return "(none)";
        String clean = value.replace('\n', ' ').replace('\r', ' ');
        return clean.substring(0, Math.min(limit, clean.length()));
    }

    private static String parseChat(JSONObject root) {
        JSONArray choices = root.optJSONArray("choices");
        JSONObject first = choices == null ? null : choices.optJSONObject(0);
        JSONObject message = first == null ? null : first.optJSONObject("message");
        if (message == null) return "";
        Object content = message.opt("content");
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray blocks = (JSONArray) content;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) {
                    if (b.length() > 0) b.append('\n');
                    b.append(block.optString("text"));
                }
            }
            return b.toString();
        }
        return "";
    }

    private static String parseResponses(JSONObject root) {
        String text = root.optString("output_text", "");
        if (!text.isEmpty()) return text;
        JSONArray output = root.optJSONArray("output");
        if (output == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject message = output.optJSONObject(i);
            if (message == null || !"message".equals(message.optString("type"))) continue;
            JSONArray blocks = message.optJSONArray("content");
            if (blocks == null) continue;
            for (int j = 0; j < blocks.length(); j++) {
                JSONObject part = blocks.optJSONObject(j);
                if (part == null || !"output_text".equals(part.optString("type"))) continue;
                if (b.length() > 0) b.append('\n');
                b.append(part.optString("text"));
            }
        }
        return b.toString();
    }

    private static JSONObject doPost(String endpoint, String key, JSONObject payload) throws Exception {
        HttpURLConnection c = app.githubble.mobile.network.AiNetwork.open(endpoint);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "GitHubble-AIWorkspace/1");
        c.setDoOutput(true);
        try {
            byte[] request = payload.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(request.length);
            try (OutputStream out = c.getOutputStream()) { out.write(request); }
            int status = c.getResponseCode();
            InputStream in = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
            byte[] bytes = readLimited(in);
            String data = new String(bytes, StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                String hint = "";
                try {
                    JSONObject problem = new JSONObject(data);
                    Object error = problem.opt("error");
                    if (error instanceof JSONObject) hint = ((JSONObject) error).optString("message", "");
                    if (hint.isEmpty()) hint = problem.optString("detail", "");
                } catch (Exception ignored) {}
                String diagnostic = hint.isEmpty() ? "Ошибка ответа провайдера" : hint;
                // Do not forward accidentally reflected secrets to UI messages.
                diagnostic = diagnostic.replace(key, "[secret]");
                if (diagnostic.length() > 240) diagnostic = diagnostic.substring(0, 240);
                throw new IllegalStateException("AI HTTP " + status + ": " + diagnostic);
            }
            return new JSONObject(data);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readLimited(InputStream stream) throws Exception {
        if (stream == null) return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) != -1) {
                if (out.size() + n > READ_LIMIT) throw new IllegalStateException("Ответ AI слишком большой");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
