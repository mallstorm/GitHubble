package app.githubble.mobile.agent;

import java.util.ArrayList;
import java.util.List;

/** Bounded line comparison for the phone's unified diff. */
public final class AgentLineDiff {
  public static final class Line {
    public final char kind;
    public final String text;
    public final int before, after;

    Line(char k, String t, int b, int a) {
      kind = k;
      text = t;
      before = b;
      after = a;
    }
  }

  public final List<Line> lines = new ArrayList<>();
  public final boolean coarse;

  private AgentLineDiff(boolean coarse) {
    this.coarse = coarse;
  }

  private static String[] split(String s) {
    if (s == null || s.isEmpty()) return new String[0];
    String[] lines = s.replace("\r\n", "\n").split("\n", -1);
    return lines[lines.length - 1].isEmpty()
        ? java.util.Arrays.copyOf(lines, lines.length - 1)
        : lines;
  }

  public static AgentLineDiff between(String before, String after) {
    String[] a = split(before), b = split(after);
    boolean coarse = (long) a.length * b.length > 1000000;
    AgentLineDiff result = new AgentLineDiff(coarse);
    if (coarse) {
      for (int i = 0; i < a.length; i++) result.lines.add(new Line('-', a[i], i + 1, 0));
      for (int j = 0; j < b.length; j++) result.lines.add(new Line('+', b[j], 0, j + 1));
      return result;
    }
    int[][] common = new int[a.length + 1][b.length + 1];
    for (int i = a.length - 1; i >= 0; i--)
      for (int j = b.length - 1; j >= 0; j--)
        common[i][j] =
            a[i].equals(b[j])
                ? common[i + 1][j + 1] + 1
                : Math.max(common[i + 1][j], common[i][j + 1]);
    int i = 0, j = 0;
    while (i < a.length || j < b.length) {
      if (i < a.length && j < b.length && a[i].equals(b[j])) {
        result.lines.add(new Line(' ', a[i], i + 1, j + 1));
        i++;
        j++;
      } else if (i < a.length && (j == b.length || common[i + 1][j] >= common[i][j + 1])) {
        result.lines.add(new Line('-', a[i], i + 1, 0));
        i++;
      } else {
        result.lines.add(new Line('+', b[j], 0, j + 1));
        j++;
      }
    }
    return result;
  }
}
