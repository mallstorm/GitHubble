package app.githubble.mobile.build;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ActionsErrorTextTest {
  @Test
  public void paymentRequiredIsExplainedWithoutJson() {
    String raw = "GitHub API HTTP 402. {\"message\":\"Please ensure your account has a valid payment method on file to access this service.\",\"documentation_url\":\"https://docs.github.com/rest/actions/cache#get-github-action...\"}";
    String text = ActionsErrorText.describe(raw);
    assertTrue(text.contains("способ оплаты"));
    assertFalse(text.contains("{"));
    assertFalse(text.contains("documentation_url"));
  }

  @Test
  public void commonStatusesAndFallbacks() {
    assertTrue(ActionsErrorText.describe("GitHub API HTTP 403. {\"message\":\"Resource not accessible\"}").contains("Administration"));
    assertTrue(ActionsErrorText.describe("GitHub API HTTP 404. {}").contains("не найден"));
    assertTrue(ActionsErrorText.describe("API rate limit exceeded").contains("лимит"));
    assertTrue(ActionsErrorText.describe(null).contains("Неизвестная"));
    assertFalse(ActionsErrorText.describe("Сеть недоступна {\"x\":1}").contains("{"));
  }
}
