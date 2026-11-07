package app.githubble.mobile.network;

import static org.junit.Assert.*;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.json.JSONObject;
import org.junit.Test;

/** Exercise the real HTTP client; credentials and cache are in-memory ports, no Android stubs. */
public class GitHubApiClientTest {
  static final class Sessions implements GitHubSession.Source {
    private GitHubSession current = new GitHubSession("token-A", "alice", 0);

    synchronized void switchTo(String token, String login) {
      current = new GitHubSession(token, login, current.generation + 1);
    }

    public synchronized GitHubSession capture() {
      return current;
    }

    public synchronized boolean isCurrent(GitHubSession s) {
      return current.generation == s.generation;
    }

    public void reauthorization(GitHubSession s, boolean required) {}
  }

  static final class Cache implements GitHubApiClient.Cache {
    final Map<String, Entry> rows = new ConcurrentHashMap<>();

    public Entry get(String key) {
      return rows.get(key);
    }

    public void put(String k, String type, String payload, String etag, long ttl) {
      rows.put(k, new Entry(payload, etag));
    }

    public void remove(String key) {
      rows.remove(key);
    }

    public void removePrefix(String p) {
      rows.keySet().removeIf(k -> k.startsWith(p));
    }
  }

  @Test
  public void accountSwitchDuringResponseCannotPoisonNewAccountsCache() throws Exception {
    Sessions source = new Sessions();
    Cache cache = new Cache();
    GitHubApiClient api = new GitHubApiClient(source, cache);
    CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) throws InterruptedException {
              arrived.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return new MockResponse().setBody("{\"private\":\"alice\"}");
            }
          });
      server.start();
      String url = server.url("/user-data").toString();
      Future<String> a = pool.submit(() -> api.getText(url));
      assertTrue(arrived.await(5, TimeUnit.SECONDS));
      source.switchTo("token-B", "bob");
      api.resetInMemorySessionState();
      release.countDown();
      try {
        a.get(5, TimeUnit.SECONDS);
        fail("stale response accepted");
      } catch (ExecutionException cancelled) {
        assertTrue(cancelled.getCause() instanceof GitHubApiClient.SessionChangedException);
      }
      assertTrue(cache.rows.isEmpty());
      assertEquals("Bearer token-A", server.takeRequest().getHeader("Authorization"));
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
              return new MockResponse().setResponseCode(503).setBody("{}");
            }
          });
      try {
        api.getJson(url);
        fail("private response leaked through fallback");
      } catch (GitHubApiClient.ApiException expected) {
        assertEquals(503, expected.statusCode);
      }
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  public void mutationIsSentOnceWhenServerReturns503() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
      try {
        new GitHubApiClient(new Sessions(), null)
            .postJson(
                server.url("/issues").toString(), new JSONObject().put("title", "example"), true);
        fail();
      } catch (GitHubApiClient.MutationOutcomeUnknownException expected) {
        assertEquals(503, expected.statusCode);
      }
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  public void lostMutationResponseIsNotReplayed() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
      try {
        new GitHubApiClient(new Sessions(), null)
            .postJson(server.url("/comments").toString(), new JSONObject(), true);
        fail();
      } catch (GitHubApiClient.MutationOutcomeUnknownException expected) {
      }
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  public void invalidSuccessfulMutationResponseRequiresReconciliation() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(201).setBody("not-json"));
      try {
        new GitHubApiClient(new Sessions(), null)
            .postJson(server.url("/comments").toString(), new JSONObject(), true);
        fail();
      } catch (GitHubApiClient.MutationOutcomeUnknownException expected) {
        assertEquals(200, expected.statusCode);
      }
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  public void freshConflictCheckNeverFallsBackToCachedVersion() throws Exception {
    Cache cache = new Cache();
    GitHubApiClient api = new GitHubApiClient(new Sessions(), cache);
    try (MockWebServer server = new MockWebServer()) {
      String url = server.url("/issues/1").toString();
      server.enqueue(
          new MockResponse().setHeader("ETag", "old").setBody("{\"updated_at\":\"old\"}"));
      api.getJson(url);
      for (int i = 0; i < 3; i++)
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
      try {
        api.getJsonFresh(url);
        fail("stale version used for write precondition");
      } catch (GitHubApiClient.ApiException expected) {
      }
      server.takeRequest();
      assertNull(server.takeRequest().getHeader("If-None-Match"));
    }
  }

  @Test
  public void ordinaryReadCanUseSameAccountsOfflineSnapshot() throws Exception {
    GitHubApiClient api = new GitHubApiClient(new Sessions(), new Cache());
    try (MockWebServer server = new MockWebServer()) {
      String url = server.url("/repos").toString();
      server.enqueue(new MockResponse().setBody("{\"snapshot\":true}"));
      api.getJson(url);
      for (int i = 0; i < 3; i++)
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
      assertTrue(api.getJson(url).getBoolean("snapshot"));
    }
  }

  @Test
  public void boundOperationRefusesNewAccountBeforeSending() throws Exception {
    Sessions source = new Sessions();
    GitHubApiClient scoped = new GitHubApiClient(source, null).bindCurrentSession();
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      source.switchTo("token-B", "bob");
      try {
        scoped.patch(server.url("/issues/1").toString(), new JSONObject());
        fail();
      } catch (GitHubApiClient.SessionChangedException expected) {
      }
      assertEquals(0, server.getRequestCount());
    }
  }

  @Test
  public void switchingAwayAndBackStillInvalidatesOldOperation() throws Exception {
    Sessions source = new Sessions();
    GitHubApiClient scoped = new GitHubApiClient(source, null).bindCurrentSession();
    source.switchTo("token-B", "bob");
    source.switchTo("token-A", "alice");
    try {
      scoped.requireCurrentSession();
      fail();
    } catch (GitHubApiClient.SessionChangedException expected) {
    }
  }

  @Test
  public void retryAfterLongerThanInlineBudgetDoesNotRetryEarly() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse().setResponseCode(429).setHeader("Retry-After", "60").setBody("{}"));
      try {
        new GitHubApiClient(new Sessions(), null).getJson(server.url("/repos").toString());
        fail();
      } catch (GitHubApiClient.ApiException e) {
        assertTrue(e.rateLimited);
        assertEquals(60000, e.retryAfterMillis);
      }
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  public void credentialedApiDoesNotAutomaticallyFollowExternalRedirect() throws Exception {
    try (MockWebServer server = new MockWebServer();
        MockWebServer cdn = new MockWebServer()) {
      server.enqueue(
          new MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/file")));
      try {
        new GitHubApiClient(new Sessions(), null).getJson(server.url("/repos").toString());
        fail();
      } catch (GitHubApiClient.ApiException expected) {
      }
      assertEquals(0, cdn.getRequestCount());
    }
  }

  @Test
  public void renamedRepositoryCanRedirectWithinSameOrigin() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse().setResponseCode(301).setHeader("Location", "/repos/new/name"));
      server.enqueue(new MockResponse().setBody("{\"full_name\":\"new/name\"}"));
      assertEquals(
          "new/name",
          new GitHubApiClient(new Sessions(), null)
              .getJson(server.url("/repos/old/name").toString())
              .getString("full_name"));
      assertEquals("Bearer token-A", server.takeRequest().getHeader("Authorization"));
      assertEquals("Bearer token-A", server.takeRequest().getHeader("Authorization"));
    }
  }

  @Test
  public void etagRevalidationReturnsMatchingBody() throws Exception {
    GitHubApiClient api = new GitHubApiClient(new Sessions(), new Cache());
    try (MockWebServer server = new MockWebServer()) {
      String url = server.url("/repos").toString();
      server.enqueue(new MockResponse().setHeader("ETag", "v1").setBody("{\"v\":1}"));
      api.getJson(url);
      server.enqueue(new MockResponse().setResponseCode(304));
      assertEquals(1, api.getJson(url).getInt("v"));
      server.takeRequest();
      assertEquals("v1", server.takeRequest().getHeader("If-None-Match"));
    }
  }
}
