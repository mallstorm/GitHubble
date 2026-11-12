package app.githubble.mobile.ui;

import android.content.Context;
import android.content.res.Configuration;

/** App-wide display size: scales every dp and sp through the screen density of the Activity context. */
public final class DisplayScale {
  public static final String PREF_KEY = "ui_display_scale";
  public static final int MIN = 80;
  public static final int MAX = 110;
  public static final int STEP = 5;
  public static final int DEFAULT = 100;

  private DisplayScale() {}

  /** Snaps to the nearest step inside the allowed range. */
  public static int clamp(int percent) {
    int snapped = Math.round(percent / (float) STEP) * STEP;
    return Math.max(MIN, Math.min(MAX, snapped));
  }

  public static int scaledDpi(int densityDpi, int percent) {
    return Math.max(80, Math.round(densityDpi * clamp(percent) / 100f));
  }

  public static Context wrap(Context base, int percent) {
    int value = clamp(percent);
    if (value == DEFAULT) return base;
    Configuration configuration = new Configuration(base.getResources().getConfiguration());
    configuration.densityDpi = scaledDpi(configuration.densityDpi, value);
    return base.createConfigurationContext(configuration);
  }

  public static int stored(Context context) {
    return clamp(context.getSharedPreferences("githubble", Context.MODE_PRIVATE).getInt(PREF_KEY, DEFAULT));
  }
}
