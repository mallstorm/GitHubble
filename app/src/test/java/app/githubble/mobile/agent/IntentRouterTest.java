package app.githubble.mobile.agent;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class IntentRouterTest {
    private static JSONArray chat(String... roleBody) throws Exception {
        JSONArray log = new JSONArray();
        for (int i = 0; i < roleBody.length; i += 2) log.put(new JSONObject().put("role", roleBody[i]).put("body", roleBody[i + 1]));
        return log;
    }
    private static final String LIST = "GitHub @mallstorm: доступно 3 репозиториев.\n\n1. mallstorm/GitHubble · приватный\n2. mallstorm/OmniChat · приватный\n3. ntl-nam/GPTDrive · публичный";

    @Test public void choosingARepositoryIsNotAConversation() {
        assertEquals(IntentRouter.Kind.PICK_REPO, IntentRouter.classify("возьмем этот репозиторий"));
        assertEquals(IntentRouter.Kind.PICK_REPO, IntentRouter.classify("Давай возьмём первый"));
        assertEquals(IntentRouter.Kind.PICK_REPO, IntentRouter.classify("mallstorm/GitHubble"));
        assertEquals(IntentRouter.Kind.CONTINUE, IntentRouter.classify("Продолжай"));
        assertEquals(IntentRouter.Kind.TASK, IntentRouter.classify("исправь баг в авторизации"));
        assertEquals(IntentRouter.Kind.QUESTION, IntentRouter.classify("почему упала сборка?"));
    }
    @Test public void firstFromTheListIsResolvedByTheHost() throws Exception {
        IntentRouter.Route route = IntentRouter.route("возьмём первый", chat("user", "покажи репозитории", "agent", LIST), "");
        assertEquals("mallstorm/GitHubble", route.repository);
        assertEquals("ntl-nam/GPTDrive", IntentRouter.route("возьмём последний", chat("agent", LIST), "").repository);
        assertEquals("mallstorm/OmniChat", IntentRouter.route("давай второй", chat("agent", LIST), "").repository);
    }
    @Test public void thisMeansTheRepositoryJustMentioned() throws Exception {
        JSONArray log = chat("user", "что с mallstorm/OmniChat?", "agent", "В mallstorm/OmniChat последний коммит вчера.");
        assertEquals("mallstorm/OmniChat", IntentRouter.route("возьмем этот репозиторий", log, "").repository);
        assertEquals("mallstorm/GitHubble", IntentRouter.route("возьмем этот репозиторий", chat(), "mallstorm/GitHubble").repository);
    }
    @Test public void thisAfterALongListIsAmbiguousAndOffersCandidates() throws Exception {
        IntentRouter.Route route = IntentRouter.route("возьмем этот репозиторий", chat("agent", LIST), "");
        assertTrue(route.ambiguous());
        assertEquals(Arrays.asList("mallstorm/GitHubble", "mallstorm/OmniChat", "ntl-nam/GPTDrive"), route.candidates);
    }
    @Test public void explicitNameAndLinkWinAndPathsAreNotRepositories() throws Exception {
        assertEquals("a/b", IntentRouter.route("/repo a/b", chat(), "").repository);
        assertEquals("foo/bar", IntentRouter.route("возьми https://github.com/foo/bar.git", chat(), "").repository);
        assertTrue(IntentRouter.repositories("смотри app/src и src/main/Foo.java, CI/CD и и/или").isEmpty());
    }
    @Test public void onlyTasksNeedTheReviewer() {
        assertTrue(IntentRouter.changesRepository("добавь тесты"));
        assertFalse(IntentRouter.changesRepository("что в этом репозитории?"));
    }
    @Test public void sessionFocusCarriesBindingAndRecentRepositories() throws Exception {
        SessionFocus focus = SessionFocus.of(new AgentProjectStore.Project("a", "n", "mallstorm/GitHubble", "dev", ""), chat("agent", LIST));
        String block = focus.contextBlock();
        assertTrue(block.contains("Focus: mallstorm/GitHubble @ dev"));
        assertTrue(block.contains("mallstorm/OmniChat"));
        assertTrue(SessionFocus.of(null, chat()).contextBlock().contains("focus_repository"));
    }
}
