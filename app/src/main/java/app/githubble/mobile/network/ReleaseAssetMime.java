package app.githubble.mobile.network;

import java.util.Locale;

/** MIME type used to open a downloaded release asset: GitHub's own type when specific, else by extension. */
public final class ReleaseAssetMime {
  private ReleaseAssetMime() {}

  public static String forAsset(String fileName, String contentType) {
    String declared = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    if (!declared.isEmpty() && !declared.equals("application/octet-stream") && declared.indexOf('/') > 0)
      return declared;
    String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    String ext = dot >= 0 ? name.substring(dot + 1) : "";
    switch (ext) {
      case "apk":
        return "application/vnd.android.package-archive";
      case "zip":
        return "application/zip";
      case "gz":
      case "tgz":
        return "application/gzip";
      case "txt":
      case "log":
      case "md":
        return "text/plain";
      case "json":
        return "application/json";
      case "pdf":
        return "application/pdf";
      default:
        return "*/*";
    }
  }

  public static boolean isApk(String mime) {
    return "application/vnd.android.package-archive".equals(mime);
  }
}
