package app.githubble.mobile.security

/** Minimal encryption boundary used by credential persistence. */
interface SecretCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

