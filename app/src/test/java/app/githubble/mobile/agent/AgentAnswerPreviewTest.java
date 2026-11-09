package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import org.junit.Test;

public class AgentAnswerPreviewTest {
  @Test
  public void toolArgumentsNeverAppearAsReply() {
    assertEquals(
        "",
        AgentAnswerPreview.visible(
            "{\"tools\":[{\"name\":\"write_files\",\"args\":{\"content\":\"private\"}}]}"));
    assertEquals("", AgentAnswerPreview.visible("```json\n{\"tools\":[]}"));
    assertEquals("", AgentAnswerPreview.visible("[ {\"tool\":\"read_file\"} ]"));
  }

  @Test
  public void partialAnswerEnvelopeDecodesWithoutJsonPunctuation() {
    assertEquals("# Отчёт\nГотово", AgentAnswerPreview.visible("{\"answer\":\"# Отчёт\\nГотово"));
    assertEquals("А", AgentAnswerPreview.visible("{\"answer\":\"\\u0410\\u0"));
    assertEquals(
        "Обычный ответ **жирным**", AgentAnswerPreview.visible("Обычный ответ **жирным**"));
  }
}
