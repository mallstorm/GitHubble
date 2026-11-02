package app.githubble.mobile.agent;

import java.util.Locale;

/** Separates a conversational message from an explicit Copilot Cloud task.
 * Cloud tasks create GitHub work; they must not silently treat chat greetings
 * as requests to create issues or pull requests. */
public final class CopilotModePolicy {
    private CopilotModePolicy() {}

    public static boolean isExplicitCloudTask(String prompt) {
        if (prompt == null) return false;
        String input=prompt.trim();
        if(input.length()<12) return false;
        String lower=input.toLowerCase(Locale.ROOT);
        if(lower.startsWith("/copilot ")||lower.startsWith("/task ")||lower.startsWith("/задача "))return true;
        return lower.matches("(?s)^(?:реализуй|реализовать|исправь|исправить|создай (?:функцию|тесты|код|pull request|pr)|"+
                "сделай (?:коммит|pr|pull request)|обнови (?:документацию|код|тесты)|"+
                "добавь (?:функцию|тесты)|запусти (?:агента|задачу)|fix |implement |"+
                "create (?:a |an )?(?:pull request|pr|feature|test)|refactor ).*");
    }

    public static String taskBody(String prompt) {
        if (prompt == null) return "";
        String result=prompt.trim().replaceFirst("(?is)^/(?:copilot|task|задача)\\s+","");
        return result;
    }
}
