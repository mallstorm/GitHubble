package app.githubble.mobile.network

import okhttp3.HttpUrl

/** Shared URL policy for API transports and local proxy compatibility. */
object NetworkEndpointPolicy {
    private val localHosts = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    fun isLocal(host: String): Boolean = host.lowercase() in localHosts

    fun requireApiUrl(url: HttpUrl, allowLocalHttp: Boolean = false) {
        val localHttp = allowLocalHttp && url.scheme == "http" && isLocal(url.host)
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            (url.scheme != "https" && !localHttp)
        ) {
            throw ApiFailure(
                if (allowLocalHttp) "Нужен HTTPS URL без параметров и пароля; HTTP разрешён только для localhost"
                else "Нужен HTTPS URL без параметров и пароля"
            )
        }
    }
}

