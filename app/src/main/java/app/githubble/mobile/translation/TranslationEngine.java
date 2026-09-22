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
        public String provider = "mlkit"; // mlkit, auto, deepl, google, openai, libretranslate, generic
        public String endpoint = "";
        public String apiKey = "";
        public String model = "gpt-4.1-mini";
        public int chunkSize = 3600;
        public String sourceLanguage = "auto";
        public String identity() { return provider + "|" + endpoint + "|" + model + "|" + sourceLanguage; }
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

    private static final Pattern PLACEHOLDER = Pattern.compile("⟦GH_\\\\d{4,}⟧");

    /**
     * ML Kit can rewrite artificial placeholders. Never send technical fragments to
     * the on-device translator: split on protected markers and splice the exact
     * original source fragments back after translating only ordinary prose.
     */
    private static final class LocalPart {
        final String text;
        final boolean translate;
        LocalPart(String text,boolean translate){this.text=text;this.translate=translate;}
    }

    private static List<LocalPart> localParts(ProtectedText protectedText,int max){
        List<LocalPart> parts=new ArrayList<>();
        Matcher matcher=PLACEHOLDER.matcher(protectedText.text);
        int cursor=0;
        while(matcher.find()){
            addLocalProse(parts,protectedText.text.substring(cursor,matcher.start()),max);
            String original=protectedText.tokens.get(matcher.group());
            if(original==null)
                throw new IllegalStateException("Не удалось восстановить защищённый фрагмент.");
            parts.add(new LocalPart(original,false));
            cursor=matcher.end();
        }
        addLocalProse(parts,protectedText.text.substring(cursor),max);
        return parts;
    }

    private static void addLocalProse(List<LocalPart> parts,String text,int max){
        if(text.isEmpty())return;
        for(String chunk:splitMarkdown(text,max))
            if(!chunk.isEmpty())parts.add(new LocalPart(chunk,true));
    }

    private static String translateOffline(String source,String target,Config config,
                                           Progress progress)throws Exception{
        String from=MlKitTranslation.detectSource(source,config.sourceLanguage);
        if(from.equals(target))return source;
        List<LocalPart> parts=localParts(protect(source),
                Math.max(1200,Math.min(7000,config.chunkSize)));
        List<String> prose=new ArrayList<>();
        for(LocalPart part:parts)
            if(part.translate&&!part.text.trim().isEmpty())prose.add(part.text);
        if(prose.isEmpty())return source;
        List<String> translated=MlKitTranslation.translateSegments(prose,from,target,progress);
        StringBuilder output=new StringBuilder(source.length()+source.length()/5);
        int translatedIndex=0;
        for(LocalPart part:parts){
            if(!part.translate||part.text.trim().isEmpty())output.append(part.text);
            else output.append(translated.get(translatedIndex++));
        }
        return output.toString();
    }

    public String translate(String source, String target, Config config, Progress progress) throws Exception {
        if(source==null||source.trim().isEmpty())return "";
        if(config==null)throw new IllegalStateException("Переводчик не выбран");
        if("mlkit".equals(config.provider)||"auto".equals(config.provider))
            return translateOffline(source,target,config,progress);
        if(config.endpoint==null||config.endpoint.trim().isEmpty())
            throw new IllegalStateException("Настройте адрес облачного переводчика.");
        ProtectedText protectedText=protect(source);
        List<String> chunks=splitMarkdown(protectedText.text,
                Math.max(1200,Math.min(7000,config.chunkSize)));
        StringBuilder translated=new StringBuilder(source.length()+source.length()/5);
        for(int i=0;i<chunks.size();i++){
            String part=request(chunks.get(i),target,config);
            if(translated.length()>0&&!endsWithWhitespace(translated)&&!part.startsWith("\\n"))
                translated.append('\\n');
            translated.append(part);
            if(progress!=null)progress.onProgress(i+1,chunks.size());
        }
        String output=translated.toString();
        for(String placeholder:protectedText.tokens.keySet()){
            if(!output.contains(placeholder))
                throw new IllegalStateException("Переводчик изменил технический фрагмент. Оригинал сохранён; попробуйте другой способ перевода.");
        }
        return restore(output,protectedText.tokens);
    }

    public static String providerLabel(String provider) {
        if ("mlkit".equals(provider)) return "Google ML Kit · на устройстве";
        if ("auto".equals(provider)) return "Автоматически · сначала на устройстве";
        if ("deepl".equals(provider)) return "DeepL API";
        if ("google".equals(provider)) return "Google Cloud Translation";
        if ("openai".equals(provider)) return "OpenAI-совместимая модель";
        if ("libretranslate".equals(provider)) return "LibreTranslate";
        return "Универсальный JSON";
    }

    private String request(String text, String target, Config config) throws Exception {
        URL destination=new URL(config.endpoint.trim());
        if(!"https".equalsIgnoreCase(destination.getProtocol())||destination.getUserInfo()!=null)
            throw new IllegalArgumentException("Облачный переводчик должен использовать HTTPS без пароля в адресе.");
        HttpURLConnection c = (HttpURLConnection) destination.openConnection();
        c.setInstanceFollowRedirects(false);
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
        } else if ("deepl".equals(config.provider)) {
            if(empty(config.apiKey))throw new IllegalStateException("Для DeepL нужен ключ API.");
            c.setRequestProperty("Authorization","DeepL-Auth-Key "+config.apiKey);
            body.put("text",new JSONArray().put(text));
            body.put("target_lang",target.toUpperCase(Locale.ROOT));
            body.put("preserve_formatting",true);
        } else if ("google".equals(config.provider)) {
            if(empty(config.apiKey))throw new IllegalStateException("Для Google Cloud Translation нужен ключ API.");
            c.setRequestProperty("X-goog-api-key",config.apiKey);
            body.put("q",new JSONArray().put(text));
            body.put("target",target);
            body.put("format","text");
        } else if ("libretranslate".equals(config.provider)) {
            body.put("q", text); body.put("source", "auto"); body.put("target", target); body.put("format", "text");
            if (config.apiKey != null && !config.apiKey.isEmpty()) body.put("api_key", config.apiKey);
        } else {
            if (config.apiKey != null && !config.apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            body.put("text", text); body.put("source", "auto"); body.put("target", target);
            if (!empty(config.model)) body.put("model", config.model);
        }
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        String response = readAll(status < 400 ? c.getInputStream() : c.getErrorStream());
        if (status >= 300 && status < 400)
            throw new IllegalStateException("Переводчик перенаправил запрос. Проверьте конечный HTTPS endpoint.");
        if (status >= 400) throw new IllegalStateException("Переводчик HTTP " + status + ": " + compact(response));
        JSONObject json = new JSONObject(response);
        if ("openai".equals(config.provider)) {
            JSONArray choices = json.optJSONArray("choices");
            JSONObject first = choices == null ? null : choices.optJSONObject(0);
            JSONObject message = first == null ? null : first.optJSONObject("message");
            String value = message == null ? "" : message.optString("content", "");
            if (!value.isEmpty()) return value;
        }
        if ("deepl".equals(config.provider)){
            JSONArray values=json.optJSONArray("translations");
            JSONObject first=values==null?null:values.optJSONObject(0);
            String result=first==null?"":first.optString("text","");
            if(!result.isEmpty())return result;
        }
        if ("google".equals(config.provider)){
            JSONObject data=json.optJSONObject("data");
            JSONArray values=data==null?null:data.optJSONArray("translations");
            JSONObject first=values==null?null:values.optJSONObject(0);
            String result=first==null?"":first.optString("translatedText","");
            if(!result.isEmpty())return android.text.Html.fromHtml(result,
                    android.text.Html.FROM_HTML_MODE_LEGACY).toString();
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
