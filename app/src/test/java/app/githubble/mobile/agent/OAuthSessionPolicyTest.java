package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;

public class OAuthSessionPolicyTest {
    @Test public void sameEmailIsSameAccountAcrossRefreshes() throws Exception {
        JSONObject a = new JSONObject().put("identity", "Me@Mail.com").put("account_id", "x").put("refresh_token", "r1");
        JSONObject b = new JSONObject().put("identity", "me@mail.com").put("account_id", "x").put("refresh_token", "r2");
        assertEquals(OAuthSessionPolicy.identityKey(a), OAuthSessionPolicy.identityKey(b));
    }

    @Test public void differentAccountsOfOneServiceAreDistinct() throws Exception {
        JSONObject a = new JSONObject().put("identity", "one@mail.com").put("account_id", "1");
        JSONObject b = new JSONObject().put("identity", "two@mail.com").put("account_id", "2");
        assertNotEquals(OAuthSessionPolicy.identityKey(a), OAuthSessionPolicy.identityKey(b));
    }

    @Test public void withoutIdentityTheKeyIsAHashNeverTheSecret() throws Exception {
        String key = OAuthSessionPolicy.identityKey(new JSONObject().put("refresh_token", "secret-refresh"));
        assertTrue(key.startsWith("r:"));
        assertFalse(key.contains("secret-refresh"));
        assertEquals("", OAuthSessionPolicy.identityKey(new JSONObject()));
    }

    @Test public void nextAccountSkipsRestingOnesAndWraps() {
        long now = 1000;
        assertEquals(1, OAuthSessionPolicy.pickNext(Arrays.asList(0L, 0L, 0L), 0, now));
        assertEquals(2, OAuthSessionPolicy.pickNext(Arrays.asList(0L, 5000L, 0L), 0, now));
        assertEquals(0, OAuthSessionPolicy.pickNext(Arrays.asList(0L, 5000L, 5000L), 1, now));
        assertEquals(-1, OAuthSessionPolicy.pickNext(Arrays.asList(0L, 5000L, 5000L), 0, now));
        assertEquals(-1, OAuthSessionPolicy.pickNext(Arrays.asList(0L), 0, now));
    }

    @Test public void restTimeFollowsTheKindOfFailure() {
        assertTrue(OAuthSessionPolicy.pauseMillis(PoolPolicy.Failure.QUOTA) > OAuthSessionPolicy.pauseMillis(PoolPolicy.Failure.RATE_LIMIT));
        assertEquals(0, OAuthSessionPolicy.pauseMillis(PoolPolicy.Failure.REQUEST));
    }

    @Test public void labelFallsBackToNumberedAccount() throws Exception {
        assertEquals("a@b.c", OAuthSessionPolicy.label(new JSONObject().put("identity", "a@b.c"), 1));
        assertEquals("Аккаунт 2 · plus", OAuthSessionPolicy.label(new JSONObject().put("plan", "plus"), 2));
        assertEquals("Аккаунт 3", OAuthSessionPolicy.label(new JSONObject(), 3));
    }
}
