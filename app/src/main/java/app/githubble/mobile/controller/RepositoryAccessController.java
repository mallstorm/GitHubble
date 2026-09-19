package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative repository synchronization for GitHub App user access tokens.
 *
 * The important distinction is that a user token can access only the
 * intersection of resources available to the user and resources granted to
 * the GitHub App installation. /user/installations + installation repositories
 * exposes that intersection directly.
 */
public final class RepositoryAccessController {
    private static final int PER_PAGE = 100;
    private static final int MAX_PAGES = 20;

    public static final class Installation {
        public final long id;
        public final String account;
        public final String accountType;
        public final String repositorySelection;
        public final JSONObject permissions;
        public final int repositoryCount;
        public final int privateCount;
        public final int publicCount;
        public final String error;

        Installation(long id, String account, String accountType, String repositorySelection,
                     JSONObject permissions, int repositoryCount, int privateCount,
                     int publicCount, String error) {
            this.id = id;
            this.account = account == null ? "" : account;
            this.accountType = accountType == null ? "" : accountType;
            this.repositorySelection = repositorySelection == null ? "" : repositorySelection;
            this.permissions = permissions == null ? new JSONObject() : permissions;
            this.repositoryCount = repositoryCount;
            this.privateCount = privateCount;
            this.publicCount = publicCount;
            this.error = error == null ? "" : error;
        }

        public boolean metadataReadable() {
            String value = permissions.optString("metadata", "");
            return "read".equalsIgnoreCase(value) || "write".equalsIgnoreCase(value);
        }

        public String settingsUrl() {
            return "https://github.com/settings/installations/" + id;
        }
    }

    public static final class SyncResult {
        public final JSONArray repositories;
        public final List<Installation> installations;
        public final int privateCount;
        public final int publicCount;
        public final boolean fallbackUsed;
        public final String warning;

        SyncResult(JSONArray repositories, List<Installation> installations,
                   int privateCount, int publicCount, boolean fallbackUsed, String warning) {
            this.repositories = repositories;
            this.installations = Collections.unmodifiableList(installations);
            this.privateCount = privateCount;
            this.publicCount = publicCount;
            this.fallbackUsed = fallbackUsed;
            this.warning = warning == null ? "" : warning;
        }

        public boolean hasInstallations() { return !installations.isEmpty(); }

        public String bestSettingsUrl() {
            return installations.isEmpty()
                    ? "https://github.com/settings/installations"
                    : installations.get(0).settingsUrl();
        }
    }

    private final GitHubApiClient api;

    public RepositoryAccessController(GitHubApiClient api) {
        this.api = api;
    }

    public SyncResult sync(boolean forceNetwork) throws Exception {
        if (forceNetwork) {
            api.invalidatePrefix("https://api.github.com/user/installations");
            api.invalidatePrefix("https://api.github.com/user/repos");
            api.invalidatePrefix("https://api.github.com/user/installations/");
        }

        Map<String, JSONObject> merged = new LinkedHashMap<>();
        List<Installation> installations = new ArrayList<>();
        String warning = "";

        try {
            JSONArray rawInstallations = loadInstallations();
            for (int i = 0; i < rawInstallations.length(); i++) {
                JSONObject installation = rawInstallations.optJSONObject(i);
                if (installation == null) continue;

                long id = installation.optLong("id", 0L);
                JSONObject account = installation.optJSONObject("account");
                String login = account == null ? "" : account.optString("login", "");
                String type = account == null ? "" : account.optString("type", "");
                String selection = installation.optString("repository_selection", "");
                JSONObject permissions = installation.optJSONObject("permissions");

                int privateCount = 0;
                int publicCount = 0;
                String error = "";

                if (id > 0L) {
                    try {
                        JSONArray repos = loadInstallationRepositories(id);
                        for (int j = 0; j < repos.length(); j++) {
                            JSONObject repository = repos.optJSONObject(j);
                            if (repository == null) continue;
                            String full = repository.optString("full_name", "");
                            if (full.isEmpty()) continue;
                            merged.put(full, repository);
                            if (repository.optBoolean("private", false)) privateCount++;
                            else publicCount++;
                        }
                    } catch (GitHubApiClient.ApiException e) {
                        error = "HTTP " + e.statusCode + ": " + e.getMessage();
                    }
                }

                installations.add(new Installation(
                        id, login, type, selection, permissions,
                        privateCount + publicCount, privateCount, publicCount, error));
            }
        } catch (GitHubApiClient.ApiException e) {
            // Older/non-GitHub-App tokens may not support installation endpoints.
            warning = e.getMessage();
        }

        // Merge every page of /user/repos even when installations returned some entries.
        // /user/repos can contain projects that an installation endpoint did not return.
        boolean fallbackUsed = false;
        try {
            JSONArray userRepositories = loadUserRepositories();
            for (int i = 0; i < userRepositories.length(); i++) {
                JSONObject repository = userRepositories.optJSONObject(i);
                if (repository == null) continue;
                String full = repository.optString("full_name", "");
                if (!full.isEmpty()) merged.put(full, repository);
            }
            fallbackUsed = true;
        } catch (GitHubApiClient.ApiException e) {
            // Authentication failures must not be disguised as an empty repository list.
            if (e.statusCode == 401) throw e;
            warning = warning.isEmpty() ? e.getMessage() : warning + "; " + e.getMessage();
            if (merged.isEmpty()) throw e;
        }

        List<JSONObject> sorted = new ArrayList<>(merged.values());
        sorted.sort((a, b) -> b.optString("pushed_at", b.optString("updated_at", ""))
                .compareTo(a.optString("pushed_at", a.optString("updated_at", ""))));

        JSONArray repositories = new JSONArray();
        int privateCount = 0;
        int publicCount = 0;
        for (JSONObject repository : sorted) {
            repositories.put(repository);
            if (repository.optBoolean("private", false)) privateCount++;
            else publicCount++;
        }

        installations.sort(Comparator.comparing((Installation x) -> x.account.toLowerCase()));
        return new SyncResult(repositories, installations, privateCount, publicCount, fallbackUsed, warning);
    }

    private JSONArray loadInstallations() throws Exception {
        JSONArray result = new JSONArray();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JSONObject response = api.getJson("https://api.github.com/user/installations?per_page="
                    + PER_PAGE + "&page=" + page);
            JSONArray batch = response.optJSONArray("installations");
            if (batch == null || batch.length() == 0) break;
            for (int i = 0; i < batch.length(); i++) result.put(batch.opt(i));
            if (batch.length() < PER_PAGE) break;
        }
        return result;
    }

    private JSONArray loadInstallationRepositories(long installationId) throws Exception {
        JSONArray result = new JSONArray();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JSONObject response = api.getJson("https://api.github.com/user/installations/"
                    + installationId + "/repositories?per_page=" + PER_PAGE + "&page=" + page);
            JSONArray batch = response.optJSONArray("repositories");
            if (batch == null || batch.length() == 0) break;
            for (int i = 0; i < batch.length(); i++) result.put(batch.opt(i));
            if (batch.length() < PER_PAGE) break;
        }
        return result;
    }

    private JSONArray loadUserRepositories() throws Exception {
        JSONArray result = new JSONArray();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JSONArray batch = api.getArray("https://api.github.com/user/repos?visibility=all"
                    + "&affiliation=owner,collaborator,organization_member&sort=updated&per_page="
                    + PER_PAGE + "&page=" + page);
            for (int i = 0; i < batch.length(); i++) result.put(batch.opt(i));
            if (batch.length() < PER_PAGE) break;
        }
        return result;
    }
}
