package app.githubble.mobile.security

/** Keystore-backed API/account credential vault. Public behavior and key alias stay unchanged. */
class KeyVault : SecretCipher {
    private val delegate = AndroidKeystoreAesGcmVault(
        alias = "githubble_xray_v1",
        invalidFormatMessage = "Неверный формат защищённого ключа"
    )

    override fun encrypt(value: String): String = delegate.encrypt(value)

    override fun decrypt(value: String): String = delegate.decrypt(value)
}

