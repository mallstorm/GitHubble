package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Account operations. Supply a session-bound client captured before starting UI work. */
public final class GitHubAccountController {
  interface Transport {
    void requireCurrentSession() throws Exception;

    String executeText(String m, String u, String b, boolean a) throws Exception;

    JSONObject getJsonFresh(String u) throws Exception;

    JSONObject putJson(String u, JSONObject b) throws Exception;

    void putEmpty(String u) throws Exception;

    void delete(String u) throws Exception;

    void invalidate(String u);

    void invalidatePrefix(String u);
  }

  private final Transport api;
  private static final String ROOT = "https://api.github.com";
  private static final Set<String> LIMITS =
      Set.of("existing_users", "contributors_only", "collaborators_only");
  private static final Set<String> EXPIRIES =
      Set.of("one_day", "three_days", "one_week", "one_month", "six_months");

  public GitHubAccountController(GitHubApiClient client) {
    this(
        new Transport() {
          public void requireCurrentSession() throws Exception {
            client.requireCurrentSession();
          }

          public String executeText(String m, String u, String b, boolean a) throws Exception {
            return client.executeText(m, u, b, a);
          }

          public JSONObject getJsonFresh(String u) throws Exception {
            return client.getJsonFresh(u);
          }

          public JSONObject putJson(String u, JSONObject b) throws Exception {
            return client.putJson(u, b);
          }

          public void putEmpty(String u) throws Exception {
            client.putEmpty(u);
          }

          public void delete(String u) throws Exception {
            client.delete(u);
          }

          public void invalidate(String u) {
            client.invalidate(u);
          }

          public void invalidatePrefix(String u) {
            client.invalidatePrefix(u);
          }
        });
  }

  GitHubAccountController(Transport transport) {
    this.api = transport;
  }

  public JSONArray blocks(int page) throws Exception {
    if (page < 1) throw new IllegalArgumentException("Некорректная страница");
    api.requireCurrentSession();
    JSONArray result =
        new JSONArray(
            api.executeText("GET", ROOT + "/user/blocks?per_page=100&page=" + page, null, true));
    api.requireCurrentSession();
    return result;
  }

  public void block(String login, boolean enabled) throws Exception {
    if (login == null || !login.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"))
      throw new IllegalArgumentException("Укажите GitHub login без @ и ссылки");
    api.requireCurrentSession();
    if (enabled) api.putEmpty(ROOT + "/user/blocks/" + login);
    else api.delete(ROOT + "/user/blocks/" + login);
    api.invalidatePrefix(ROOT + "/user/blocks");
  }

  public JSONObject limits() throws Exception {
    api.requireCurrentSession();
    JSONObject result = api.getJsonFresh(ROOT + "/user/interaction-limits");
    api.requireCurrentSession();
    return result;
  }

  public void setLimits(String limit, String expiry) throws Exception {
    if (limit == null || expiry == null || !LIMITS.contains(limit) || !EXPIRIES.contains(expiry))
      throw new IllegalArgumentException("Выберите ограничение и срок");
    api.requireCurrentSession();
    api.putJson(
        ROOT + "/user/interaction-limits",
        new JSONObject().put("limit", limit).put("expiry", expiry));
    api.invalidate(ROOT + "/user/interaction-limits");
  }

  public void clearLimits() throws Exception {
    api.requireCurrentSession();
    api.delete(ROOT + "/user/interaction-limits");
    api.invalidate(ROOT + "/user/interaction-limits");
  }

  public JSONObject installations() throws Exception {
    JSONArray items = new JSONArray();
    for (int page = 1; page <= 20; page++) {
      api.requireCurrentSession();
      JSONObject response =
          api.getJsonFresh(ROOT + "/user/installations?per_page=100&page=" + page);
      JSONArray batch = response.optJSONArray("installations");
      if (batch == null) throw new IllegalStateException("GitHub не вернул список установок");
      for (int i = 0; i < batch.length(); i++) items.put(batch.get(i));
      if (batch.length() < 100) {
        api.requireCurrentSession();
        return new JSONObject().put("installations", items);
      }
    }
    throw new IllegalStateException("Список установок превышает лимит загрузки; повторите позже");
  }
}
