package app.githubble.mobile;

import app.githubble.mobile.repository.Gh;

/** Validation of the «owner/repository» entered as a template. */
final class RepoSettingsSource {
    private RepoSettingsSource() {}
    static void check(String raw) { Gh.repo(raw == null ? "" : raw.trim()); }
}
