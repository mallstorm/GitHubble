package app.githubble.mobile.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Host-side reading of what the user means. It never grants anything: it only decides which repository the
 * words «этот / первый / тот» point at, and whether the message is a question, a task or "go on".
 */
public final class IntentRouter {
    public enum Kind { QUESTION, TASK, PICK_REPO, CONTINUE }

    public static final class Route {
        public final Kind kind;
        /** Resolved owner/repo, or "" when the message names no repository or it is ambiguous. */
        public final String repository;
        /** Several equally plausible repositories (the user said «этот» after a list). */
        public final List<String> candidates;
        Route(Kind kind, String repository, List<String> candidates) {
            this.kind = kind; this.repository = repository; this.candidates = candidates;
        }
        public boolean ambiguous() { return repository.isEmpty() && !candidates.isEmpty(); }
    }

    private IntentRouter() {}

    private static final Pattern GITHUB_URL = Pattern.compile("github\\.com/([A-Za-z0-9][A-Za-z0-9_.-]{0,38})/([A-Za-z0-9_.-]{1,100}?)(?:\\.git)?(?=$|[\\s/?#)\\]>.,;:!])");
    private static final Pattern REPO = Pattern.compile("(?<![\\w./:@-])([A-Za-z0-9][A-Za-z0-9_.-]{0,38})/([A-Za-z0-9_.-]{1,100})(?![\\w/-])");
    private static final java.util.Set<String> NOT_OWNERS = new java.util.HashSet<>(java.util.Arrays.asList(
        "src", "app", "main", "docs", "doc", "lib", "libs", "test", "tests", "build", "gradle", "res", "assets", "bin", "scripts",
        "ci", "cd", "tcp", "udp", "input", "output", "yes", "no", "and", "or", "http", "https", "java", "kotlin", "android",
        "values", "layout", "drawable", "mipmap", "androidtest", "debug", "release", "github", "workflows", "etc", "usr", "var", "tmp"));
    private static final Pattern FILE_TAIL = Pattern.compile("(?i).*\\.(java|kt|kts|xml|md|json|yml|yaml|gradle|png|jpg|svg|txt|js|ts|py|html|css|sh|toml|properties|apk|zip|so|cpp|c|h)$");
    private static final Pattern CONTINUE = Pattern.compile("(?iu)^\\s*(?:продолж\\p{L}*|дальше|давай\\s+дальше|continue|go\\s+on|resume|ещё|еще)[\\s.!]*$");
    private static final Pattern PICK_VERB = Pattern.compile("(?iu).*(?:возьм|берём|берем|бери|закреп|привяж|/repo|выбира|выбер|работа(?:ем|ть)\\s+с|давай\\s+(?:этот|эту|его|тот|ту|перв|втор|трет)|откро|открой|перейд|переключ|используем|займ[её]мся|займись|начн[её]м|погнали|\\btake\\b|\\bpick\\b|\\buse\\b|\\bopen\\b|switch\\s+to|let'?s\\s+(?:take|use|work)).*");
    private static final Pattern TASK_VERB = Pattern.compile("(?iu).*(?:исправ|измен|добав|созда|собер|собра|реализ|напиш|удали|переимен|перепиш|рефактор|почин|внедр|обнов|залей|запушь|запуш|\\bfix\\b|\\badd\\b|\\bimplement\\b|\\bbuild\\b|\\bcreate\\b|\\brefactor\\b|\\bpush\\b|\\bwrite\\b|\\bdelete\\b|\\brename\\b).*");
    private static final Pattern DEMONSTRATIVE = Pattern.compile("(?iu).*(?:\\bэтот\\b|\\bэту\\b|\\bэто\\b|\\bтот\\b|\\bту\\b|\\bего\\b|репозитор|проект|\\bthis\\b|\\bthat\\b|\\bit\\b).*");
    private static final String[][] ORDINALS = {
        {"перв", "1"}, {"втор", "2"}, {"трет", "3"}, {"четв", "4"}, {"пят", "5"}, {"шест", "6"}, {"седьм", "7"}, {"восьм", "8"}, {"девят", "9"}, {"десят", "10"}};

    /** Ordered, de-duplicated owner/repo names found in free text. Cheap filter only; the API validates later. */
    public static List<String> repositories(String text) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>();
        Matcher url = GITHUB_URL.matcher(text);
        while (url.find()) found.add(url.group(1) + "/" + url.group(2));
        Matcher m = REPO.matcher(text);
        while (m.find()) {
            String owner = m.group(1), name = m.group(2);
            while (name.endsWith(".") || name.endsWith("-")) name = name.substring(0, name.length() - 1);
            if (name.isEmpty() || NOT_OWNERS.contains(owner.toLowerCase(Locale.ROOT)) || FILE_TAIL.matcher(name).matches()) continue;
            if (name.equals(".") || name.equals("..")) continue;
            found.add(owner + "/" + name);
        }
        return new ArrayList<>(found);
    }

    public static Kind classify(String message) {
        if (message == null) return Kind.QUESTION;
        String text = message.trim();
        if (CONTINUE.matcher(text).matches()) return Kind.CONTINUE;
        if (text.startsWith("/build") || text.startsWith("/repair") || text.startsWith("/edit") || text.startsWith("/workspace")) return Kind.TASK;
        if (TASK_VERB.matcher(text).matches()) return Kind.TASK;
        if (isPick(text)) return Kind.PICK_REPO;
        return Kind.QUESTION;
    }

    private static boolean isPick(String text) {
        if (text.length() > 240) return false;
        List<String> named = repositories(text);
        if (!named.isEmpty() && text.length() <= 120 && (PICK_VERB.matcher(text).matches() || text.replaceAll("\\s", "").equals(named.get(0)))) return true;
        return PICK_VERB.matcher(text).matches() && (DEMONSTRATIVE.matcher(text).matches() || ordinal(text) != 0);
    }

    static int ordinal(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String[] entry : ORDINALS) if (lower.contains(entry[0])) return Integer.parseInt(entry[1]);
        Matcher digits = Pattern.compile("(?:№|#|номер\\s*)(\\d{1,2})|\\b(\\d{1,2})-?(?:й|ый|ой|ий)\\b").matcher(lower);
        if (digits.find()) return Integer.parseInt(digits.group(1) != null ? digits.group(1) : digits.group(2));
        if (lower.contains("последн")) return -1;
        return 0;
    }

    /**
     * @param messages the conversation as stored: objects with role/body, oldest first.
     * @param focus    the repository currently in focus, or "".
     */
    public static Route route(String message, JSONArray messages, String focus) {
        Kind kind = classify(message);
        String text = message == null ? "" : message.trim();
        if (kind == Kind.CONTINUE) return new Route(kind, "", new ArrayList<>());
        List<String> named = repositories(text);
        if (!named.isEmpty()) return new Route(kind, named.get(0), new ArrayList<>());
        boolean refersToRepository = kind == Kind.PICK_REPO || (kind == Kind.TASK && PICK_VERB.matcher(text).matches() && DEMONSTRATIVE.matcher(text).matches());
        if (!refersToRepository) return new Route(kind, "", new ArrayList<>());
        int nth = ordinal(text);
        // Newest first; the current message is not yet in the stored log, but guard against it anyway.
        for (int i = (messages == null ? 0 : messages.length()) - 1; i >= 0; i--) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null) continue;
            String role = item.optString("role"), body = item.optString("body");
            if (role.equals("system")) continue;
            List<String> list = repositories(body);
            if (list.isEmpty()) continue;
            if (nth != 0) {
                if (list.size() < 2 && nth != 1 && nth != -1) continue;
                int index = nth == -1 ? list.size() - 1 : nth - 1;
                if (index >= 0 && index < list.size()) return new Route(kind, list.get(index), new ArrayList<>());
                continue;
            }
            if (list.size() == 1) return new Route(kind, list.get(0), new ArrayList<>());
            if (role.equals("user")) return new Route(kind, list.get(0), new ArrayList<>());
            return new Route(kind, "", new ArrayList<>(list.subList(0, Math.min(5, list.size()))));
        }
        if (focus != null && !focus.isEmpty()) return new Route(kind, focus, new ArrayList<>());
        return new Route(kind, "", new ArrayList<>());
    }

    /** True for tasks that change something; simple questions never need a reviewer pass. */
    public static boolean changesRepository(String message) { return classify(message) == Kind.TASK; }
}
