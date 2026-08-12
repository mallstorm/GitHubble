package app.githubble.mobile.core.auth

import android.content.Context

class OAuthSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("githubble_oauth_session", Context.MODE_PRIVATE)
    fun save(state: String, verifier: String) = prefs.edit().putString("state", state).putString("verifier", verifier).apply()
    fun consume(expectedState: String?): String? {
        val storedState = prefs.getString("state", null)
        val verifier = prefs.getString("verifier", null)
        prefs.edit().clear().apply()
        return if (expectedState != null && expectedState == storedState) verifier else null
    }
}
