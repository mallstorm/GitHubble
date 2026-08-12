package app.githubble.mobile.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureTokenStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "githubble_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveAccessToken(token: String) = prefs.edit().putString(KEY_TOKEN, token).apply()
    fun accessToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun clear() = prefs.edit().clear().apply()

    private companion object { const val KEY_TOKEN = "github_access_token" }
}
