package app.githubble.mobile.core.auth

import android.net.Uri
import android.util.Base64
import app.githubble.mobile.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom

class OAuthManager {
    data class Request(val url: Uri, val state: String, val verifier: String)

    fun createRequest(): Request {
        require(BuildConfig.GITHUB_CLIENT_ID.isNotBlank()) {
            "Не указан GitHub Client ID. Добавьте его в BuildConfig перед тестированием входа."
        }
        val state = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val url = Uri.parse("https://github.com/login/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.GITHUB_CLIENT_ID)
            .appendQueryParameter("redirect_uri", BuildConfig.OAUTH_CALLBACK)
            .appendQueryParameter("scope", "read:user repo notifications")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        return Request(url, state, verifier)
    }

    fun parseCallback(uri: Uri): CallbackResult = CallbackResult(
        code = uri.getQueryParameter("code"),
        state = uri.getQueryParameter("state"),
        error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
    )

    data class CallbackResult(val code: String?, val state: String?, val error: String?)

    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
