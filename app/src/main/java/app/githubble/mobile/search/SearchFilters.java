package app.githubble.mobile.search;

import java.util.Locale;

/** Serializable-in-memory visual filters. SearchScreen owns persistence/UI. */
public final class SearchFilters {
    public String language = "";
    public int minStars = 0;
    public String updatedAfter = ""; // YYYY-MM-DD
    public String ownerOrOrg = "";   // "user:name", "org:name" or bare login
    public String topic = "";
    public String issueState = "all"; // all/open/closed
    public String issueKind = "all";  // all/issue/pr
    public String peopleKind = "all"; // all/user/org

    public SearchFilters copy() {
        SearchFilters f = new SearchFilters();
        f.language = language; f.minStars = minStars; f.updatedAfter = updatedAfter;
        f.ownerOrOrg = ownerOrOrg; f.topic = topic; f.issueState = issueState;
        f.issueKind = issueKind; f.peopleKind = peopleKind;
        return f;
    }

    public boolean isEmpty(SearchMode mode) {
        switch (mode) {
            case ISSUES_PRS:
                return "all".equals(issueState) && "all".equals(issueKind);
            case PEOPLE:
                return "all".equals(peopleKind) && ownerOrOrg.trim().isEmpty();
            case CODE:
                return language.trim().isEmpty() && ownerOrOrg.trim().isEmpty();
            case REPOSITORIES:
            default:
                return language.trim().isEmpty() && minStars <= 0 && updatedAfter.trim().isEmpty()
                        && ownerOrOrg.trim().isEmpty() && topic.trim().isEmpty();
        }
    }

    public String summary(SearchMode mode) {
        StringBuilder b = new StringBuilder();
        if (mode == SearchMode.REPOSITORIES || mode == SearchMode.CODE) add(b, language.isEmpty()?"":"Язык: "+language);
        if (mode == SearchMode.REPOSITORIES && minStars > 0) add(b, "★ от "+minStars);
        if (mode == SearchMode.REPOSITORIES && !updatedAfter.isEmpty()) add(b, "после "+updatedAfter);
        if ((mode == SearchMode.REPOSITORIES || mode == SearchMode.CODE || mode == SearchMode.PEOPLE) && !ownerOrOrg.isEmpty()) add(b, ownerOrOrg);
        if (mode == SearchMode.REPOSITORIES && !topic.isEmpty()) add(b, "topic: "+topic);
        if (mode == SearchMode.ISSUES_PRS) {
            if (!"all".equals(issueKind)) add(b, "pr".equals(issueKind)?"Pull Request":"Issue");
            if (!"all".equals(issueState)) add(b, "open".equals(issueState)?"Открытые":"Закрытые");
        }
        if (mode == SearchMode.PEOPLE && !"all".equals(peopleKind)) add(b, "org".equals(peopleKind)?"Организации":"Пользователи");
        return b.length()==0 ? "Без фильтров" : b.toString();
    }

    public String qualifiers(SearchMode mode) {
        StringBuilder b = new StringBuilder();
        switch (mode) {
            case REPOSITORIES:
                qualifier(b, "language", language);
                if (minStars > 0) token(b, "stars:>=" + minStars);
                if (validDate(updatedAfter)) token(b, "pushed:>=" + updatedAfter);
                ownerQualifier(b, ownerOrOrg);
                qualifier(b, "topic", topic);
                break;
            case ISSUES_PRS:
                if ("issue".equals(issueKind)) token(b, "is:issue");
                else if ("pr".equals(issueKind)) token(b, "is:pr");
                if ("open".equals(issueState)) token(b, "is:open");
                else if ("closed".equals(issueState)) token(b, "is:closed");
                break;
            case CODE:
                qualifier(b, "language", language);
                ownerQualifier(b, ownerOrOrg);
                break;
            case PEOPLE:
                if ("user".equals(peopleKind)) token(b, "type:user");
                else if ("org".equals(peopleKind)) token(b, "type:org");
                if (!ownerOrOrg.trim().isEmpty()) {
                    String clean = clean(ownerOrOrg).replaceFirst("^(user|org):", "");
                    if (!clean.isEmpty()) token(b, clean + " in:login");
                }
                break;
        }
        return b.toString().trim();
    }

    private static void ownerQualifier(StringBuilder b, String raw) {
        String v = clean(raw);
        if (v.isEmpty()) return;
        if (v.startsWith("org:") || v.startsWith("user:")) token(b, v);
        else token(b, "user:" + v);
    }
    private static void qualifier(StringBuilder b, String name, String value) {
        String v = clean(value); if (!v.isEmpty()) token(b, name + ":" + quoteIfNeeded(v));
    }
    private static String quoteIfNeeded(String v) { return v.indexOf(' ') >= 0 ? '"' + v + '"' : v; }
    private static String clean(String v) { return v == null ? "" : v.trim().replaceAll("\\s+", " "); }
    private static boolean validDate(String v) { return v != null && v.matches("\\d{4}-\\d{2}-\\d{2}"); }
    private static void token(StringBuilder b, String value) { if (b.length()>0) b.append(' '); b.append(value); }
    private static void add(StringBuilder b, String v) { if (v==null||v.isEmpty()) return; if (b.length()>0)b.append(" · "); b.append(v); }
}
