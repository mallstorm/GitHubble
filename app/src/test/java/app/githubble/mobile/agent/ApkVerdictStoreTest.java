package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ApkVerdictStoreTest {
  private static JSONObject apk(String status) throws Exception {
    return new JSONObject().put("file", "a.apk").put("status", status);
  }

  @Test public void overallStatus() throws Exception {
    assertEquals("NO_APK", ApkVerdictStore.overall(new JSONArray()));
    assertEquals("NO_APK", ApkVerdictStore.overall(null));
    assertEquals("PASS", ApkVerdictStore.overall(new JSONArray().put(apk("PASS")).put(apk("PASS"))));
    assertEquals("UNVERIFIED", ApkVerdictStore.overall(new JSONArray().put(apk("PASS")).put(apk("UNVERIFIED"))));
    assertEquals("FAIL", ApkVerdictStore.overall(new JSONArray().put(apk("UNVERIFIED")).put(apk("FAIL"))));
  }

  @Test public void recordsAndFindsPerAccountRepositoryArtifact() throws Exception {
    JSONArray pass = new JSONArray().put(apk("PASS"));
    String stored = ApkVerdictStore.add("", ApkVerdictStore.entry(7, "acc1", "o/r", "z.zip", 3, pass, 1));
    stored = ApkVerdictStore.add(stored, ApkVerdictStore.entry(7, "acc2", "o/r", "z.zip", 3, new JSONArray().put(apk("FAIL")), 2));
    assertEquals("PASS", ApkVerdictStore.find(stored, "acc1", "o/r", 7).getString("status"));
    assertEquals("FAIL", ApkVerdictStore.find(stored, "acc2", "o/r", 7).getString("status"));
    assertNull(ApkVerdictStore.find(stored, "acc1", "other/repo", 7));
    assertNull(ApkVerdictStore.find(stored, "acc1", "o/r", 8));
  }

  @Test public void newDownloadOfSameArtifactReplacesOldVerdict() throws Exception {
    String stored = ApkVerdictStore.add("", ApkVerdictStore.entry(7, "a", "o/r", "z", 1, new JSONArray().put(apk("FAIL")), 1));
    stored = ApkVerdictStore.add(stored, ApkVerdictStore.entry(7, "a", "o/r", "z", 1, new JSONArray().put(apk("PASS")), 2));
    assertEquals(1, new JSONArray(stored).length());
    assertEquals("PASS", ApkVerdictStore.find(stored, "a", "o/r", 7).getString("status"));
  }

  @Test public void historyIsCappedAndDamagedStoreIsIgnored() throws Exception {
    String stored = "not json";
    for (int i = 1; i <= 30; i++)
      stored = ApkVerdictStore.add(stored, ApkVerdictStore.entry(i, "a", "o/r", "z", 1, new JSONArray(), i));
    assertEquals(20, new JSONArray(stored).length());
    assertNotNull(ApkVerdictStore.find(stored, "a", "o/r", 30));
    assertNull(ApkVerdictStore.find(stored, "a", "o/r", 1));
  }

  @Test public void expectationIsValidatedAndBounded() throws Exception {
    JSONObject ok = ApkVerdictStore.cleanExpectation(new JSONObject()
        .put("package", "app.githubble.mobile").put("version_code", 78).put("version_name", "1.0.0-rc4.2.31"));
    assertEquals("app.githubble.mobile", ok.getString("package"));
    assertEquals(78, ok.getLong("version_code"));
    assertEquals("1.0.0-rc4.2.31", ok.getString("version_name"));
    JSONObject bad = ApkVerdictStore.cleanExpectation(new JSONObject()
        .put("package", "not a package!").put("version_code", -5).put("version_name", "x y; rm"));
    assertEquals(0, bad.length());
    assertEquals(0, ApkVerdictStore.cleanExpectation(null).length());
  }
}
