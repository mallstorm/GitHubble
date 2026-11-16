package app.githubble.mobile;

import static org.junit.Assert.*;

import org.junit.Test;

public class AppLockPolicyTest {
    private static final long T = AppLockPolicy.TIMEOUT_MS;

    @Test public void disabledNeverLocks() { assertFalse(AppLockPolicy.needsUnlock(false, 0, 0, 1_000_000, T)); }
    @Test public void coldStartLocks() { assertTrue(AppLockPolicy.needsUnlock(true, 0, 0, 1000, T)); }
    @Test public void returningQuicklyDoesNotLock() { assertFalse(AppLockPolicy.needsUnlock(true, 100_000, 50_000, 100_000 + T - 1, T)); }
    @Test public void returningAfterTimeoutLocks() { assertTrue(AppLockPolicy.needsUnlock(true, 100_000, 50_000, 100_000 + T, T)); }
    @Test public void unlockAfterBackgroundingClearsTheLock() { assertFalse(AppLockPolicy.needsUnlock(true, 100_000, 200_000, 900_000, T)); }
}
