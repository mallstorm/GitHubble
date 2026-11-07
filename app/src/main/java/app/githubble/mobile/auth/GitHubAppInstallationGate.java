package app.githubble.mobile.auth;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Gate between GitHub user authorization and GitHub App installation.
 */
public final class GitHubAppInstallationGate {
    private GitHubAppInstallationGate() {}

    public static boolean hasInstallationForLogin(JSONObject response, String login) {
        if (response == null || login == null || login.trim().isEmpty()) return false;
        JSONArray installations = response.optJSONArray("installations");
        if (installations == null) return false;
        String wanted = login.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < installations.length(); i++) {
            JSONObject installation = installations.optJSONObject(i);
            if (installation == null) continue;
            Object suspended = installation.opt("suspended_at");
            if (suspended != null && suspended != JSONObject.NULL) continue;
            JSONObject account = installation.optJSONObject("account");
            if (account == null) continue;
            String actual = account.optString("login", "").trim().toLowerCase(Locale.ROOT);
            String targetType = installation.optString("target_type", "");
            if (wanted.equals(actual) && (targetType.isEmpty() || "User".equalsIgnoreCase(targetType))) {
                return true;
            }
        }
        return false;
    }

    /** An organization installation can provide all access needed by this user. */
    public static boolean hasUsableInstallation(JSONObject response) {
        if (response == null) return false;
        JSONArray items = response.optJSONArray("installations");
        for (int i=0;items!=null && i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);
            if (item==null || item.optLong("id",0)<=0 || !item.isNull("suspended_at")) continue;
            JSONObject account=item.optJSONObject("account");
            if (account!=null && !account.optString("login", "").isEmpty()) return true;
        }
        return false;
    }

    public static String installUrl(String appSlug) {
        String slug = appSlug == null ? "" : appSlug.trim().toLowerCase(Locale.ROOT);
        if (!slug.matches("[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?")) slug = "gihubble";
        return "https://github.com/apps/" + slug + "/installations/new";
    }
}
