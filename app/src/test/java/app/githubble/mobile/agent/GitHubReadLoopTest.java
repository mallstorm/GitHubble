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
}
