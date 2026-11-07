package app.githubble.mobile.network;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/** Authenticated asset API request, credential-free CDN redirects, atomic Downloads publication. */
public final class GitHubReleaseDownloads {
  private GitHubReleaseDownloads() {}

  public static boolean validAssetApi(String url) {
    try {
      URI u = URI.create(url);
      return "https".equals(u.getScheme())
          && "api.github.com".equals(u.getHost())
          && u.getPort() == -1
          && u.getUserInfo() == null
          && u.getQuery() == null
          && u.getFragment() == null
          && u.getPath()
              .matches("/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/assets/[1-9][0-9]*");
    } catch (Exception invalid) {
      return false;
    }
  }

  public static boolean validRedirect(String url) {
    try {
      URI u = URI.create(url);
      String host = u.getHost();
      return "https".equals(u.getScheme())
          && u.getUserInfo() == null
          && u.getPort() == -1
          && u.getFragment() == null
          && host != null
          && (host.equals("github.com")
              || host.endsWith(".githubusercontent.com")
              || host.endsWith(".blob.core.windows.net"));
    } catch (Exception invalid) {
      return false;
    }
  }

  public static Uri download(
      Context context, String name, String apiUrl, long expected, GitHubSession.Source source)
      throws Exception {
    return download(context, name, apiUrl, expected, source, source.capture().scope);
  }

  public static Uri download(
      Context context,
      String name,
      String apiUrl,
      long expected,
      GitHubSession.Source source,
      String expectedScope)
      throws Exception {
    if (!validAssetApi(apiUrl))
      throw new IllegalArgumentException("Некорректный GitHub Release asset API URL");
    GitHubSession session = source.capture();
    if (!session.scope.equals(expectedScope)) throw new GitHubApiClient.SessionChangedException();
    requireCurrent(source, session);
    HttpURLConnection connection = null;
    Uri output = null;
    File legacy = null;
    boolean complete = false;
    try {
      String target = apiUrl;
      for (int redirects = 0; ; redirects++) {
        requireCurrent(source, session);
        connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(35000);
        connection.setRequestProperty("User-Agent", "GitHubble-Android");
        connection.setRequestProperty("Accept", "application/octet-stream");
        if (redirects == 0 && !session.token.isEmpty())
          connection.setRequestProperty("Authorization", "Bearer " + session.token);
        int code = connection.getResponseCode();
        requireCurrent(source, session);
        if (code == 200) break;
        if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308)
            && redirects < 5) {
          String location = connection.getHeaderField("Location");
          if (location == null) throw new IOException("GitHub не выдал ссылку загрузки");
          String next = new URL(new URL(target), location).toString();
          if (!validRedirect(next))
            throw new IOException("GitHub выдал неподдерживаемый адрес загрузки");
          connection.disconnect();
          connection = null;
          target = next;
          continue;
        }
        throw new IOException("Не удалось скачать Release asset: HTTP " + code);
      }
      String safe =
          (name == null ? "release.bin" : name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
      if (safe.isBlank() || safe.equals(".") || safe.equals("..")) safe = "release.bin";
      if (safe.length() > 180) safe = safe.substring(0, 180);
      OutputStream sink;
      if (Build.VERSION.SDK_INT >= 29) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, safe);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
        values.put(
            MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GitHubble");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        output =
            context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (output == null) throw new IOException("Не удалось создать файл Downloads");
        sink = context.getContentResolver().openOutputStream(output);
      } else {
        File dir =
            new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "GitHubble");
        if (!dir.isDirectory() && !dir.mkdirs())
          throw new IOException("Не создать папку Downloads/GitHubble");
        legacy = File.createTempFile("download-", "-" + safe, dir);
        output = Uri.fromFile(legacy);
        sink = new FileOutputStream(legacy);
      }
      if (sink == null) throw new IOException("Не удалось открыть файл Downloads");
      long received = 0;
      try (InputStream in = connection.getInputStream();
          OutputStream out = sink) {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
          requireCurrent(source, session);
          received += n;
          if (expected >= 0 && received > expected)
            throw new IOException("Размер загрузки не совпадает с GitHub");
          out.write(buffer, 0, n);
        }
      }
      requireCurrent(source, session);
      if (expected >= 0 && received != expected) throw new IOException("Файл скачан не полностью");
      if (Build.VERSION.SDK_INT >= 29) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.IS_PENDING, 0);
        context.getContentResolver().update(output, v, null, null);
      }
      complete = true;
      return output;
    } finally {
      if (connection != null) connection.disconnect();
      if (!complete && output != null) {
        if (Build.VERSION.SDK_INT >= 29) context.getContentResolver().delete(output, null, null);
        else if (legacy != null) legacy.delete();
      }
    }
  }

  private static void requireCurrent(GitHubSession.Source source, GitHubSession session)
      throws IOException {
    if (Thread.currentThread().isInterrupted() || !source.isCurrent(session))
      throw new GitHubApiClient.SessionChangedException();
  }
}
