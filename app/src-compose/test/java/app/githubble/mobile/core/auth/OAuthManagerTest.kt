package app.githubble.mobile.core.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class OAuthManagerTest {
    @Test fun callbackModelKeepsValues() {
        val callback = OAuthManager.CallbackResult("code", "state", null)
        assertEquals("code", callback.code)
        assertEquals("state", callback.state)
    }
}
