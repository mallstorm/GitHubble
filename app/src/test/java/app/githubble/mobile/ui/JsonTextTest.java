package app.githubble.mobile.ui;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class JsonTextTest {
  @Test
  public void jsonNullBlankAndMissingUseFallback() throws Exception {
    JSONObject o = new JSONObject("{\"a\":null,\"b\":\" \",\"c\":\"text\"}");
    assertEquals("none", JsonText.of(o, "a", "none"));
    assertEquals("none", JsonText.of(o, "b", "none"));
    assertEquals("none", JsonText.of(o, "missing", "none"));
    assertEquals("text", JsonText.of(o, "c", "none"));
    assertEquals("none", JsonText.of(null, "c", "none"));
  }
}
