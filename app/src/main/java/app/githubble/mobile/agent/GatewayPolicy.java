package app.githubble.mobile.agent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Host-owned risk classifier for the generic GitHub REST gateway. The model supplies only
 * (method, path, body); every decision below is computed here and never taken from model text.
 * Only api.github.com is reachable and only an allow-listed set of API roots.
 */
public final class GatewayPolicy {
    public enum Risk { READ, REVERSIBLE, DELETE, IRREVERSIBLE, BLOCKED }

    public static final class Decision {
        public final Risk risk;
        public final String method, path, confirm, reason;
        Decision(Risk risk, String method, String path, String confirm, String reason) {
            this.risk = risk; this.method = method; this.path = path; this.confirm = confirm; this.reason = reason;
        }
        public boolean blocked() { return risk == Risk.BLOCKED; }
        public String url() { return "https://api.github.com" + path; }
    }

    private static final String HOST = "https://api.github.com";
    private static final Set<String> ROOTS = new HashSet<>(Arrays.asList(
        "repos", "user", "users", "orgs", "rate_limit", "search", "notifications", "gists", "issues"));
    /** Areas that change who can access or trigger things: always typed confirmation. */
    private static final Set<String> SECURITY_AREAS = new HashSet<>(Arrays.asList(
        "collaborators", "invitations", "keys", "hooks", "rulesets", "protection", "permissions",
        "environments", "variables", "pages", "rules", "vulnerability-alerts", "automated-security-fixes",
        "dependabot", "teams", "memberships", "members", "interaction-limits", "autolinks",
        "custom-properties", "secrets", "security-advisories", "code-scanning", "oidc", "installations"));
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~:%+,=\\-]{1,200}");
    private static final Pattern REPO_PATCH_RISK = Pattern.compile(
        "\"(private|visibility|archived|name|default_branch|security_and_analysis)\"\\s*:");
    private static final Pattern FORCE = Pattern.compile("\"force\"\\s*:\\s*true");

    private GatewayPolicy() {}

    private static Decision blocked(String method, String reason) {
        return new Decision(Risk.BLOCKED, method, "", "", reason);
    }

    /** Returns a normalized path ("/repos/o/r?x=1") or null when the input is not acceptable. */
    static String normalize(String raw) {
        if (raw == null) return null;
        String p = raw.trim();
        if (p.startsWith(HOST)) p = p.substring(HOST.length());
        if (p.isEmpty() || p.charAt(0) != '/' || p.length() > 600) return null;
        // A query string is data: spaces and non-ASCII characters are percent-encoded and a dangling "?" is dropped,
        // instead of rejecting the whole request; the path itself stays strict.
        int question = p.indexOf('?');
        if (question >= 0) {
            String query = encodeQuery(p.substring(question + 1));
            p = query.isEmpty() ? p.substring(0, question) : p.substring(0, question) + "?" + query;
        }
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x21 || c > 0x7e || c == '\\' || c == '#') return null;
        }
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.contains("..") || lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")
            || lower.contains("%00") || lower.contains("//")) return null;
        return p;
    }

    private static String encodeQuery(String query) {
        StringBuilder out = new StringBuilder();
        for (byte b : query.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 0x21 && c <= 0x7e && c != '\\' && c != '#') out.append((char) c);
            else out.append('%').append(String.format(Locale.ROOT, "%02X", c));
        }
        return out.toString();
    }

    public static Decision classify(String method, String rawPath) { return classify(method, rawPath, null); }

    public static Decision classify(String method, String rawPath, String body) {
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!Arrays.asList("GET", "POST", "PATCH", "PUT", "DELETE").contains(m))
            return blocked(m, "Метод не поддерживается: " + method);
        String path = normalize(rawPath);
        if (path == null) return blocked(m, "Недопустимый путь: разрешён только путь api.github.com без «..», «//», «#» и пробелов");
        int q = path.indexOf('?');
        String bare = q < 0 ? path : path.substring(0, q);
        String[] segs = bare.substring(1).split("/");
        for (String s : segs) if (!SEGMENT.matcher(s).matches()) return blocked(m, "Недопустимый сегмент пути");
        String[] low = new String[segs.length];
        for (int i = 0; i < segs.length; i++) low[i] = segs[i].toLowerCase(Locale.ROOT);
        if (!ROOTS.contains(low[0])) return blocked(m, "Раздел API недоступен агенту: /" + segs[0]);
        String lowerBare = bare.toLowerCase(Locale.ROOT);
        if (lowerBare.contains("/settings/billing") && !"GET".equals(m)) return blocked(m, "Биллинг доступен только для чтения");
        if (lowerBare.endsWith("registration-token") || lowerBare.endsWith("remove-token") || lowerBare.contains("/access_tokens"))
            return blocked(m, "Выпуск токенов недоступен агенту");
        if (low[0].equals("user") && low.length > 1 && !"GET".equals(m)
            && Arrays.asList("keys", "gpg_keys", "ssh_signing_keys", "emails", "email", "migrations").contains(low[1]))
            return blocked(m, "Ключи, e-mail и миграции аккаунта агент не меняет");
        if ("GET".equals(m)) {
            String last = low[low.length - 1];
            if (last.equals("logs") || last.equals("zip") || last.equals("zipball") || last.equals("tarball") || last.equals("raw"))
                return blocked(m, "Для журналов CI и архивов есть отдельные инструменты");
            if (low.length > 3 && low[0].equals("repos") && (low[3].equals("contents") || low[3].equals("git") && low.length > 4 && low[4].equals("blobs")))
                return blocked(m, "Файлы репозитория читаются через repository_read_file (с защитой секретов)");
            return new Decision(Risk.READ, m, path, "", "");
        }
        boolean secrets = Arrays.asList(low).contains("secrets");
        if (secrets && ("PUT".equals(m) || "POST".equals(m) || "PATCH".equals(m)))
            return blocked(m, "Значение секрета нельзя передавать модели: задайте его в настройках GitHub");
        String owner = low[0].equals("repos") && segs.length >= 3 ? segs[1] + "/" + segs[2] : "";
        String fallbackConfirm = low[0].equals("orgs") && segs.length >= 2 ? segs[1] : owner.isEmpty() ? "ПОДТВЕРЖДАЮ" : owner;
        if ("DELETE".equals(m) && low[0].equals("repos") && segs.length == 3)
            return new Decision(Risk.IRREVERSIBLE, m, path, owner, "Удаление репозитория необратимо");
        if (low[0].equals("repos") && segs.length == 4 && low[3].equals("transfer") && "POST".equals(m))
            return new Decision(Risk.IRREVERSIBLE, m, path, owner, "Передача репозитория другому владельцу");
        if (low[0].equals("repos") && segs.length == 3 && "PATCH".equals(m) && body != null && REPO_PATCH_RISK.matcher(body).find())
            return new Decision(Risk.IRREVERSIBLE, m, path, owner, "Смена видимости, имени, архивации или ветки по умолчанию");
        if (low[0].equals("repos") && segs.length >= 6 && low[3].equals("git") && low[4].equals("refs")) {
            String ref = bare.substring(bare.indexOf("/refs/") + 6).toLowerCase(Locale.ROOT);
            if (!"DELETE".equals(m) && body != null && FORCE.matcher(body).find())
                return new Decision(Risk.IRREVERSIBLE, m, path, owner, "Принудительное обновление ссылки (force) может потерять коммиты");
            if ("DELETE".equals(m) && (ref.equals("heads/main") || ref.equals("heads/master")))
                return new Decision(Risk.IRREVERSIBLE, m, path, owner, "Удаление основной ветки");
        }
        if (low[0].equals("user") && low.length > 1 && low[1].equals("installations"))
            return new Decision(Risk.IRREVERSIBLE, m, path, "ПОДТВЕРЖДАЮ", "Изменение доступа GitHub App к репозиториям");
        for (int i = 1; i < low.length; i++)
            if (SECURITY_AREAS.contains(low[i]) && !(low[i].equals("pages") && low.length > 4 && low[4].equals("builds")))
                return new Decision(Risk.IRREVERSIBLE, m, path, fallbackConfirm, "Изменение доступа, безопасности или автоматизации: " + segs[i]);
        if ("DELETE".equals(m)) return new Decision(Risk.DELETE, m, path, "", "Удаление");
        return new Decision(Risk.REVERSIBLE, m, path, "", "");
    }

    /** True when the typed text equals the required token (case-insensitive, trimmed). */
    public static boolean confirmationMatches(String required, String typed) {
        return required != null && !required.isEmpty() && typed != null
            && required.trim().equalsIgnoreCase(typed.trim());
    }
}
