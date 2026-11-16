package app.githubble.mobile;

/** When the app asks for the device PIN, pattern, password or biometrics again. Pure logic. */
public final class AppLockPolicy {
    public static final long TIMEOUT_MS = 30_000L;
    private AppLockPolicy() {}

    /**
     * @param backgroundedAt monotonic time the app last left the screen (0 = never)
     * @param unlockedAt     monotonic time of the last successful unlock (0 = not yet this process)
     */
    public static boolean needsUnlock(boolean enabled, long backgroundedAt, long unlockedAt, long now, long timeoutMs) {
        if (!enabled) return false;
        if (unlockedAt == 0) return true;
        return backgroundedAt > unlockedAt && now - backgroundedAt >= timeoutMs;
    }
}
