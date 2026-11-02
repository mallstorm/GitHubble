package app.githubble.mobile.agent;

import org.junit.Test;
import static org.junit.Assert.*;

/** Guards against accidentally treating normal Copilot chat text as a cloud task. */
public final class CopilotModePolicyTest {
    @Test public void greetingsAndQuestionsAreNeverCloudTasks() {
        assertFalse(CopilotModePolicy.isExplicitCloudTask("привет"));
        assertFalse(CopilotModePolicy.isExplicitCloudTask("Привет, как дела?"));
        assertFalse(CopilotModePolicy.isExplicitCloudTask("What is a pull request?"));
        assertFalse(CopilotModePolicy.isExplicitCloudTask(null));
    }

    @Test public void onlyExplicitDevelopmentActionsCanLaunchCloudTasks() {
        assertTrue(CopilotModePolicy.isExplicitCloudTask("/copilot исправь экран логина"));
        assertTrue(CopilotModePolicy.isExplicitCloudTask("реализуй функцию экспорта настроек"));
        assertTrue(CopilotModePolicy.isExplicitCloudTask("implement feature for repository"));
        assertTrue(CopilotModePolicy.isExplicitCloudTask("/task write tests"));
        assertFalse(CopilotModePolicy.isExplicitCloudTask("/copilot "));
    }

    @Test public void taskBodyStripsOnlyExplicitCommandPrefix() {
        assertEquals("исправь экран",CopilotModePolicy.taskBody("/copilot исправь экран"));
        assertEquals("write tests",CopilotModePolicy.taskBody("/task write tests"));
        assertEquals("реализуй функцию",CopilotModePolicy.taskBody("реализуй функцию"));
    }
}
