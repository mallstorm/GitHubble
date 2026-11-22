package app.githubble.mobile.mirror;

import app.githubble.mobile.network.GitHubApiClient;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * One-time, sealed credential for importing workflow-containing Git history.
 * GitHub Actions GITHUB_TOKEN cannot write workflow files to new Git refs.
 * Uses the same libsodium sealed-box protocol as Mirror Manager.
 */
public final class ExternalImportSecrets {
    private ExternalImportSecrets() { }
    private static final String API = "https://api.github.com/repos/";
    private static final String PREFIX = "GITHUBBLE_EXT_IMPORT_";

    public static String name(String requestId) {
        if (requestId == null || !requestId.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid import request ID");
        return PREFIX + requestId.toUpperCase(Locale.ROOT);
    }

    public static void put(GitHubApiClient client, String repository, String requestId,
                           String accountToken) throws Exception {
        if (accountToken == null || accountToken.isEmpty())
            throw new IllegalStateException("Для импорта требуется повторный вход в GitHub");
        JSONObject key = MirrorSecrets.publicKey(client, repository);
        String id = key.optString("key_id", "");
        String value = key.optString("key", "");
        if (id.isEmpty() || value.isEmpty())
            throw new SecurityException("GitHub Actions не вернул ключ шифрования");
        byte[] plaintext = accountToken.getBytes(StandardCharsets.UTF_8);
        try {
            JSONObject payload = new JSONObject()
                    .put("key_id", id)
                    .put("encrypted_value", MirrorSecrets.encryptForGitHub(plaintext, value));
            client.putJson(API + repository + "/actions/secrets/" + name(requestId), payload);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public static void delete(GitHubApiClient client, String repository,
                              String requestId) throws Exception {
        try {
            client.delete(API + repository + "/actions/secrets/" + name(requestId));
        } catch (GitHubApiClient.ApiException e) {
            if (e.statusCode != 404) throw e;
        }
    }
}
