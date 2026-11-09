package app.githubble.mobile.ui;

import java.util.Arrays;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

/** CommonMark/GFM grammar shared by the transcript, document reader and tests. */
public final class AgentMarkdown {
  private static final Parser PARSER =
      Parser.builder()
          .extensions(
              Arrays.asList(
                  TablesExtension.create(),
                  TaskListItemsExtension.create(),
                  StrikethroughExtension.create(),
                  AutolinkExtension.create()))
          .build();

  private AgentMarkdown() {}

  public static Node parse(String source) {
    return PARSER.parse(source == null ? "" : source);
  }

  public static boolean safeLink(String url) {
    if (url == null) return false;
    try {
      java.net.URI uri = new java.net.URI(url);
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null
          && uri.getUserInfo() == null;
    } catch (Exception invalid) {
      return false;
    }
  }
}
