package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Device-side record of APK verification results, so the agent can state only what the device
 * actually checked. Entries hold identity and check names, never file contents or tokens.
 */
public final class ApkVerdictStore {
  public static final String KEY = "apk_verdicts";
  static final int MAX = 20;

  private ApkVerdictStore() {}

  /** NO_APK when nothing was extracted, FAIL if any APK failed, UNVERIFIED if any could not be proven, else PASS. */
  public static String overall(JSONArray apks) {
    if (apks == null || apks.length() == 0) return "NO_APK";
    boolean unverified = false;
    for (int i = 0; i < apks.length(); i++) {
      String status = apks.optJSONObject(i) == null ? "FAIL" : apks.optJSONObject(i).optString("status");
      if ("FAIL".equals(status)) return "FAIL";
      if (!"PASS".equals(status)) unverified = true;
    }
    return unverified ? "UNVERIFIED" : "PASS";
  }

  public static JSONObject entry(
      long artifactId, String account, String repo, String zipName, long runId, JSONArray apks, long at)
      throws Exception {
    return new JSONObject()
        .put("artifact_id", artifactId)
        .put("account", account)
        .put("repository", repo)
        .put("zip", zipName)
        .put("run_id", runId)
        .put("status", overall(apks))
        .put("apks", apks == null ? new JSONArray() : apks)
        .put("at", at);
  }

  /** Newest first; one entry per account+repository+artifact; at most {@link #MAX} kept. */
  public static String add(String stored, JSONObject entry) throws Exception {
    JSONArray old;
    try {
      old = stored == null || stored.isEmpty() ? new JSONArray() : new JSONArray(stored);
    } catch (Exception damaged) {
      old = new JSONArray();
    }
    JSONArray out = new JSONArray().put(entry);
    for (int i = 0; i < old.length() && out.length() < MAX; i++) {
      JSONObject e = old.optJSONObject(i);
      if (e == null) continue;
      boolean same =
          e.optLong("artifact_id") == entry.optLong("artifact_id")
              && e.optString("account").equals(entry.optString("account"))
              && e.optString("repository").equals(entry.optString("repository"));
      if (!same) out.put(e);
    }
    return out.toString();
  }

  public static JSONObject find(String stored, String account, String repo, long artifactId) {
    try {
      JSONArray all = stored == null || stored.isEmpty() ? new JSONArray() : new JSONArray(stored);
      for (int i = 0; i < all.length(); i++) {
        JSONObject e = all.optJSONObject(i);
        if (e != null
            && e.optLong("artifact_id") == artifactId
            && e.optString("account").equals(account)
            && e.optString("repository").equals(repo)) return e;
      }
    } catch (Exception damaged) {
      /* treated as absent */
    }
    return null;
  }

  /** Validated expectation from the agent: only identity fields, bounded. */
  public static JSONObject cleanExpectation(JSONObject raw) {
    JSONObject out = new JSONObject();
    if (raw == null) return out;
    try {
      String pkg = raw.optString("package", "");
      if (pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") && pkg.length() <= 200) out.put("package", pkg);
      long code = raw.optLong("version_code", -1);
      if (code >= 0 && code < 2_100_000_000L) out.put("version_code", code);
      String name = raw.optString("version_name", "");
      if (!name.isEmpty() && name.length() <= 64 && name.matches("[A-Za-z0-9._+-]+")) out.put("version_name", name);
    } catch (Exception impossible) {
      /* JSONObject.put on valid keys does not fail */
    }
    return out;
  }
}
