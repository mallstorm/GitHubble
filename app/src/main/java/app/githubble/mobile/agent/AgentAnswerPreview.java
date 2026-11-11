package app.githubble.mobile.agent;

/** Decode only the answer envelope. Never display streamed tool arguments or partial JSON. */
public final class AgentAnswerPreview {
  private AgentAnswerPreview() {}

  private static final java.util.regex.Pattern TOOL_CALL =
      java.util.regex.Pattern.compile("(```(?:json)?\\s*)?\\{\\s*\"tools\"\\s*:");

  /** Prose the model wrote before a tool-call JSON stays visible; the JSON itself (even half-streamed) does not. */
  static String withoutToolCalls(String text) {
    java.util.regex.Matcher m = TOOL_CALL.matcher(text);
    if (m.find()) return text.substring(0, m.start()).replaceFirst("\\s+$", "");
    int brace = text.lastIndexOf('{');
    if (brace >= 0) {
      String tail = text.substring(brace).replaceAll("\\s+", "");
      if (tail.length() < 9 && "{\"tools\":".startsWith(tail)) {
        return text.substring(0, brace).replaceFirst("(```(?:json)?\\s*)?$", "").replaceFirst("\\s+$", "");
      }
    }
    String trimmed = text.replaceFirst("\\s+$", "");
    if (trimmed.endsWith("```json")) return trimmed.substring(0, trimmed.length() - 7).replaceFirst("\\s+$", "");
    return text;
  }

  public static String visible(String source) {
    if (source == null) return "";
    String s = source.replaceFirst("^\\s+", "");
    if (s.startsWith("```json")) return "";
    if (!s.startsWith("{")) return s.startsWith("[") ? "" : withoutToolCalls(source);
    // Only a first top-level answer field is permitted in the preview.
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("^\\{\\s*\"answer\"\\s*:\\s*\"").matcher(s);
    if (!m.find()) return "";
    StringBuilder out = new StringBuilder();
    for (int i = m.end(); i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"') break;
      if (c != '\\') {
        out.append(c);
        continue;
      }
      if (++i >= s.length()) break;
      char escaped = s.charAt(i);
      switch (escaped) {
        case 'n':
          out.append('\n');
          break;
        case 'r':
          out.append('\r');
          break;
        case 't':
          out.append('\t');
          break;
        case '"':
        case '\\':
        case '/':
          out.append(escaped);
          break;
        case 'u':
          if (i + 4 >= s.length()) return out.toString();
          try {
            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
            i += 4;
          } catch (NumberFormatException invalid) {
            return "";
          }
          break;
        default:
          return "";
      }
    }
    return out.toString();
  }
}
