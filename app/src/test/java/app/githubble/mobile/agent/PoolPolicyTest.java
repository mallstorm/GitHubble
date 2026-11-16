package app.githubble.mobile.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class PoolPolicyTest {
    private static final long NOW = 1_700_000_000_000L;

    private static PoolPolicy.State state(int strategy, String... ids) {
        PoolPolicy.State s = new PoolPolicy.State();
        s.strategy = strategy;
        for (String id : ids) {
            PoolPolicy.Slot slot = new PoolPolicy.Slot();
            slot.id = id; slot.label = id; slot.periodStart = NOW;
            s.slots.add(slot);
        }
        return s;
    }

    @Test public void priorityUsesFirstAvailableAndSkipsDisabled() {
        PoolPolicy.State s = state(PoolPolicy.PRIORITY, "a", "b", "c");
        assertEquals("a", PoolPolicy.pick(s, null, NOW).id);
        PoolPolicy.setEnabled(s.find("a"), false);
        assertEquals("b", PoolPolicy.pick(s, null, NOW).id);
        PoolPolicy.setEnabled(s.find("b"), false);
        PoolPolicy.setEnabled(s.find("c"), false);
        assertNull(PoolPolicy.pick(s, null, NOW));
    }

    @Test public void roundRobinRotatesAndSkipsExcluded() {
        PoolPolicy.State s = state(PoolPolicy.ROUND_ROBIN, "a", "b", "c");
        assertEquals("a", PoolPolicy.pick(s, null, NOW).id);
        assertEquals("b", PoolPolicy.pick(s, null, NOW).id);
        assertEquals("c", PoolPolicy.pick(s, null, NOW).id);
        assertEquals("a", PoolPolicy.pick(s, null, NOW).id);
        Set<String> skip = new HashSet<>(Collections.singleton("b"));
        assertEquals("c", PoolPolicy.pick(s, skip, NOW).id);
    }

    @Test public void leastUsedPrefersQuietKey() {
        PoolPolicy.State s = state(PoolPolicy.LEAST_USED, "a", "b");
        PoolPolicy.onSuccess(s.find("a"), 10, 10, true, NOW);
        assertEquals("b", PoolPolicy.pick(s, null, NOW).id);
    }

    @Test public void mostQuotaPrefersLargerHeadroom() {
        PoolPolicy.State s = state(PoolPolicy.MOST_QUOTA, "a", "b");
        s.find("a").quotaTokens = 1000; s.find("b").quotaTokens = 1000;
        PoolPolicy.onSuccess(s.find("a"), 400, 400, true, NOW);
        assertEquals("b", PoolPolicy.pick(s, null, NOW).id);
    }

    @Test public void quotaLimitTakesKeyOutOfRotation() {
        PoolPolicy.State s = state(PoolPolicy.PRIORITY, "a", "b");
        s.find("a").quotaTokens = 100;
        PoolPolicy.onSuccess(s.find("a"), 60, 50, true, NOW);
        assertEquals(PoolPolicy.Status.LIMIT, PoolPolicy.status(s.find("a"), NOW));
        assertEquals("b", PoolPolicy.pick(s, null, NOW).id);
    }

    @Test public void periodResetRestoresKey() {
        PoolPolicy.State s = state(PoolPolicy.PRIORITY, "a");
        PoolPolicy.Slot a = s.find("a");
        a.quotaRequests = 1; a.resetMode = PoolPolicy.RESET_DAILY;
        PoolPolicy.onSuccess(a, 0, 0, false, NOW);
        assertNull(PoolPolicy.pick(s, null, NOW + 1000));
        assertNotNull(PoolPolicy.pick(s, null, NOW + 25L * 3600 * 1000));
        assertEquals(0, a.periodRequests);
    }

    @Test public void quotaFailureExhaustsUntilReEnabled() {
        PoolPolicy.State s = state(PoolPolicy.PRIORITY, "a", "b");
        PoolPolicy.onFailure(s.find("a"), PoolPolicy.Failure.QUOTA, "insufficient_quota", NOW);
        assertEquals(PoolPolicy.Status.EXHAUSTED, PoolPolicy.status(s.find("a"), NOW + 10 * 86400000L));
        assertEquals("b", PoolPolicy.pick(s, null, NOW).id);
        PoolPolicy.setEnabled(s.find("a"), false);
        PoolPolicy.setEnabled(s.find("a"), true);
        assertEquals("a", PoolPolicy.pick(s, null, NOW).id);
    }

    @Test public void rateLimitPausesThenRecovers() {
        PoolPolicy.State s = state(PoolPolicy.PRIORITY, "a", "b");
        PoolPolicy.onFailure(s.find("a"), PoolPolicy.Failure.RATE_LIMIT, "429", NOW);
        assertEquals("b", PoolPolicy.pick(s, null, NOW + 1000).id);
        assertEquals("a", PoolPolicy.pick(s, null, NOW + 31_000).id);
    }

    @Test public void authFailureIsReportedAsKeyError() {
        PoolPolicy.State s = state(PoolPolicy.PRIORITY, "a");
        PoolPolicy.onFailure(s.find("a"), PoolPolicy.Failure.AUTH, "401", NOW);
        assertEquals(PoolPolicy.Status.AUTH_ERROR, PoolPolicy.status(s.find("a"), NOW + 1000));
    }

    @Test public void classifiesStatusCodes() {
        assertEquals(PoolPolicy.Failure.AUTH, PoolPolicy.classify(401, "bad"));
        assertEquals(PoolPolicy.Failure.RATE_LIMIT, PoolPolicy.classify(429, "slow down"));
        assertEquals(PoolPolicy.Failure.QUOTA, PoolPolicy.classify(429, "You exceeded your current quota"));
        assertEquals(PoolPolicy.Failure.QUOTA, PoolPolicy.classify(402, ""));
        assertEquals(PoolPolicy.Failure.TRANSIENT, PoolPolicy.classify(503, ""));
        assertEquals(PoolPolicy.Failure.REQUEST, PoolPolicy.classify(400, "model not found"));
        assertEquals(PoolPolicy.Failure.TRANSIENT, PoolPolicy.classify(0, "Unable to resolve host"));
        assertEquals(PoolPolicy.Failure.REQUEST, PoolPolicy.classify(0, "weird parse error"));
        assertFalse(PoolPolicy.failover(PoolPolicy.Failure.REQUEST));
        assertTrue(PoolPolicy.failover(PoolPolicy.Failure.AUTH));
    }

    @Test public void summaryCountsOnlyEnabledKeys() {
        PoolPolicy.State s = state(PoolPolicy.PRIORITY, "a", "b", "c");
        s.find("a").quotaTokens = 1000; s.find("b").quotaTokens = 2000; s.find("c").quotaTokens = 4000;
        PoolPolicy.onSuccess(s.find("a"), 100, 100, true, NOW);
        PoolPolicy.onSuccess(s.find("b"), 300, 0, true, NOW);
        PoolPolicy.onSuccess(s.find("c"), 50, 50, true, NOW);
        PoolPolicy.Summary all = PoolPolicy.summarize(s.slots, NOW);
        assertEquals(7000, all.quotaTokens);
        assertEquals(600, all.usedOfQuotaTokens);
        assertEquals(6400, all.remainingTokens());
        PoolPolicy.setEnabled(s.find("c"), false);
        PoolPolicy.Summary partial = PoolPolicy.summarize(s.slots, NOW);
        assertEquals(3000, partial.quotaTokens);
        assertEquals(500, partial.usedOfQuotaTokens);
        assertEquals(2, partial.enabled);
        assertEquals(3, partial.total);
        assertEquals(2, partial.calls);
    }

    @Test public void stateSurvivesJsonRoundTrip() {
        PoolPolicy.State s = state(PoolPolicy.ROUND_ROBIN, "a", "b");
        s.cursor = 1;
        s.find("a").quotaTokens = 5; s.find("a").lastError = "x \"quoted\"";
        PoolPolicy.onSuccess(s.find("b"), 1, 2, true, NOW);
        PoolPolicy.State back = PoolPolicy.State.fromJson(s.toJson());
        assertEquals(PoolPolicy.ROUND_ROBIN, back.strategy);
        assertEquals(1, back.cursor);
        assertEquals(2, back.slots.size());
        assertEquals(5, back.find("a").quotaTokens);
        assertEquals("x \"quoted\"", back.find("a").lastError);
        assertEquals(3, back.find("b").totalTokens());
        assertEquals(0, PoolPolicy.State.fromJson("not json").slots.size());
    }

    @Test public void compactFormatsNumbers() {
        assertEquals("999", PoolPolicy.compact(999));
        assertEquals("1.5K", PoolPolicy.compact(1500));
        assertEquals("12K", PoolPolicy.compact(12_000));
        assertEquals("2.5M", PoolPolicy.compact(2_500_000));
    }
}
