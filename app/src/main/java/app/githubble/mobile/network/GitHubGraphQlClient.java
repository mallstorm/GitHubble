package app.githubble.mobile.network;

import org.json.JSONArray;
import org.json.JSONObject;

/** Minimal GitHub GraphQL client built on the app's authenticated HTTP layer. */
public final class GitHubGraphQlClient {
    private static final String ENDPOINT = "https://api.github.com/graphql";
    private final GitHubApiClient api;

    public GitHubGraphQlClient(GitHubApiClient api) {
        this.api = api;
    }

    public JSONObject execute(String query) throws Exception {
        return execute(query, new JSONObject());
    }

    public JSONObject execute(String query, JSONObject variables) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("query", query);
        payload.put("variables", variables == null ? new JSONObject() : variables);
        JSONObject root = api.postJson(ENDPOINT, payload, true);
        JSONArray errors = root.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            StringBuilder message = new StringBuilder();
            for (int i = 0; i < errors.length(); i++) {
                JSONObject error = errors.optJSONObject(i);
                if (error == null) continue;
                if (message.length() > 0) message.append("; ");
                message.append(error.optString("message", "Ошибка GitHub GraphQL"));
            }
            throw new GitHubApiClient.ApiException(422,
                    message.length() == 0 ? "Ошибка GitHub GraphQL" : message.toString(),
                    api.getResetEpochSeconds());
        }
        JSONObject data = root.optJSONObject("data");
        return data == null ? new JSONObject() : data;
    }
}
