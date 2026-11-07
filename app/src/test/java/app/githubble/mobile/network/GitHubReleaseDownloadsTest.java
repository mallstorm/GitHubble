package app.githubble.mobile.network;

import static org.junit.Assert.*;

import org.junit.Test;

public class GitHubReleaseDownloadsTest {
  @Test
  public void credentialsOnlyGoToExactAssetApi() {
    assertTrue(
        GitHubReleaseDownloads.validAssetApi(
            "https://api.github.com/repos/owner/repo/releases/assets/123"));
    for (String u :
        new String[] {
          "https://api.github.com.evil.test/repos/a/b/releases/assets/1",
          "http://api.github.com/repos/a/b/releases/assets/1",
          "https://user@api.github.com/repos/a/b/releases/assets/1",
          "https://api.github.com/repos/a/b/releases/assets/1?token=x"
        }) assertFalse(u, GitHubReleaseDownloads.validAssetApi(u));
  }

  @Test
  public void redirectsCannotEscapeGithubCdnOrDowngradeTls() {
    assertTrue(
        GitHubReleaseDownloads.validRedirect(
            "https://release-assets.githubusercontent.com/file?sig=example"));
    for (String u :
        new String[] {
          "http://release-assets.githubusercontent.com/file",
          "https://evil.test/file",
          "https://release-assets.githubusercontent.com.evil.test/file",
          "https://user@release-assets.githubusercontent.com/file"
        }) assertFalse(u, GitHubReleaseDownloads.validRedirect(u));
  }
}
