package app.githubble.mobile.ui;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class RepositoryListOptionsTest {
    JSONObject repo(String name,boolean priv,String lang,int stars,String date)throws Exception{return new JSONObject().put("full_name","demo/"+name).put("private",priv).put("language",lang==null?JSONObject.NULL:lang).put("stargazers_count",stars).put("updated_at",date).put("description","Project "+name);}
    JSONArray data()throws Exception{return new JSONArray().put(repo("Beta",true,"Kotlin",9,"2025-01-01T00:00:00Z"))
        .put(repo("Alpha",false,"Python",50,"2024-01-01T00:00:00Z")).put(repo("Gamma",true,"Rust",3,"2026-01-01T00:00:00Z"));}
    @Test public void allThreeOrderingsUseActualMetadata()throws Exception {
        RepositoryListOptions o=new RepositoryListOptions();Set<String> saved=Collections.emptySet();
        assertEquals("demo/Gamma",o.select(data(),saved).get(0).getString("full_name"));
        o.order="name";assertEquals("demo/Alpha",o.select(data(),saved).get(0).getString("full_name"));
        o.order="stars";assertEquals("demo/Alpha",o.select(data(),saved).get(0).getString("full_name"));
    }
    @Test public void visibilityFavoritesAndLanguageCombine()throws Exception {
        RepositoryListOptions o=new RepositoryListOptions();o.visibility="private";o.language="Rust";o.favoritesOnly=true;
        assertEquals(1,o.select(data(),new HashSet<>(Arrays.asList("demo/Gamma","demo/Alpha"))).size());
        assertEquals(0,o.select(data(),Collections.singleton("demo/Alpha")).size());
        o.visibility="public";assertEquals(0,o.select(data(),Collections.singleton("demo/Gamma")).size());
    }
    @Test public void changingOrderAndRefreshingDataKeepSearchAndFilters()throws Exception {
        RepositoryListOptions o=new RepositoryListOptions();o.query="PROJECT BETA";o.visibility="private";o.language="Kotlin";
        for(String sort:new String[]{"updated","name","stars"}){o.order=sort;assertEquals("demo/Beta",o.select(data(),Collections.emptySet()).get(0).getString("full_name"));}
        assertEquals("PROJECT BETA",o.query);assertEquals("private",o.visibility);assertEquals("Kotlin",o.language);
        o.clearFilters();assertEquals("PROJECT BETA",o.query);assertEquals("stars",o.order);
    }
    @Test public void languagesComeFromRepositoriesIncludingMissingLanguage()throws Exception {
        JSONArray data=data().put(repo("NoLang",false,null,0,""));
        List<String> languages=RepositoryListOptions.languages(data);
        assertTrue(languages.contains("Rust"));assertTrue(languages.contains("Python"));assertTrue(languages.contains("Без языка"));
        RepositoryListOptions o=new RepositoryListOptions();o.language="Без языка";assertEquals(1,o.select(data,Collections.emptySet()).size());
    }
    @Test public void missingDatesAndTiedStarsHaveStableOrder()throws Exception {
        JSONArray data=new JSONArray().put(repo("Zulu",false,"Java",2,"invalid")).put(repo("Alpha",false,"Java",2,"").put("pushed_at","2025-01-01T00:00:00Z"));
        RepositoryListOptions o=new RepositoryListOptions();assertEquals("demo/Alpha",o.select(data,Collections.emptySet()).get(0).getString("full_name"));
        o.order="stars";assertEquals("demo/Alpha",o.select(data,Collections.emptySet()).get(0).getString("full_name"));
        assertEquals(0,RepositoryListOptions.updatedAt(data.getJSONObject(0)));
    }
}
