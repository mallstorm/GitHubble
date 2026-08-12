package app.githubble.mobile.search;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mode-aware query compiler. It keeps only qualifiers valid for the selected GitHub search surface,
 * then delegates Russian semantic expansion to SmartSearchPlanner and appends visual filters.
 */
public final class AdvancedSearchPlanner {
    private static final Pattern TOKEN = Pattern.compile("\\\"[^\\\"]+\\\"|\\S+");
    private static final Pattern QUALIFIER = Pattern.compile("^(-?)([A-Za-z][A-Za-z0-9_.-]*):(.*)$");

    private static final Set<String> REPO = set("in","repo","user","org","size","followers","forks","fork","created","pushed","stars","language","topic","topics","license","mirror","archived","is","visibility","template","has","good-first-issues","help-wanted-issues","deployable","deployed");
    private static final Set<String> ISSUES = set("in","author","assignee","mentions","commenter","involves","team","state","is","label","milestone","project","status","reason","linked","draft","review","reviewed-by","review-requested","user-review-requested","team-review-requested","user","org","repo","language","created","updated","closed","comments","reactions","interactions","type","has","no");
    private static final Set<String> CODE = set("in","repo","user","org","language","path","extension","filename","size","fork");
    private static final Set<String> PEOPLE = set("in","type","repos","followers","location","language","created","user","org","fullname","is");

    public static final class Plan {
        public final SearchMode mode;
        public final String original;
        public final SmartSearchPlanner.Plan semantic;
        public final List<String> variants;
        public final List<String> rejectedQualifiers;
        public final SearchFilters filters;
        Plan(SearchMode mode, String original, SmartSearchPlanner.Plan semantic, List<String> variants,
             List<String> rejectedQualifiers, SearchFilters filters) {
            this.mode=mode;this.original=original;this.semantic=semantic;
            this.variants=Collections.unmodifiableList(variants);
            this.rejectedQualifiers=Collections.unmodifiableList(rejectedQualifiers);
            this.filters=filters.copy();
        }
        public boolean expanded(){return semantic.expanded();}
    }

    public static Plan plan(String raw, boolean smart, SearchMode mode, SearchFilters filters) {
        if (mode == null) mode = SearchMode.REPOSITORIES;
        if (filters == null) filters = new SearchFilters();
        String original = clean(raw);
        Sanitized sanitized = sanitize(original, mode);
        SmartSearchPlanner.Plan base = SmartSearchPlanner.plan(sanitized.query, smart);
        String visual = filters.qualifiers(mode);
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        for (String v : base.variants) {
            String q = clean(v + (visual.isEmpty()?"":" " + visual));
            if (!q.isEmpty()) variants.add(q);
        }
        if (variants.isEmpty() && !visual.isEmpty()) variants.add(visual);
        return new Plan(mode, original, base, new ArrayList<>(variants), sanitized.rejected, filters);
    }

    public static boolean qualifierAllowed(SearchMode mode, String name) {
        String n=name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (mode==SearchMode.REPOSITORIES && n.startsWith("props.")) return true;
        if (mode==SearchMode.ISSUES_PRS && n.startsWith("field.")) return true;
        return allowed(mode).contains(n);
    }

    private static final class Sanitized {
        final String query; final List<String> rejected;
        Sanitized(String query,List<String> rejected){this.query=query;this.rejected=rejected;}
    }

    private static Sanitized sanitize(String raw, SearchMode mode) {
        List<String> kept = new ArrayList<>(), rejected = new ArrayList<>();
        Matcher m = TOKEN.matcher(raw==null?"":raw);
        Set<String> allowed = allowed(mode);
        while(m.find()) {
            String token=m.group();String upper=token.toUpperCase(Locale.ROOT);
            if("AND".equals(upper)||"OR".equals(upper)||"NOT".equals(upper)){kept.add(token);continue;}
            Matcher q=QUALIFIER.matcher(token);
            if(!q.matches()){kept.add(token);continue;}
            String name=q.group(2).toLowerCase(Locale.ROOT);
            boolean dynamic=(mode==SearchMode.REPOSITORIES && name.startsWith("props.")) || (mode==SearchMode.ISSUES_PRS && name.startsWith("field."));
            if(allowed.contains(name) || dynamic) kept.add(token); else rejected.add(token);
        }
        return new Sanitized(clean(String.join(" ",kept)),rejected);
    }

    private static Set<String> allowed(SearchMode mode) {
        switch(mode){case ISSUES_PRS:return ISSUES;case CODE:return CODE;case PEOPLE:return PEOPLE;case REPOSITORIES:default:return REPO;}
    }
    private static Set<String> set(String...v){return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(v)));}
    private static String clean(String s){return s==null?"":s.trim().replaceAll("\\s+"," ");}
}
