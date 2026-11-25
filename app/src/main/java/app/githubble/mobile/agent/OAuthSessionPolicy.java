package app.githubble.mobile.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Pure rules for several sign-in sessions of one subscription service (which account is which, who is next). */
public final class OAuthSessionPolicy {
    private OAuthSessionPolicy() {}

    /** Stable identity of an account across token refreshes; secrets are hashed and never stored as is. */
    public static String identityKey(JSONObject session) {
        if (session == null) return "";
        String identity = session.optString("identity").trim().toLowerCase(Locale.ROOT);
        if (!identity.isEmpty()) return "id:" + identity + "|" + session.optString("account_id").trim();
        String refresh = session.optString("refresh_token");
        if (!refresh.isEmpty()) return "r:" + sha256(refresh);
        String access = session.optString("access_token");
        return access.isEmpty() ? "" : "a:" + sha256(access);
    }

    /** How long a failed account rests before it is tried again. */
    public static long pauseMillis(PoolPolicy.Failure failure) {
        switch (failure) {
            case QUOTA: return 30L * 60_000;
            case AUTH: return 60L * 60_000;
            case RATE_LIMIT: return 2L * 60_000;
            case TRANSIENT: return 15_000;
            default: return 0;
        }
    }

    /** Index of the next account after {@code failed} (wrapping) that is not resting, or -1. */
    public static int pickNext(List<Long> pausedUntil, int failed, long now) {
        int size = pausedUntil.size();
        for (int step = 1; step < size; step++) {
            int index = (failed + step) % size;
            if (pausedUntil.get(index) <= now) return index;
        }
        return -1;
    }

    public static String label(JSONObject session, int number) {
        String identity = session.optString("identity").trim();
        if (!identity.isEmpty()) return identity;
        String plan = session.optString("plan").trim();
        return plan.isEmpty() ? "Аккаунт " + number : "Аккаунт " + number + " · " + plan;
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
