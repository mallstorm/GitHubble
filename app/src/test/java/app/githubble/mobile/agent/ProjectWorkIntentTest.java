package app.githubble.mobile.agent;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectWorkIntentTest {
    @Test public void positiveDevelopmentTasksOpenReview(){
        assertTrue(ProjectWorkIntent.requestsFileProposal("/edit src/Main.kt"));
        assertTrue(ProjectWorkIntent.requestsFileProposal("Добавь экран настроек"));
        assertTrue(ProjectWorkIntent.requestsFileProposal("Реализуй вход по OAuth"));
        assertTrue(ProjectWorkIntent.requestsFileProposal("исправь ошибку компиляции"));
        assertTrue(ProjectWorkIntent.requestsFileProposal("доработай приложение"));
        assertTrue(ProjectWorkIntent.requestsFileProposal("перепиши класс"));
    }
    @Test public void questionsRemainInChat(){
        assertFalse(ProjectWorkIntent.requestsFileProposal("Привет"));
        assertFalse(ProjectWorkIntent.requestsFileProposal("как добавить кнопку?"));
        assertFalse(ProjectWorkIntent.requestsFileProposal("Почему сборка падает?"));
        assertFalse(ProjectWorkIntent.requestsFileProposal("Покажи README"));
        assertFalse(ProjectWorkIntent.requestsFileProposal("Можно изменить UI?"));
    }
}
