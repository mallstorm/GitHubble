package app.githubble.mobile.network;

import static org.junit.Assert.*;

import org.junit.Test;

public class ReleaseAssetMimeTest {
  @Test
  public void specificDeclaredTypeWins() {
    assertEquals("application/zip", ReleaseAssetMime.forAsset("x.bin", "application/zip"));
    assertEquals("text/plain", ReleaseAssetMime.forAsset("a.apk", " Text/Plain "));
  }

  @Test
  public void genericOrMissingTypeFallsBackToExtension() {
    assertEquals("application/vnd.android.package-archive", ReleaseAssetMime.forAsset("GitHubble-release.APK", "application/octet-stream"));
    assertEquals("application/zip", ReleaseAssetMime.forAsset("sources.zip", ""));
    assertEquals("*/*", ReleaseAssetMime.forAsset("data.xyz", null));
    assertEquals("*/*", ReleaseAssetMime.forAsset(null, null));
    assertTrue(ReleaseAssetMime.isApk("application/vnd.android.package-archive"));
    assertFalse(ReleaseAssetMime.isApk("application/zip"));
  }
}
