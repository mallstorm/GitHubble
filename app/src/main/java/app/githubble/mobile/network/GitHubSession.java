package app.githubble.mobile.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Immutable owner and credentials for an entire request, including retries and cache writes. */
public final class GitHubSession {
  public final String token, scope;
  public final long generation;

  public GitHubSession(String token, String login, long generation) {
    this.token = token == null ? "" : token;
    String identity =
        login == null || login.isEmpty() ? this.token : "login:" + login.toLowerCase(Locale.ROOT);
    this.scope = identity.isEmpty() ? "public" : digest(identity);
    this.generation = generation;
  }

  public interface Source {
    GitHubSession capture();

    boolean isCurrent(GitHubSession session);

    void reauthorization(GitHubSession session, boolean required);

    default boolean commitIfCurrent(GitHubSession session, Runnable action) {
      synchronized (this) {
        if (!isCurrent(session)) return false;
        action.run();
        return true;
      }
    }
  }

  private static String digest(String value) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < 12; i++) out.append(String.format(Locale.ROOT, "%02x", bytes[i] & 255));
      return out.toString();
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
