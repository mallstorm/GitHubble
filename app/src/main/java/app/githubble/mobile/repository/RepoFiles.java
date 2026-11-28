package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reading and committing single text files in a repository (community files, CODEOWNERS, dependabot.yml). */
public final class RepoFiles {
    private RepoFiles() {}

    public static final class File {
        public final String path, sha, text; public final boolean exists;
        File(String path, String sha, String text, boolean exists) { this.path = path; this.sha = sha; this.text = text; this.exists = exists; }
    }

    /** The files the editor offers: path, title and a starter text for a new file. */
    public static final String[][] KNOWN = {
        {".github/CODEOWNERS", "CODEOWNERS — кто проверяет изменения", "# Каждая строка: шаблон путей и владельцы\n# * @owner\n# /docs/ @owner\n* @"},
        {".github/PULL_REQUEST_TEMPLATE.md", "Шаблон pull request", "## Что изменено\n\n## Как проверить\n\n## Чек-лист\n- [ ] Тесты проходят\n- [ ] Документация обновлена\n"},
        {".github/ISSUE_TEMPLATE/bug_report.md", "Шаблон issue: ошибка", "---\nname: Ошибка\nabout: Сообщить о проблеме\nlabels: bug\n---\n\n**Что произошло**\n\n**Как воспроизвести**\n\n**Ожидаемое поведение**\n"},
        {".github/ISSUE_TEMPLATE/feature_request.md", "Шаблон issue: идея", "---\nname: Идея\nabout: Предложить улучшение\nlabels: enhancement\n---\n\n**Что хотелось бы**\n\n**Зачем это нужно**\n"},
        {"CONTRIBUTING.md", "CONTRIBUTING — как участвовать", "# Как участвовать\n\n1. Форкните репозиторий и создайте ветку.\n2. Внесите изменения и добавьте тесты.\n3. Откройте pull request.\n"},
        {"SECURITY.md", "SECURITY — как сообщить об уязвимости", "# Политика безопасности\n\nСообщайте об уязвимостях приватно через вкладку Security → Report a vulnerability.\n"},
        {".github/FUNDING.yml", "FUNDING — спонсорство", "# github: [username]\n"},
        {".github/dependabot.yml", "dependabot.yml — автообновления", "version: 2\nupdates: []\n"},
    };

    public static String checkPath(String raw) {
        String path = raw == null ? "" : raw.trim();
        for (String[] known : KNOWN) if (known[0].equals(path)) return path;
        throw new IllegalArgumentException("Этот файл редактор не открывает");
    }

    static String decode(String base64) {
        return new String(java.util.Base64.getMimeDecoder().decode(base64 == null ? "" : base64), StandardCharsets.UTF_8);
    }

    static String encode(String text) { return java.util.Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }

    static File parse(String path, JSONObject json) {
        if ("file".equals(json.optString("type", "file")) && json.has("content")) return new File(path, json.optString("sha"), decode(json.optString("content")), true);
        throw new IllegalArgumentException("«" + path + "» — не файл");
    }

    public static File get(GitHubApiClient api, String repo, String path, String ref) throws Exception {
        String url = Gh.repo(repo) + "/contents/" + Gh.segment(path) + (ref == null || ref.isEmpty() ? "" : "?ref=" + java.net.URLEncoder.encode(ref, "UTF-8"));
        try { return parse(path, Gh.object(api, url)); }
        catch (GitHubApiClient.ApiException e) { if (e.statusCode == 404) return new File(path, "", "", false); throw e; }
    }

    static JSONObject putBody(String message, String text, String branch, String sha) throws Exception {
        String note = message == null ? "" : message.trim();
        if (note.isEmpty()) throw new IllegalArgumentException("Введите сообщение коммита");
        JSONObject o = new JSONObject().put("message", note).put("content", encode(text == null ? "" : text));
        if (branch != null && !branch.isEmpty()) o.put("branch", branch);
        if (sha != null && !sha.isEmpty()) o.put("sha", sha);
        return o;
    }

    /** Commits the file to the branch; {@code sha} is the current blob of an existing file. */
    public static void put(GitHubApiClient api, String repo, String path, String branch, String text, String message, String sha) throws Exception {
        api.putJson(Gh.repo(repo) + "/contents/" + Gh.segment(path), putBody(message, text, branch, sha));
    }

    /** Lines of CODEOWNERS that GitHub could not parse, as readable messages. */
    public static List<String> codeownersErrors(GitHubApiClient api, String repo) throws Exception {
        JSONArray list = Gh.object(api, Gh.repo(repo) + "/codeowners/errors").optJSONArray("errors");
        List<String> out = new ArrayList<>();
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x != null) out.add("строка " + x.optInt("line") + ": " + x.optString("message", x.optString("kind")).split("\n")[0]);
        }
        return out;
    }

    /** Ecosystems for dependabot.yml that this repository root appears to use. */
    public static Set<String> detectEcosystems(GitHubApiClient api, String repo) throws Exception {
        Set<String> names = new HashSet<>();
        JSONArray root = Gh.array(api, Gh.repo(repo) + "/contents/");
        for (int i = 0; i < root.length(); i++) { JSONObject x = root.optJSONObject(i); if (x != null) names.add(x.optString("name")); }
        Set<String> out = DependabotConfig.detect(names);
        try { if (Gh.array(api, Gh.repo(repo) + "/contents/.github/workflows").length() > 0) out.add("github-actions"); } catch (GitHubApiClient.ApiException ignored) { }
        return out;
    }
}
