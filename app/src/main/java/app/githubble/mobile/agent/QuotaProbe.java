package app.githubble.mobile.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Remote balance of a single key, only where the provider documents a balance endpoint.
 * Everything else relies on local counters and the limits the user sets per key.
 */
public final class QuotaProbe {
    private QuotaProbe() {}

    public static boolean supported(String provider) {
        return "openrouter".equals(provider) || "deepseek".equals(provider) || "kimi".equals(provider)
                || "siliconflow".equals(provider) || "gigachat".equals(provider);
    }

    /** Same as {@link #fetch(String, String)} but knows the saved endpoint and access scope (needed by Kimi, SiliconFlow and GigaChat). */
    public static String fetch(android.content.Context context, String provider, String key, String baseUrl, String scope) throws Exception {
        if ("gigachat".equals(provider)) return app.githubble.mobile.network.GigaChatBalance.fetch(context, key, scope == null ? "" : scope);
        if ("kimi".equals(provider)) {
            boolean china = baseUrl != null && baseUrl.contains("moonshot.cn");
            return kimi(getJson(china ? "https://api.moonshot.cn/v1/users/me/balance" : "https://api.moonshot.ai/v1/users/me/balance", key), china);
        }
        if ("siliconflow".equals(provider)) {
            boolean china = baseUrl != null && baseUrl.contains("siliconflow.cn");
            return siliconFlow(getJson(china ? "https://api.siliconflow.cn/v1/user/info" : "https://api.siliconflow.com/v1/user/info", key));
        }
        return fetch(provider, key);
    }

    static String kimi(JSONObject json, boolean china) {
        JSONObject data = json.optJSONObject("data");
        if (data == null || !data.has("available_balance")) throw new IllegalStateException("Нет данных о балансе");
        String unit = china ? "¥" : "$";
        String out = String.format(Locale.ROOT, "Доступно %s%.2f", unit, data.optDouble("available_balance", 0));
        if (data.has("voucher_balance")) out += String.format(Locale.ROOT, " (ваучеры %s%.2f)", unit, data.optDouble("voucher_balance", 0));
        return out;
    }

    static String siliconFlow(JSONObject json) {
        JSONObject data = json.optJSONObject("data");
        if (data == null || !data.has("totalBalance")) throw new IllegalStateException("Нет данных о балансе");
        String out = "Баланс: " + data.optString("totalBalance");
        if (data.has("chargeBalance")) out += " (пополнено " + data.optString("chargeBalance") + ")";
        return out;
    }

    private static JSONObject getJson(String url, String key) throws Exception {
        HttpURLConnection connection = app.githubble.mobile.network.AiNetwork.open(url);
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + key.trim());
            connection.setRequestProperty("User-Agent", "GitHubble-AI-Workspace/1");
            int status = connection.getResponseCode();
            String body;
            try (InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream()) {
                body = read(input);
            }
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    /** One-line balance text for the key; throws with a readable message when the provider refuses. */
    public static String fetch(String provider, String key) throws Exception {
        if (!"openrouter".equals(provider) && !"deepseek".equals(provider)) throw new IllegalArgumentException("Провайдер не отдаёт баланс по API");
        String url = "openrouter".equals(provider) ? "https://openrouter.ai/api/v1/key" : "https://api.deepseek.com/user/balance";
        HttpURLConnection connection = app.githubble.mobile.network.AiNetwork.open(url);
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + key.trim());
            connection.setRequestProperty("User-Agent", "GitHubble-AI-Workspace/1");
            int status = connection.getResponseCode();
            String body;
            try (InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream()) {
                body = read(input);
            }
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            JSONObject json = new JSONObject(body);
            return "openrouter".equals(provider) ? openRouter(json) : deepSeek(json);
        } finally {
            connection.disconnect();
        }
    }

    static String openRouter(JSONObject json) {
        JSONObject data = json.optJSONObject("data");
        if (data == null) throw new IllegalStateException("Пустой ответ");
        double usage = data.optDouble("usage", 0);
        if (data.isNull("limit")) return String.format(Locale.ROOT, "Потрачено $%.2f · лимит ключа не задан", usage);
        double limit = data.optDouble("limit", 0);
        double remaining = data.has("limit_remaining") && !data.isNull("limit_remaining")
                ? data.optDouble("limit_remaining") : Math.max(0, limit - usage);
        return String.format(Locale.ROOT, "Осталось $%.2f из $%.2f", remaining, limit);
    }

    static String deepSeek(JSONObject json) {
        JSONArray infos = json.optJSONArray("balance_infos");
        if (infos == null || infos.length() == 0) throw new IllegalStateException("Нет данных о балансе");
        StringBuilder out = new StringBuilder(json.optBoolean("is_available", true) ? "Баланс: " : "Недоступен · баланс: ");
        for (int i = 0; i < infos.length(); i++) {
            JSONObject item = infos.optJSONObject(i);
            if (item == null) continue;
            if (i > 0) out.append(" · ");
            out.append(item.optString("total_balance", "?")).append(' ').append(item.optString("currency", ""));
        }
        return out.toString().trim();
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n, total = 0;
            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > 256 * 1024) throw new IllegalStateException("Слишком большой ответ");
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
