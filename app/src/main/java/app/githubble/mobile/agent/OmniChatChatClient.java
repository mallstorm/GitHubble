package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Native phase-2 text transport for the direct/API-key providers listed in OmniChat.
 * Fixed HTTPS endpoint allowlist prevents provider IDs or prompts from becoming arbitrary URLs.
 * Does NOT use ChatGPT website cookies nor impersonate an OAuth subscription.
 */
public final class OmniChatChatClient {
    private final OAuthSubscriptions subscriptions;
    public OmniChatChatClient(){this.subscriptions=null;}
    public OmniChatChatClient(OAuthSubscriptions subscriptions){this.subscriptions=subscriptions;}
    /** Reported by an AI API for the current worker thread; not a subscription quota. */
    public static final class Usage {
        public final long inputTokens,outputTokens;
        public final boolean reported;
        Usage(long input,long output,boolean reported){
            inputTokens=Math.max(0,input);outputTokens=Math.max(0,output);this.reported=reported;
        }
    }
    private static final ThreadLocal<Usage> LAST_USAGE=new ThreadLocal<>();
    public static Usage takeUsage(){Usage usage=LAST_USAGE.get();LAST_USAGE.remove();return usage;}
    private static void captureUsage(JSONObject response){
        JSONObject usage=response.optJSONObject("usage");
        if(usage==null){LAST_USAGE.set(new Usage(0,0,false));return;}
        long input=usage.has("input_tokens")?usage.optLong("input_tokens"):usage.optLong("prompt_tokens");
        long output=usage.has("output_tokens")?usage.optLong("output_tokens"):usage.optLong("completion_tokens");
        LAST_USAGE.set(new Usage(input,output,true));
    }

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_HISTORY_TURNS = 28;
    private static final int MAX_TEXT_CHARS = 20000;
    private static final int MAX_INPUT_BYTES = 130 * 1024;

    static String endpoint(String provider) {
        switch (provider) {
            case "openai": return "https://api.openai.com/v1/responses";
            case "gemini": return "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
            case "openrouter": return "https://openrouter.ai/api/v1/chat/completions";
            case "groq": return "https://api.groq.com/openai/v1/chat/completions";
            case "deepseek": return "https://api.deepseek.com/v1/chat/completions";
            case "xai": return "https://api.x.ai/v1/chat/completions";
            case "mistral": return "https://api.mistral.ai/v1/chat/completions";
            case "together": return "https://api.together.ai/v1/chat/completions";
            case "cerebras": return "https://api.cerebras.ai/v1/chat/completions";
            case "nvidia": return "https://integrate.api.nvidia.com/v1/chat/completions";
            default: throw new IllegalArgumentException("Этот AI-транспорт не поддерживается");
        }
    }

    private static JSONArray conversation(JSONArray transcript) throws org.json.JSONException {
        JSONArray messages = new JSONArray();
        int since = Math.max(0, transcript.length() - MAX_HISTORY_TURNS);
        for (int i = since; i < transcript.length(); i++) {
            JSONObject entry = transcript.optJSONObject(i);
            if (entry == null) continue;
            String role = entry.optString("role", "");
            if ("agent".equals(role)) role = "assistant";
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            String value = entry.optString("body", "").trim();
            if (value.isEmpty()) continue;
            messages.put(new JSONObject().put("role", role)
                    .put("content", value.substring(0, Math.min(MAX_TEXT_CHARS, value.length()))));
        }
        return messages;
    }

    public static boolean supportsVision(String provider){
        return "openai".equals(provider)||"gemini".equals(provider)||"openrouter".equals(provider);
    }

    public String completeWithImage(String provider,String model,String credential,
            JSONArray transcript,String project,String repository,String mime,byte[] image)throws Exception{
        if(!supportsVision(provider))throw new IllegalArgumentException(
            "Выбранная модель/подключение не поддерживает передачу изображений в Git Agent");
        if(image==null||image.length==0||image.length>1024*1024)
            throw new IllegalArgumentException("Фото должно быть не больше 1 МБ");
        if(!"image/jpeg".equals(mime)&&!"image/png".equals(mime)&&!"image/webp".equals(mime))
            throw new IllegalArgumentException("Поддерживаются JPEG, PNG и WebP");
        return completeInternal(provider,model,credential,transcript,project,repository,mime,image);
    }

    public String complete(String provider, String model, String credential,
                           JSONArray transcript, String project, String repository) throws Exception {
        return completeInternal(provider,model,credential,transcript,project,repository,null,null);
    }

    private String completeInternal(String provider,String model,String credential,
            JSONArray transcript,String project,String repository,String imageMime,byte[] image)throws Exception {
        LAST_USAGE.remove();
        if(OAuthSubscriptions.supports(provider)){
            if(subscriptions==null)throw new IllegalStateException("OAuth-транспорт не инициализирован");
            return subscriptions.complete(provider,model,credential,transcript,project,repository);
        }
        if (credential == null || credential.trim().isEmpty())
            throw new IllegalStateException("Нет API-ключа для подключения");
        if (model == null || model.trim().isEmpty())
            throw new IllegalStateException("Не выбрана модель");
        JSONArray messages = conversation(transcript);
        if (messages.length() == 0) throw new IllegalArgumentException("Нет сообщений пользователя");
        if(image!=null){
            String imageUrl="data:"+imageMime+";base64,"+
                java.util.Base64.getEncoder().encodeToString(image);
            boolean appended=false;
            for(int i=messages.length()-1;i>=0;i--){
                JSONObject entry=messages.getJSONObject(i);
                if(!"user".equals(entry.optString("role")))continue;
                String text=entry.optString("content");
                JSONArray parts=new JSONArray();
                if("openai".equals(provider)){
                    parts.put(new JSONObject().put("type","input_text").put("text",text));
                    parts.put(new JSONObject().put("type","input_image").put("image_url",imageUrl));
                }else{
                    parts.put(new JSONObject().put("type","text").put("text",text));
                    parts.put(new JSONObject().put("type","image_url")
                        .put("image_url",new JSONObject().put("url",imageUrl)));
                }
                entry.put("content",parts);
                appended=true;break;
            }
            if(!appended)throw new IllegalArgumentException("Нет сообщения для изображения");
        }

        String context = "Вы AI-помощник в приложении GitHubble. Отвечайте на языке пользователя. "
                + "Помогайте анализировать архитектуру, писать код и планировать проект. "
                + "Не утверждайте, что создали репозиторий, коммит или APK, если результат не подтверждён инструментами GitHub. "
                + "Операции с GitHub выполняет отдельный инструмент приложения по подтверждению пользователя.";
        if (project != null && !project.isEmpty()) context += "\nПроект: " + project;
        if (repository != null && !repository.isEmpty()) context += "\nРепозиторий: " + repository;

        JSONObject body;
        if ("openai".equals(provider)) {
            body = new JSONObject().put("model", model.trim())
                    .put("instructions", context)
                    .put("input", messages)
                    .put("store", false)
                    .put("stream", false);
        } else {
            JSONArray chat = new JSONArray().put(new JSONObject()
                    .put("role", "system").put("content", context));
            for (int i = 0; i < messages.length(); i++) chat.put(messages.optJSONObject(i));
            body = new JSONObject().put("model", model.trim())
                    .put("messages", chat).put("stream", false);
        }

        byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (requestBytes.length > (image==null?MAX_INPUT_BYTES:3*1024*1024))
            throw new IllegalArgumentException("Контекст слишком большой для одного запроса");
        HttpURLConnection connection = app.githubble.mobile.network.AiNetwork.open(endpoint(provider));
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(85000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + credential.trim());
            connection.setRequestProperty("User-Agent", "GitHubble-AI-Workspace/1");
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }
            int status = connection.getResponseCode();
            boolean ok = status >= 200 && status < 300;
            String raw;
            try (InputStream input = ok ? connection.getInputStream() : connection.getErrorStream()) {
                raw = readLimited(input);
            }
            if (!ok) throw new IllegalStateException(
                    "AI API (" + provider + ") HTTP " + status + ": " + apiError(raw, credential));
            JSONObject result = new JSONObject(raw);
            captureUsage(result);
            String reply = "openai".equals(provider) ? responseOutput(result) : chatOutput(result);
            if (reply.isEmpty()) throw new IllegalStateException(
                    "AI API не вернул текст. Проверьте модель, тариф и поддержку текстового чата.");
            return reply;
        } finally {
            connection.disconnect();
        }
    }

    static String chatOutput(JSONObject result) {
        JSONArray choices = result.optJSONArray("choices");
        JSONObject choice = choices == null ? null : choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        if (message == null) return "";
        Object content = message.opt("content");
        if (content instanceof String) return ((String) content).trim();
        if (content instanceof JSONArray) {
            StringBuilder text = new StringBuilder();
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && part.optString("text").length() > 0)
                    text.append(part.optString("text"));
            }
            return text.toString().trim();
        }
        return "";
    }

    static String responseOutput(JSONObject result) {
        String direct = result.optString("output_text", "").trim();
        if (!direct.isEmpty()) return direct;
        JSONArray output = result.optJSONArray("output");
        if (output == null) return "";
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type", ""))) continue;
            JSONArray parts = item.optJSONArray("content");
            if (parts == null) continue;
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type", "")))
                    answer.append(part.optString("text", ""));
            }
        }
        return answer.toString().trim();
    }

    private static String apiError(String raw, String credential) {
        String message = "Неизвестная ошибка";
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject error = root.optJSONObject("error");
            if (error != null) message = error.optString("message", message);
            else message = root.optString("message", message);
        } catch (Exception ignored) {}
        if (credential != null && !credential.isEmpty()) message = message.replace(credential, "[redacted]");
        if (message.length() > 350) message = message.substring(0, 350);
        return message.replace("\n", " ").replace("\r", " ");
    }

    /** Discovers model IDs through a selected provider's fixed official HTTPS endpoint. */
    public java.util.List<String> discoverModels(String provider, String credential) throws Exception {
        if(OAuthSubscriptions.supports(provider)){
            if(subscriptions==null)throw new IllegalStateException("OAuth-транспорт не инициализирован");
            return subscriptions.discover(provider,credential);
        }

        if (credential == null || credential.trim().isEmpty())
            throw new IllegalStateException("Сначала сохраните API-ключ");
        String base = endpoint(provider);
        String modelsEndpoint = base.endsWith("/responses")
                ? base.substring(0, base.length() - "/responses".length()) + "/models"
                : base.substring(0, base.length() - "/chat/completions".length()) + "/models";
        HttpURLConnection connection = app.githubble.mobile.network.AiNetwork.open(modelsEndpoint);
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + credential.trim());
            connection.setRequestProperty("User-Agent", "GitHubble-AI-Workspace/1");
            int status = connection.getResponseCode();
            String response;
            try (InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream()) {
                response = readLimited(input);
            }
            if (status < 200 || status >= 300)
                throw new IllegalStateException("Каталог моделей HTTP " + status + ": " + apiError(response, credential));
            JSONObject result = new JSONObject(response);
            JSONArray entries = result.optJSONArray("data");
            if (entries == null) entries = result.optJSONArray("models");
            if (entries == null) return java.util.Collections.emptyList();
            java.util.TreeSet<String> models = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 0; i < entries.length() && models.size() < 500; i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (id.isEmpty()) id = item.optString("name", "");
                if (id.startsWith("models/")) id = id.substring("models/".length());
                if (!id.isEmpty() && id.length() <= 160) models.add(id);
            }
            return new java.util.ArrayList<>(models);
        } finally {
            connection.disconnect();
        }
    }

    private static String readLimited(InputStream input) throws Exception {
        if (input == null) return "";
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int read, total = 0;
            while ((read = input.read(bytes)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES)
                    throw new IllegalStateException("AI API вернул слишком большой ответ");
                output.write(bytes, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

