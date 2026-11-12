package app.githubble.mobile.ui;

import org.json.JSONObject;

/** Reads display text from GitHub JSON where a null value must not turn into the string "null". */
public final class JsonText {
  private JsonText() {}

  public static String of(JSONObject object, String key, String fallback) {
    if (object == null || object.isNull(key)) return fallback;
    String value = object.optString(key, fallback);
    return value == null || value.trim().isEmpty() || "null".equals(value) ? fallback : value;
  }
}
