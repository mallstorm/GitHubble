package app.githubble.mobile.translation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown-aware translation engine with chunking and technical-token protection. */
public final class TranslationEngine {
    public interface Progress { void onProgress(int completed, int total); }

    public static final class Config {
        public String provider = "generic"; // generic, openai, libretranslate
        public String endpoint = "";
        public String apiKey = "";
        public String model = "gpt-4.1-mini";
        public int chunkSize = 3600;
        public String identity() { return provider + "|" + endpoint + "|" + model; }
    }

    private static final Pattern[] PROTECTED_PATTERNS = new Pattern[]{
            Pattern.compile("(?s)```.*?```"),
            Pattern.compile("`[^`\\n]+`"),
            Pattern.compile("https?://[^\\s)<>]+"),
            Pattern.compile("(?m)^\\s{0,3}(?:[$>#]|(?:[-*+] |\\d+\\. ))?(?:[A-Za-z]:\\\\|/)[^\\n]+$"),
            Pattern.compile("(?m)^\\s*(?:\\{|\\}|\\[|\\]|[A-Za-z0-9_.-]+\\s*[:=]\\s*[^\\n]+)$"),
            Pattern.compile("\\b(?:[a-fA-F0-9]{7,64}|v?\\d+(?:\\.\\d+){1,4}(?:[-+][A-Za-z0-9._-]+)?)\\b"),
            Pattern.compile("<[^>]+>")
    };

    public String translate(String source, String target, Config config, Progress progress) throws Exception {
        if (source == null || source.trim().isEmpty()) return "";
        if (config == null || config.endpoint == null || config.endpoint.trim().isEmpty()) throw new IllegalStateException("Провайдер перевода не настроен");
        ProtectedText protectedText = protect(source);
        List<String> chunks = splitMarkdown(protectedText.text, Math.max(1200, Math.min(7000, config.chunkSize)));
        StringBuilder translated = new StringBuilder(source.length() + source.length() / 5);
        for (int i = 0; i < chunks.size(); i++) {
            String part = request(chunks.get(i), target, config);
            if (translated.length() > 0 && !endsWithWhitespace(translated) && !part.startsWith("\n")) translated.append('\n');
            translated.append(part);
            if (progress != null) progress.onProgress(i + 1, chunks.size());
        }
        return restore(translated.toString(), protectedText.tokens);
    }

    public static String providerLabel(String provider) {
        if ("openai".equals(provider)) return "OpenAI-совместимый";
        if ("libretranslate".equals(provider)) return "LibreTranslate";
        return "Универсальный JSON";
    }

    private String request(String text, String target, Config config) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(config.endpoint.trim()).openConnection();
        c.setConnectTimeout(18000); c.setReadTimeout(70000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        JSONObject body = new JSONObject();
        if ("openai".equals(config.provider)) {
            if (config.apiKey != null && !config.apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            body.put("model", empty(config.model) ? "gpt-4.1-mini" : config.model);
            body.put("temperature", 0.1);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Translate natural-language Markdown to Russian. Preserve every placeholder like ⟦GH_0001⟧ exactly, preserve Markdown structure, headings, lists and tables, and output only the translation."));
            messages.put(new JSONObject().put("role", "user").put("content", text));
            body.put("messages", messages);
        } else if ("libretranslate".equals(config.provider)) {
            body.put("q", text); body.put("source", "auto"); body.put("target", target); body.put("format", "text");
            if (config.apiKey != null && !config.apiKey.isEmpty()) body.put("api_key", config.apiKey);
        } else {
            if (config.apiKey != null && !config.apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            body.put("text", text); body.put("source", "auto"); body.put("target", target);
            if (!empty(config.model)) body.put("model", config.model);
        }
        try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int status = c.getResponseCode();
        String response = readAll(status < 400 ? c.getInputStream() : c.getErrorStream());
        if (status >= 400) throw new IllegalStateException("Переводчик HTTP " + status + ": " + compact(response));
        JSONObject json = new JSONObject(response);
        if ("openai".equals(config.provider)) {
            JSONArray choices = json.optJSONArray("choices");
            JSONObject first = choices == null ? null : choices.optJSONObject(0);
            JSONObject message = first == null ? null : first.optJSONObject("message");
            String value = message == null ? "" : message.optString("content", "");
            if (!value.isEmpty()) return value;
        }
        String value = json.optString("translatedText", json.optString("translation", json.optString("text", "")));
        if (value.isEmpty()) throw new IllegalStateException("Провайдер не вернул translatedText");
        return value;
    }

    private static ProtectedText protect(String source) {
        String result = source;
        Map<String,String> tokens = new LinkedHashMap<>();
        for (Pattern pattern : PROTECTED_PATTERNS) {
            Matcher matcher = pattern.matcher(result);
            StringBuffer out = new StringBuffer();
            while (matcher.find()) {
                String token = String.format(Locale.ROOT, "⟦GH_%04d⟧", tokens.size() + 1);
                tokens.put(token, matcher.group());
                matcher.appendReplacement(out, Matcher.quoteReplacement(token));
            }
            matcher.appendTail(out); result = out.toString();
        }
        return new ProtectedText(result, tokens);
    }

    private static String restore(String translated, Map<String,String> tokens) {
        String out = translated;
        for (Map.Entry<String,String> entry : tokens.entrySet()) {
            String token = entry.getKey();
            out = out.replace(token, entry.getValue())
                    .replace(token.replace("⟦", "[ ").replace("⟧", " ]"), entry.getValue())
                    .replace(token.replace("_", " "), entry.getValue());
        }
        return out;
    }

    private static List<String> splitMarkdown(String text, int max) {
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("(?<=\\n)", -1);
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (current.length() > 0 && current.length() + line.length() > max) {
                chunks.add(current.toString()); current.setLength(0);
            }
            if (line.length() > max) {
                int p = 0;
                while (p < line.length()) {
                    int end = Math.min(line.length(), p + max);
                    if (current.length() > 0) { chunks.add(current.toString()); current.setLength(0); }
                    chunks.add(line.substring(p, end)); p = end;
                }
            } else current.append(line);
        }
        if (current.length() > 0) chunks.add(current.toString());
        if (chunks.isEmpty()) chunks.add(text);
        return chunks;
    }

    private static boolean endsWithWhitespace(StringBuilder b) { return b.length() == 0 || Character.isWhitespace(b.charAt(b.length() - 1)); }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
    private static String compact(String value) { value = value == null ? "" : value.replace('\n',' '); return value.length() > 240 ? value.substring(0,240) + "…" : value; }
    private static String readAll(InputStream in) throws Exception { if (in == null) return ""; try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[8192]; int n; while ((n=input.read(b))!=-1) out.write(b,0,n); return out.toString(StandardCharsets.UTF_8.name()); } }

    private static final class ProtectedText {
        final String text; final Map<String,String> tokens;
        ProtectedText(String text, Map<String,String> tokens) { this.text=text; this.tokens=tokens; }
    }
}
