package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small shared helpers for the repository settings screens: URLs, fresh reads and permission-aware error texts. */
public final class Gh {
    private Gh() {}

    public static String repo(String repo) { return "https://api.github.com/repos/" + RepoSecrets.checkRepo(repo); }

    public static JSONObject object(GitHubApiClient api, String url) throws Exception { return api.getJsonFresh(url); }

    public static JSONArray array(GitHubApiClient api, String url) throws Exception {
        String text = api.executeText("GET", url, null, true);
        return new JSONArray(text == null || text.trim().isEmpty() ? "[]" : text);
    }

    /** One path segment (a branch name may hold slashes, which the API accepts as they are). */
    public static String segment(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("Пустое имя");
        String name = raw.trim();
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) throw new IllegalArgumentException("Недопустимые символы в имени");
        try { return java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("%2F", "/"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }

    /** One path component: unlike {@link #segment} a slash is encoded too (label names such as type/bug). */
    public static String component(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("Пустое имя");
        try { return java.net.URLEncoder.encode(raw.trim(), "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }

    public static int status(Exception error) { return error instanceof GitHubApiClient.ApiException ? ((GitHubApiClient.ApiException) error).statusCode : 0; }

    /** Names the GitHub App permission that is most likely missing, so the user knows exactly what to switch on. */
    public static String explain(Exception error, String permission) {
        int code = status(error);
        if (code == 401) return "Сессия GitHub истекла. Войдите в аккаунт заново (Настройки → Аккаунт).";
        if (code == 403 || code == 404) {
            if (error instanceof GitHubApiClient.ApiException && ((GitHubApiClient.ApiException) error).rateLimited)
                return "Лимит запросов GitHub исчерпан. Подождите немного и повторите.";
            String raw = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (raw.contains("upgrade") || raw.contains("advanced security") || raw.contains("not available for")) return "Эта функция недоступна для репозитория на текущем тарифе GitHub (приватный репозиторий, нужен платный план или Advanced Security). Ответ GitHub: " + error.getMessage();
            return "GitHub не разрешил действие (HTTP " + code + "). Проверьте по порядку: 1) у GitHub App включено право «" + permission + "» — Read and write (GitHub App → Permissions → Repository permissions); "
                + "2) новые права подтверждены на github.com/settings/installations → Configure → Review request; 3) репозиторий входит в установку приложения; 4) у вас права администратора репозитория"
                + (code == 404 ? "; либо такой функции в этом репозитории нет (тариф, тип аккаунта)." : ".");
        }
        if (code == 422) return "GitHub отклонил запрос (HTTP 422): " + (error.getMessage() == null ? "" : error.getMessage());
        return error.getMessage() == null ? "Ошибка сети" : error.getMessage();
    }

    static String bytes(String s) { return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8); }

    static String text(JSONObject o, String key) { return o == null ? "" : o.optString(key, ""); }
}
