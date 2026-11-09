package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import org.junit.Test;

public class AgentLineDiffTest {
  @Test
  public void diffReconstructsBothVersions() {
    String a = "one\ntwo\nthree\n", b = "one\nchanged\nthree\nfour\n";
    AgentLineDiff d = AgentLineDiff.between(a, b);
    StringBuilder before = new StringBuilder(), after = new StringBuilder();
    for (AgentLineDiff.Line l : d.lines) {
      if (l.kind != '+') before.append(l.text).append('\n');
      if (l.kind != '-') after.append(l.text).append('\n');
    }
    assertEquals(a, before.toString());
    assertEquals(b, after.toString());
    assertFalse(d.coarse);
  }

  @Test
  public void creationHasOnlyAddedLines() {
    AgentLineDiff d = AgentLineDiff.between("", "a\nb\n");
    assertEquals(2, d.lines.size());
    for (AgentLineDiff.Line l : d.lines) assertEquals('+', l.kind);
  }
}
