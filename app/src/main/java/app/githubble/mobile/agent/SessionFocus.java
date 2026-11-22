package app.githubble.mobile.agent;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the agent is working on right now. The binding itself (repository + branch) lives in the task/project
 * record, and recently mentioned repositories are derived from the durable chat log, so the focus survives
 * a restart without a second copy that could drift.
 */
public final class SessionFocus {
    public final String repository, branch;
    public final List<String> mentioned;

    private SessionFocus(String repository, String branch, List<String> mentioned) {
        this.repository = repository == null ? "" : repository;
        this.branch = branch == null ? "" : branch;
        this.mentioned = mentioned;
    }

    public static SessionFocus of(AgentProjectStore.Project project, JSONArray messages) {
        List<String> recent = new ArrayList<>();
        for (int i = (messages == null ? 0 : messages.length()) - 1; i >= 0 && recent.size() < 8; i--) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null || "system".equals(item.optString("role"))) continue;
            for (String repo : IntentRouter.repositories(item.optString("body")))
                if (!recent.contains(repo) && recent.size() < 8) recent.add(repo);
        }
        return new SessionFocus(project == null ? "" : project.repository, project == null ? "" : project.branch, recent);
    }

    /** The block handed to the model on every turn. */
    public String contextBlock() {
        StringBuilder text = new StringBuilder("SESSION FOCUS (host state, trusted):\n");
        if (repository.isEmpty()) text.append("- No repository is in focus yet. When the user picks or names one, call focus_repository.\n");
        else text.append("- Focus: ").append(repository).append(" @ ").append(branch.isEmpty() ? "default branch" : branch).append("\n");
        if (!mentioned.isEmpty()) text.append("- Repositories mentioned recently (newest first): ").append(String.join(", ", mentioned)).append("\n");
        return text.toString();
    }
}
