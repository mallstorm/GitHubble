package app.githubble.mobile;

import app.githubble.mobile.repository.RepoSecrets;

/** Validation of the «owner/repository» entered as a template. */
final class RepoSettingsSource {
    private RepoSettingsSource() {}
    static String check(String raw) { return RepoSecrets.checkRepo(raw == null ? "" : raw.trim()); }
}
