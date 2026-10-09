package app.githubble.mobile.build;

import java.time.Instant;
import java.util.Locale;

/** Pure policy for deciding when an Actions run deserves short-lived adaptive follow-up checks. */
public final class BuildWatchPolicy {
    /** Cached active state older than this no longer keeps fast background checks alive. */
    public static final long ACTIVE_CACHE_MAX_AGE_MS = 10L * 60L * 1000L;
    /** GitHub can publish artifacts shortly after a successful run becomes completed. */
    public static final long ARTIFACT_GRACE_MS = 5L * 60L * 1000L;

    private BuildWatchPolicy() {}

    public static boolean isActiveStatus(String status) {
        String value = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return "queued".equals(value)
                || "in_progress".equals(value)
                || "requested".equals(value)
                || "waiting".equals(value)
                || "pending".equals(value);
    }

    public static boolean needsFastFollowUp(String status, String conclusion,
                                            int artifactCount, long updatedAtMillis,
                                            long nowMillis) {
        if (isActiveStatus(status)) return true;
        String normalizedStatus = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        String normalizedConclusion = conclusion == null ? "" : conclusion.trim().toLowerCase(Locale.ROOT);
        if (!"completed".equals(normalizedStatus)
                || !"success".equals(normalizedConclusion)
                || artifactCount > 0
                || updatedAtMillis <= 0L) return false;
        long age = Math.max(0L, nowMillis - updatedAtMillis);
        return age <= ARTIFACT_GRACE_MS;
    }

    public static long parseGitHubTime(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try { return Instant.parse(value.trim()).toEpochMilli(); }
        catch (Exception ignored) { return 0L; }
    }
}
