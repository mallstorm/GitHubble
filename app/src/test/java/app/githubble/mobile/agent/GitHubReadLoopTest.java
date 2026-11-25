package app.githubble.mobile.agent;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GitHubReadLoopTest {
    @Test public void detectsRepositoryQuestionsWithoutRunningAnAI(){
        assertTrue(GitHubReadLoop.isDirectRepositoryQuestion("сколько репозиториев ты видишь на аккаунте"));
        assertTrue(GitHubReadLoop.isDirectRepositoryQuestion("покажи мои репозитории"));
        assertTrue(GitHubReadLoop.isDirectRepositoryQuestion("what repositories can you see? list them"));
        assertTrue(GitHubReadLoop.isDirectRepositoryQuestion("/repos"));
        assertTrue(GitHubReadLoop.isDirectRepositoryQuestion("/github whoami"));
    }
    @Test public void doesNotInterceptMutatingCommandsOrNormalChat(){
        assertFalse(GitHubReadLoop.isDirectRepositoryQuestion("привет"));
        assertFalse(GitHubReadLoop.isDirectRepositoryQuestion("создай репозиторий test"));
        assertFalse(GitHubReadLoop.isDirectRepositoryQuestion("удали репозиторий user/test"));
        assertFalse(GitHubReadLoop.isDirectRepositoryQuestion("опубликуй workspace в github"));
        assertFalse(GitHubReadLoop.isDirectRepositoryQuestion(null));
    }
    private static org.json.JSONObject sample()throws Exception{
        org.json.JSONArray a=new org.json.JSONArray().put(new org.json.JSONObject().put("full_name","mallstorm/OmniChat").put("private",true))
            .put(new org.json.JSONObject().put("full_name","mallstorm/GitHubble").put("private",false));
        org.json.JSONArray b=new org.json.JSONArray().put(new org.json.JSONObject().put("full_name","marwell4/OmniChat").put("private",true))
            .put(new org.json.JSONObject().put("full_name","mallstorm/GitHubble").put("private",false));
        return new org.json.JSONObject().put("total_repositories",4).put("accounts",new org.json.JSONArray()
            .put(new org.json.JSONObject().put("login","mallstorm").put("active",true).put("count",2).put("repositories",a))
            .put(new org.json.JSONObject().put("login","marwell4").put("count",2).put("repositories",b)));
    }
    @Test public void comparisonIsAMarkdownTableOfProjectsByAccount()throws Exception{
        String text=GitHubReadLoop.allAccountsAnswer(sample(),false,true);
        assertTrue(text,text.contains("| Проект | mallstorm | marwell4 |"));
        assertTrue(text,text.contains("| OmniChat | ✅ | ✅ |"));
        assertTrue(text,text.contains("| GitHubble | ✅ | 🔗 |"));
        assertTrue(text,text.contains("| @mallstorm (активный) | 2 · приватных 1 |"));
    }
    @Test public void listingIsATablePerAccount()throws Exception{
        String text=GitHubReadLoop.allAccountsAnswer(sample(),false,false);
        assertTrue(text,text.contains("## @marwell4 — 2"));
        assertTrue(text,text.contains("| marwell4/OmniChat | приватный |"));
        assertFalse(text,text.contains("• "));
    }
    @Test public void recognisesComparisonWording(){
        assertTrue(GitHubReadLoop.wantsComparison("Сравни все репозитории и какие проекты на каком аккаунте есть. Табличный вид."));
        assertFalse(GitHubReadLoop.wantsComparison("покажи мои репозитории"));
    }
    @Test public void findsTheNamedAccountEvenWhenSpelledInRussian()throws Exception{
        org.json.JSONArray accounts=sample().getJSONArray("accounts");
        assertEquals("mallstorm",GitHubReadLoop.namedAccount("Сколько репозиториев на аккаунте Малстором?",accounts));
        assertEquals("mallstorm",GitHubReadLoop.namedAccount("сколько репозиториев у Малтсторм",accounts));
        assertEquals("marwell4",GitHubReadLoop.namedAccount("репозитории @marwell4",accounts));
        assertEquals("",GitHubReadLoop.namedAccount("Сколько репозиториев на аккаунте?",accounts));
        assertEquals("",GitHubReadLoop.namedAccount("Сравни все репозитории и какие проекты на каком аккаунте есть. Табличный вид.",accounts));
    }
    @Test public void accountMatrixIntentNeedsRepositoriesAndAccounts(){
        assertTrue(GitHubReadLoop.wantsAccountMatrix("Проанализируй сколько аккаунтов подключенных ты видишь и сопоставь все репозитории"));
        assertTrue(GitHubReadLoop.wantsAccountMatrix("Сравни все репозитории и какие проекты на каком аккаунте есть"));
        assertFalse(GitHubReadLoop.wantsAccountMatrix("Сравни репозитории owner/a и owner/b по коду"));
        assertFalse(GitHubReadLoop.wantsAccountMatrix("покажи мои репозитории"));
        assertTrue(GitHubReadLoop.isDirectRepositoryQuestion("Сопоставь репозитории по всем аккаунтам"));
        assertTrue(DirectAnswers.handles("Проанализируй сколько аккаунтов подключенных ты видишь и сопоставь все репозитории"));
    }
}
