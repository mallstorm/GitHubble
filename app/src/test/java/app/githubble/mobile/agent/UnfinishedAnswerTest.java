package app.githubble.mobile.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UnfinishedAnswerTest {
    @Test public void headerOnlyTableIsUnfinished() {
        assertTrue(AgentController.unfinishedAnswer("Нашёл Funding Radar\n\n| Репозиторий | Доступ | Стек |\n|---|---|---|"));
        assertTrue(AgentController.unfinishedAnswer("Итог:\n| a | b |\n| --- | --- |\n"));
    }

    @Test public void brokenRowAndTrailingColonAreUnfinished() {
        assertTrue(AgentController.unfinishedAnswer("| a | b |\n|---|---|\n| 1 | 2"));
        assertTrue(AgentController.unfinishedAnswer("Нашёл репозиторий, вот сводка:"));
    }

    @Test public void completeAnswersPass() {
        assertFalse(AgentController.unfinishedAnswer("| a | b |\n|---|---|\n| 1 | 2 |"));
        assertFalse(AgentController.unfinishedAnswer("Готово. Репозиторий найден: mallstorm/Funding-Radar."));
        assertFalse(AgentController.unfinishedAnswer(""));
        assertFalse(AgentController.unfinishedAnswer(null));
    }
}
