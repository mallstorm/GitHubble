package app.githubble.mobile.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DesignSystemTest {
  @Test
  public void radiusScaleKeepsMarksAndPillsAndFlattensCards() {
    assertEquals(3, DesignSystem.radius(3));
    assertEquals(8, DesignSystem.radius(8));
    assertEquals(10, DesignSystem.radius(9));
    assertEquals(10, DesignSystem.radius(13));
    assertEquals(12, DesignSystem.radius(14));
    assertEquals(12, DesignSystem.radius(22));
    assertEquals(28, DesignSystem.radius(28));
    assertEquals(50, DesignSystem.radius(50));
  }
}
