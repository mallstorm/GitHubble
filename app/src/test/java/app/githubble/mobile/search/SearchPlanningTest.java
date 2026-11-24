package app.githubble.mobile.search;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class SearchPlanningTest {
    private static JSONObject repo(String name, int stars, String description) throws Exception {
        return new JSONObject().put("full_name", name).put("stargazers_count", stars)
                .put("description", description).put("updated_at", "2001-01-01T00:00:00Z");
    }

    @Test public void qualifiersInvalidForTheSelectedSurfaceAreRejectedNotSent() {
        AdvancedSearchPlanner.Plan plan = AdvancedSearchPlanner.plan(
                "okhttp stars:>10 is:open path:src", false, SearchMode.CODE, null);
        assertTrue(plan.rejectedQualifiers.toString(), plan.rejectedQualifiers.contains("stars:>10"));
        assertTrue(plan.rejectedQualifiers.contains("is:open"));
        assertEquals(1, plan.variants.size());
        assertEquals("okhttp path:src", plan.variants.get(0));
        assertTrue(AdvancedSearchPlanner.qualifierAllowed(SearchMode.REPOSITORIES, "props.team"));
        assertFalse(AdvancedSearchPlanner.qualifierAllowed(SearchMode.CODE, "props.team"));
    }

    @Test public void boolOperatorsAndQuotedPhrasesSurviveSanitising() {
        AdvancedSearchPlanner.Plan plan = AdvancedSearchPlanner.plan(
                "\"http client\" AND android -language:kotlin", false, SearchMode.REPOSITORIES, null);
        assertTrue(plan.rejectedQualifiers.isEmpty());
        assertEquals("\"http client\" AND android -language:kotlin", plan.variants.get(0));
    }

    @Test public void filtersBecomeQualifiersAndEmptyQueryStillSearchesByFilters() {
        SearchFilters f = new SearchFilters();
        f.language = "Visual Basic"; f.minStars = 50; f.updatedAfter = "2025-01-31"; f.ownerOrOrg = "square";
        AdvancedSearchPlanner.Plan plan = AdvancedSearchPlanner.plan("", false, SearchMode.REPOSITORIES, f);
        assertEquals("language:\"Visual Basic\" stars:>=50 pushed:>=2025-01-31 user:square", plan.variants.get(0));
        f.updatedAfter = "yesterday";
        assertFalse(f.qualifiers(SearchMode.REPOSITORIES).contains("pushed"));
        assertTrue(f.copy().qualifiers(SearchMode.REPOSITORIES).contains("stars:>=50"));
    }

    @Test public void filtersAreModeSpecific() {
        SearchFilters f = new SearchFilters();
        f.issueKind = "pr"; f.issueState = "closed"; f.language = "java";
        assertEquals("is:pr is:closed", f.qualifiers(SearchMode.ISSUES_PRS));
        assertFalse(f.isEmpty(SearchMode.ISSUES_PRS));
        f.peopleKind = "org"; f.ownerOrOrg = "org:google";
        assertEquals("type:org google in:login", f.qualifiers(SearchMode.PEOPLE));
        assertEquals("Без фильтров", new SearchFilters().summary(SearchMode.CODE));
    }

    @Test public void russianQueryIsExpandedOnlyWhenSmartSearchIsOn() {
        SmartSearchPlanner.Plan off = SmartSearchPlanner.plan("клиент для телеграм", false);
        assertEquals(1, off.variants.size());
        assertFalse(off.expanded());
        SmartSearchPlanner.Plan on = SmartSearchPlanner.plan("клиент для телеграм", true);
        assertTrue(on.hasCyrillic);
        assertTrue(on.variants.toString(), on.variants.size() >= 1);
        assertTrue(on.rankingTerms.size() > 0);
    }

    @Test public void qualifiersInTheQueryAreKeptVerbatimBySmartPlanner() {
        SmartSearchPlanner.Plan plan = SmartSearchPlanner.plan("клиент language:kotlin", true);
        for (String variant : plan.variants) assertTrue(variant, variant.contains("language:kotlin"));
    }

    @Test public void rankerMergesDuplicatesAndPrefersReposFoundByManyVariants() throws Exception {
        JSONArray first = new JSONArray().put(repo("a/popular", 5000, "x")).put(repo("b/both", 10, "x"));
        JSONArray second = new JSONArray().put(repo("B/Both", 10, "x")).put(repo("c/one", 20, "x"))
                .put(repo("b/both", 10, "x"));
        List<RepositorySearchRanker.Hit> hits = RepositorySearchRanker.merge(
                Arrays.asList(first, null, second), SmartSearchPlanner.plan("both", true));
        assertEquals(3, hits.size());
        assertEquals("b/both", hits.get(0).repository.getString("full_name"));
        assertEquals("duplicate inside one batch counts once", 2, hits.get(0).matchedVariants);
        assertEquals(0, RepositorySearchRanker.merge(null, null).size());
    }
}
