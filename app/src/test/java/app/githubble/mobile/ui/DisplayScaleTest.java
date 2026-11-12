package app.githubble.mobile.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DisplayScaleTest {
  @Test
  public void clampSnapsToStepAndRange() {
    assertEquals(100, DisplayScale.clamp(100));
    assertEquals(90, DisplayScale.clamp(88));
    assertEquals(95, DisplayScale.clamp(96));
    assertEquals(80, DisplayScale.clamp(10));
    assertEquals(110, DisplayScale.clamp(500));
  }

  @Test
  public void densityScalesWithPercent() {
    assertEquals(432, DisplayScale.scaledDpi(480, 90));
    assertEquals(480, DisplayScale.scaledDpi(480, 100));
    assertEquals(528, DisplayScale.scaledDpi(480, 110));
    assertEquals(384, DisplayScale.scaledDpi(480, 70));
  }
}
