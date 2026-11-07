package app.githubble.mobile.controller;

import static org.junit.Assert.*;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONObject;
import org.junit.Test;

public class GitHubAccountControllerTest {
  static class Api implements GitHubAccountController.Transport {
    String method, url;
    JSONObject body;
    int pages;
    boolean changed, changeOnRead;

    Api() {}

    @Override
    public void requireCurrentSession() throws GitHubApiClient.SessionChangedException {
      if (changed) throw new GitHubApiClient.SessionChangedException();
    }

    @Override
    public void putEmpty(String u) {
      method = "PUT";
      url = u;
    }

    @Override
    public void delete(String u) {
      method = "DELETE";
      url = u;
    }

    @Override
    public JSONObject putJson(String u, JSONObject b) {
      method = "PUT";
      url = u;
      body = b;
      return new JSONObject();
    }

    @Override
    public void invalidate(String u) {}

    @Override
    public void invalidatePrefix(String u) {}

    @Override
    public String executeText(String m, String u, String b, boolean auth) {
      method = m;
      url = u;
      if (changeOnRead) changed = true;
      return "[]";
    }

    @Override
    public JSONObject getJsonFresh(String u) throws Exception {
      url = u;
      if (!u.contains("installations")) return new JSONObject();
      org.json.JSONArray a = new org.json.JSONArray();
      int count = pages++ == 0 ? 100 : 1;
      for (int i = 0; i < count; i++) a.put(new JSONObject().put("id", i));
      return new JSONObject().put("installations", a);
    }
  }

  @Test
  public void invalidLoginNeverReachesTransport() throws Exception {
    Api api = new Api();
    GitHubAccountController c = new GitHubAccountController(api);
    for (String value :
        new String[] {"", "@alice", "alice/other", "../admin", " alice", "-alice"}) {
      try {
        c.block(value, true);
        fail(value);
      } catch (IllegalArgumentException expected) {
      }
    }
    assertNull(api.method);
  }

  @Test
  public void blockAndUnblockUseDedicatedEndpoints() throws Exception {
    Api api = new Api();
    GitHubAccountController c = new GitHubAccountController(api);
    c.block("alice-test", true);
    assertEquals("PUT", api.method);
    assertEquals("https://api.github.com/user/blocks/alice-test", api.url);
    c.block("alice-test", false);
    assertEquals("DELETE", api.method);
  }

  @Test
  public void expiredContextCannotWrite() throws Exception {
    Api api = new Api();
    api.changed = true;
    try {
      new GitHubAccountController(api).block("alice", true);
      fail();
    } catch (GitHubApiClient.SessionChangedException expected) {
    }
    assertNull(api.method);
  }

  @Test
  public void blockListRejectsResponseAfterSessionChange() throws Exception {
    Api api = new Api();
    api.changeOnRead = true;
    try {
      new GitHubAccountController(api).blocks(1);
      fail();
    } catch (GitHubApiClient.SessionChangedException expected) {
    }
  }

  @Test
  public void limitsUseValidatedPayloadAndExplicitRemoval() throws Exception {
    Api api = new Api();
    GitHubAccountController c = new GitHubAccountController(api);
    c.setLimits("contributors_only", "one_week");
    assertEquals("contributors_only", api.body.getString("limit"));
    assertEquals("one_week", api.body.getString("expiry"));
    c.clearLimits();
    assertEquals("DELETE", api.method);
    assertEquals("https://api.github.com/user/interaction-limits", api.url);
  }

  @Test
  public void unsupportedLimitsAreNeverSent() throws Exception {
    Api api = new Api();
    GitHubAccountController c = new GitHubAccountController(api);
    try {
      c.setLimits("all", "forever");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertNull(api.method);
  }

  @Test
  public void installationsLoadFollowingPages() throws Exception {
    Api api = new Api();
    assertEquals(
        101,
        new GitHubAccountController(api).installations().getJSONArray("installations").length());
    assertEquals(2, api.pages);
  }
}
