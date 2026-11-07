package app.githubble.mobile.agent;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Direct import of the Digger401/OmniChat provider catalog (metadata only).
 * Provider auth, transport, and execution must be migrated before a connection is called usable.
 */
public final class OmniChatProviderCatalog {
    private OmniChatProviderCatalog() {}

    public static JSONArray load(Context context) {
        try (InputStream stream = context.getAssets().open("omnichat/provider_catalog.json");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) >= 0) out.write(buffer, 0, n);
            return new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception error) {
            return new JSONArray();
        }
    }

    public static String summary(Context context) {
        JSONArray list = load(context);
        int api = 0, oauth = 0, other = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject provider = list.optJSONObject(i);
            if (provider == null) continue;
            String auth = provider.optString("auth_type", "").toLowerCase(java.util.Locale.ROOT);
            if (auth.contains("oauth")) oauth++;
            else if (auth.contains("bearer") || auth.contains("key")) api++;
            else other++;
        }
        return "Каталог OmniChat: " + list.length() + " провайдеров (ключи: "
                + api + ", OAuth: " + oauth + ", другие: " + other + ")."
                + "\nПеренесены описания подключений; авторизация и отправка запросов "
                + "будут подключены к движку на следующем этапе.";
    }
}
