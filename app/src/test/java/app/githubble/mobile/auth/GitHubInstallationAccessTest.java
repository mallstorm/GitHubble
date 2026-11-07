package app.githubble.mobile.auth;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class GitHubInstallationAccessTest {
  @Test
  public void organizationOnlyInstallationIsUsable() throws Exception {
    JSONObject org =
        new JSONObject()
            .put("id", 5)
            .put("account", new JSONObject().put("login", "team-org"))
            .put("target_type", "Organization");
    JSONObject root = new JSONObject().put("installations", new JSONArray().put(org));
    assertTrue(GitHubAppInstallationGate.hasUsableInstallation(root));
    org.put("suspended_at", "2026-11-07T00:00:00Z");
    assertFalse(GitHubAppInstallationGate.hasUsableInstallation(root));
  }

  @Test
  public void emptyAndMalformedResponsesDoNotGrantAccess() throws Exception {
    assertFalse(GitHubAppInstallationGate.hasUsableInstallation(null));
    assertFalse(GitHubAppInstallationGate.hasUsableInstallation(new JSONObject()));
    assertFalse(
        GitHubAppInstallationGate.hasUsableInstallation(
            new JSONObject().put("installations", new JSONArray().put(new JSONObject()))));
  }
}
