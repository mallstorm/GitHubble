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

  @Test
  public void toolJsonAfterProseIsHiddenButProseStays() {
    String prose = "Подготовлю приложение и опубликую файлы.";
    assertEquals(prose, AgentAnswerPreview.visible(prose + "\n{\"tools\":[{\"name\":\"write_files\",\"args\":{\"files\":[{\"path\":\"a\"}]}}]}"));
    assertEquals(prose, AgentAnswerPreview.visible(prose + "\n```json\n{\"tools\":[{\"na"));
    assertEquals(prose, AgentAnswerPreview.visible(prose + "\n{\"to"));
    assertEquals(prose, AgentAnswerPreview.visible(prose + "\n{"));
    assertEquals(prose, AgentAnswerPreview.visible(prose + "\n```json"));
    assertEquals("Код: {a: 1}", AgentAnswerPreview.visible("Код: {a: 1}"));
  }
}
