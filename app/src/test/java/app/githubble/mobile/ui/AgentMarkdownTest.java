package app.githubble.mobile.ui;

import static org.junit.Assert.*;

import org.commonmark.ext.gfm.tables.*;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.*;
import org.junit.Test;

public class AgentMarkdownTest {
  @Test
  public void reportParsesHeadingsTableAndCodeWithoutFlattening() {
    Node document =
        AgentMarkdown.parse(
            "# Отчёт\n\n"
                + "| Было | Стало |\n"
                + "| --- | --- |\n"
                + "| текст | таблица |\n\n"
                + "```java\n"
                + "  return true;\n"
                + "```\n");
    assertTrue(document.getFirstChild() instanceof Heading);
    assertTrue(document.getFirstChild().getNext() instanceof TableBlock);
    FencedCodeBlock code = (FencedCodeBlock) document.getLastChild();
    assertEquals("java", code.getInfo());
    assertEquals("  return true;\n", code.getLiteral());
  }

  @Test
  public void nestedListsAndTasksRemainStructured() {
    Node d = AgentMarkdown.parse("- [x] Готово\n  - Подпункт\n- [ ] Проверить\n");
    assertTrue(d.getFirstChild() instanceof BulletList);
    assertTrue(count(d, TaskListItemMarker.class) >= 2);
    assertTrue(count(d, BulletList.class) >= 2);
  }

  @Test
  public void linksRejectExecutionSchemesAndEmbeddedCredentials() {
    assertTrue(AgentMarkdown.safeLink("https://github.com/owner/repo"));
    assertFalse(AgentMarkdown.safeLink("javascript:alert(1)"));
    assertFalse(AgentMarkdown.safeLink("file:///data/private"));
    assertFalse(AgentMarkdown.safeLink("https://token@example.org/a"));
  }

  private int count(Node n, Class<?> type) {
    int total = type.isInstance(n) ? 1 : 0;
    for (Node c = n.getFirstChild(); c != null; c = c.getNext()) total += count(c, type);
    return total;
  }
}
