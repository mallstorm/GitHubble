package app.githubble.mobile.ui;

import static org.junit.Assert.*;

import org.junit.Test;

public class AccountPermissionDisplayTest {
  @Test
  public void unknownAccessNeverLooksLikeWriteAccess() {
    assertEquals("Уровень не определён", AccountAccessScreen.level("admin"));
    assertEquals("Уровень не определён", AccountAccessScreen.level(""));
  }

  @Test
  public void missingPermissionsHaveExplicitEmptyState() {
    assertEquals("Нет доступа", AccountAccessScreen.level("none"));
    assertEquals("Только чтение", AccountAccessScreen.level("read"));
    assertEquals("Чтение и изменение", AccountAccessScreen.level("write"));
  }
}
