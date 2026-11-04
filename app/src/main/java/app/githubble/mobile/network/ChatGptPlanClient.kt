package app.githubble.mobile.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OpenAI "Sign in with ChatGPT" public-client flow for ChatGPT plan usage.
 *
 * Official credentials use api.openai.com/v1.
 * Imported /api/auth/session JSON is a separate experimental ChatGPT Web session.
 * It must never be routed through the Codex backend.
 */
class ChatGptPlanClient(
    private val authBaseUrl: String = AUTH_BASE_URL,
    private val apiBaseUrl: String = API_BASE_URL,
    private val jwksUrl: String = JWKS_URL,
    private val webBaseUrl: String = WEB_BASE_URL,
    private val client: OkHttpClient = defaultClient()
) {
    data class Login(
        val authUrl: String,
        val redirectUri: String,
        val state: String,
        val nonce: String,
        val verifier: String,
        val requestedClientId: String,
        val previousSubject: String,
        internal val server: ServerSocket
    )

    data class Credentials(
        val clientId: String,
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val tokenType: String,
        val expiresAtMs: Long,
        val scopes: Set<String>,
        val subject: String,
        val email: String,
        val name: String,
        val plan: String,
        val accountId: String,
        val transport: String
    ) {
        fun expiresSoon(now: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs > 0L && expiresAtMs <= now + REFRESH_WINDOW_MS

        fun expired(now: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs > 0L && expiresAtMs <= now

        fun toJson(): String = JSONObject()
            .put("client_id", clientId)
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken)
            .put("id_token", idToken)
            .put("token_type", tokenType)
            .put("expires_at_ms", expiresAtMs)
            .put("scopes", JSONArray(scopes.toList()))
            .put("subject", subject)
            .put("email", email)
            .put("name", name)
            .put("plan", plan)
            .put("account_id", accountId)
            .put("transport", transport)
            .toString()

        companion object {
            fun fromJson(raw: String): Credentials? = runCatching {
                val json = JSONObject(raw)
                val scopes = json.optJSONArray("scopes")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        array.optString(i).trim().takeIf(String::isNotBlank)
                    }.toSet()
                } ?: emptySet()
                Credentials(
                    clientId = json.optString("client_id"),
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token"),
                    idToken = json.optString("id_token"),
                    tokenType = json.optString("token_type", "Bearer"),
                    expiresAtMs = json.optLong("expires_at_ms"),
                    scopes = scopes,
                    subject = json.optString("subject"),
                    email = json.optString("email"),
                    name = json.optString("name"),
                    plan = json.optString("plan"),
                    accountId = json.optString("account_id"),
                    transport = json.optString("transport", TRANSPORT_PUBLIC).let { stored ->
                        if (stored == "legacy-codex") TRANSPORT_WEB_SESSION else stored
                    }
                )
            }.getOrNull()
        }
    }

    fun beginLogin(hostId: String, previous: Credentials? = null): Login {
        require(hostId.startsWith("urn:")) { "Некорректный ChatGPT host ID" }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = LOGIN_TIMEOUT_MS.toInt()
        }
        val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
        val state = randomUrlToken(32)
        val nonce = randomUrlToken(32)
        val verifier = randomUrlToken(64)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        val requestedClientId = previous?.clientId?.takeIf { it.startsWith("oaiapp_") }
            ?: DYNAMIC_CLIENT_ID

        val builder = "$authBaseUrl/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", requestedClientId)
            .addQueryParameter("ext_agent_host_id", hostId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", REQUIRED_SCOPES.joinToString(" "))
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge)

        if (requestedClientId == DYNAMIC_CLIENT_ID) {
            builder.addQueryParameter("agent_name_hint", AGENT_NAME)
        } else {
            previous?.idToken?.takeIf(String::isNotBlank)?.let {
                builder.addQueryParameter("id_token_hint", it)
            }
            previous?.email?.takeIf(String::isNotBlank)?.let {
                builder.addQueryParameter("login_hint", it)
            }
        }

        return Login(
            authUrl = builder.build().toString(),
            redirectUri = redirectUri,
            state = state,
            nonce = nonce,
            verifier = verifier,
            requestedClientId = requestedClientId,
            previousSubject = previous?.subject.orEmpty(),
            server = server
        )
    }

    suspend fun awaitCredentials(login: Login): Credentials = withContext(Dispatchers.IO) {
        try {
            val callback = receiveCallback(login)
            val returnedState = callback["state"].orEmpty()
            if (returnedState != login.state) throw ApiFailure("ChatGPT OAuth: state не совпал")

            callback["error"]?.takeIf(String::isNotBlank)?.let { error ->
                val detail = callback["error_description"].orEmpty()
                throw ApiFailure(
                    if (detail.isBlank()) "ChatGPT OAuth: $error"
                    else "ChatGPT OAuth: $detail"
                )
            }

            val code = callback["code"].orEmpty()
            if (code.isBlank()) throw ApiFailure("ChatGPT OAuth не вернул authorization code")

            val returnedClientId = callback["client_id"].orEmpty()
            val issuedClientId = if (login.requestedClientId == DYNAMIC_CLIENT_ID) {
                returnedClientId.takeIf { it.startsWith("oaiapp_") }
                    ?: throw ApiFailure("ChatGPT не вернул выданный client_id")
            } else {
                if (returnedClientId.isNotBlank() && returnedClientId != login.requestedClientId) {
                    throw ApiFailure("ChatGPT OAuth вернул другой client_id")
                }
                login.requestedClientId
            }

            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", issuedClientId)
                .add("code", code)
                .add("code_verifier", login.verifier)
                .add("redirect_uri", login.redirectUri)
                .add("resource", RESOURCE)
                .build()
            val json = executeJson(
                Request.Builder()
                    .url("$authBaseUrl/api/accounts/oauth/token")
                    .header("Accept", "application/json")
                    .post(form)
                    .build(),
                "ChatGPT token exchange"
            )
            credentialsFromTokenResponse(
                json = json,
                clientId = issuedClientId,
                expectedNonce = login.nonce,
                previous = null,
                previousSubject = login.previousSubject
            )
        } finally {
            runCatching { login.server.close() }
        }
    }

    fun cancel(login: Login) {
        runCatching { login.server.close() }
    }

    suspend fun refresh(credentials: Credentials): Credentials = withContext(Dispatchers.IO) {
        require(credentials.clientId.startsWith("oaiapp_")) {
            "Эта JSON-сессия не содержит OpenAI client_id и не может автоматически обновляться"
        }
        require(credentials.refreshToken.isNotBlank()) {
            "Refresh token отсутствует — требуется повторный вход"
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", credentials.clientId)
            .add("refresh_token", credentials.refreshToken)
            .add("resource", RESOURCE)
            .build()
        val json = executeJson(
            Request.Builder()
                .url("$authBaseUrl/api/accounts/oauth/token")
                .header("Accept", "application/json")
                .post(form)
                .build(),
            "ChatGPT token refresh"
        )
        credentialsFromTokenResponse(
            json = json,
            clientId = credentials.clientId,
            expectedNonce = null,
            previous = credentials,
            previousSubject = credentials.subject
        )
    }

    suspend fun modelCatalog(credentials: Credentials): List<String> = withContext(Dispatchers.IO) {
        if (credentials.transport == TRANSPORT_WEB_SESSION) {
            webModelCatalog(credentials)
        } else {
            require(credentials.scopes.isEmpty() || DIRECT_SCOPE in credentials.scopes) {
                "ChatGPT plan usage не разрешён для этой сессии"
            }
            val request = Request.Builder()
                .url("$apiBaseUrl/models")
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val json = executeJson(request, "ChatGPT models")
            val models = json.optJSONArray("models") ?: json.optJSONArray("data") ?: JSONArray()
            val ids = (0 until models.length()).mapNotNull { i ->
                val item = models.optJSONObject(i) ?: return@mapNotNull null
                val visibility = item.optString("visibility")
                if (visibility.isNotBlank() && visibility != "list") return@mapNotNull null
                item.optString("slug")
                    .ifBlank { item.optString("id") }
                    .takeIf(String::isNotBlank)
            }.distinct()
            if (ids.isEmpty()) throw ApiFailure("ChatGPT не вернул доступные plan-модели")
            ids
        }
    }

    /**
     * Accepts the legacy /api/auth/session shape as well as a token-record JSON.
     * Imported tokens are not trusted as identity. They are isolated in the Experimental account.
     */
    fun importSessionJson(raw: String): Credentials {
        require(raw.length in 20..1_000_000) { "JSON-сессия имеет некорректный размер" }
        val root = runCatching { JSONObject(raw.trim()) }.getOrElse {
            throw ApiFailure("Некорректный JSON ChatGPT session")
        }
        val json = root.optJSONObject("session") ?: root
        val access = firstNonBlank(
            json.optString("access_token"),
            json.optString("accessToken"),
            json.optString("token")
        )
        if (access.isBlank()) throw ApiFailure("В JSON не найден accessToken")

        val idToken = firstNonBlank(json.optString("id_token"), json.optString("idToken"))
        val refresh = firstNonBlank(json.optString("refresh_token"), json.optString("refreshToken"))
        val clientId = firstNonBlank(json.optString("client_id"), json.optString("clientId"))
        val accessClaims = jwtClaims(access)
        val idClaims = jwtClaims(idToken)
        val nested = idClaims.optJSONObject(AUTH_CLAIM)
            ?: accessClaims.optJSONObject(AUTH_CLAIM)

        val scopes = parseScopes(json.opt("scope"), json.opt("scopes")).ifEmpty {
            parseJwtScopes(accessClaims)
        }
        val user = json.optJSONObject("user")
        val email = firstNonBlank(
            json.optString("email"),
            user?.optString("email").orEmpty(),
            idClaims.optString("email"),
            accessClaims.optString("email")
        )
        val name = firstNonBlank(
            json.optString("name"),
            user?.optString("name").orEmpty(),
            idClaims.optString("name")
        )
        val subject = firstNonBlank(
            json.optString("subject"),
            idClaims.optString("sub"),
            accessClaims.optString("sub"),
            user?.optString("id").orEmpty()
        )
        val accountId = firstNonBlank(
            json.optString("account_id"),
            json.optString("accountId"),
            nested?.optString("chatgpt_account_id").orEmpty(),
            idClaims.optString("chatgpt_account_id"),
            accessClaims.optString("chatgpt_account_id")
        )
        val plan = firstNonBlank(
            json.optString("plan"),
            nested?.optString("chatgpt_plan_type").orEmpty(),
            idClaims.optString("chatgpt_plan_type"),
            accessClaims.optString("chatgpt_plan_type")
        )
        val expiresAt = parseExpiry(json, accessClaims)
        // Anything imported through the Experimental JSON field is deliberately a
        // ChatGPT Web session. Official plan-sharing OAuth lives in the separate card.
        val transport = TRANSPORT_WEB_SESSION

        return Credentials(
            clientId = clientId,
            accessToken = access,
            refreshToken = refresh,
            idToken = idToken,
            tokenType = json.optString("token_type", "Bearer"),
            expiresAtMs = expiresAt,
            scopes = scopes,
            subject = subject,
            email = email,
            name = name,
            plan = plan,
            accountId = accountId,
            transport = transport
        )
    }

    private fun webModelCatalog(credentials: Credentials): List<String> {
        val request = Request.Builder()
            .url("$webBaseUrl/models")
            .header("Authorization", "Bearer ${credentials.accessToken}")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val json = executeJsonBlocking(request, "ChatGPT Web models")
        val models = json.optJSONArray("models") ?: json.optJSONArray("data") ?: JSONArray()
        val ids = (0 until models.length()).mapNotNull { i ->
            when (val item = models.opt(i)) {
                is String -> item.takeIf(String::isNotBlank)
                is JSONObject -> {
                    val visibility = item.optString("visibility")
                    if (visibility.equals("hide", true) || visibility.equals("none", true)) null
                    else item.optString("slug")
                        .ifBlank { item.optString("model") }
                        .ifBlank { item.optString("id") }
                        .ifBlank { item.optString("title") }
                        .takeIf(String::isNotBlank)
                }
                else -> null
            }
        }.distinct()
        if (ids.isEmpty()) throw ApiFailure(
            "ChatGPT Web session распознана, но веб-каталог моделей пуст"
        )
        return ids
    }

    private fun receiveCallback(login: Login): Map<String, String> {
        val socket = try {
            login.server.accept()
        } catch (error: Exception) {
            throw ApiFailure("ChatGPT OAuth callback не получен: ${error.message ?: "таймаут"}")
        }
        socket.use { connection ->
            val reader = connection.getInputStream().bufferedReader(Charsets.UTF_8)
            val first = reader.readLine().orEmpty()
            val target = first.split(' ').getOrNull(1).orEmpty()
            val params = runCatching {
                URI("http://127.0.0.1$target").rawQuery.orEmpty()
                    .split('&')
                    .filter { it.isNotBlank() }
                    .associate { part ->
                        val kv = part.split('=', limit = 2)
                        decode(kv[0]) to decode(kv.getOrElse(1) { "" })
                    }
            }.getOrElse { emptyMap() }

            val ok = params["state"] == login.state && params["error"].isNullOrBlank()
            val html = if (ok) {
                "<!doctype html><meta charset=utf-8><title>OmniChat</title>" +
                    "<h2>OmniChat подключён к ChatGPT</h2><p>Можно вернуться в приложение.</p>"
            } else {
                "<!doctype html><meta charset=utf-8><title>OmniChat</title>" +
                    "<h2>Вход ChatGPT не завершён</h2><p>Вернитесь в OmniChat и повторите вход.</p>"
            }
            val bytes = html.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            connection.getOutputStream().apply {
                write(header.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
            return params
        }
    }

    private fun credentialsFromTokenResponse(
        json: JSONObject,
        clientId: String,
        expectedNonce: String?,
        previous: Credentials?,
        previousSubject: String
    ): Credentials {
        val access = json.optString("access_token").ifBlank { previous?.accessToken.orEmpty() }
        val refresh = json.optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() }
        val newIdToken = json.optString("id_token")
        val idToken = newIdToken.ifBlank { previous?.idToken.orEmpty() }
        if (access.isBlank() || refresh.isBlank() || idToken.isBlank()) {
            throw ApiFailure("ChatGPT OAuth не вернул полный набор токенов")
        }
        if (previous == null && newIdToken.isBlank()) {
            throw ApiFailure("ChatGPT OAuth не вернул ID token")
        }

        val scopes = parseScopes(json.opt("scope"), json.opt("scopes")).ifEmpty {
            previous?.scopes ?: emptySet()
        }
        if (DIRECT_SCOPE !in scopes) {
            throw ApiFailure("ChatGPT plan usage не разрешён: отсутствует scope $DIRECT_SCOPE")
        }

        val claims = if (newIdToken.isNotBlank()) {
            validateIdToken(newIdToken, clientId, expectedNonce)
        } else {
            null
        }
        val subject = claims?.optString("sub").orEmpty().ifBlank { previous?.subject.orEmpty() }
        if (subject.isBlank()) throw ApiFailure("ChatGPT OAuth не вернул идентификатор аккаунта")
        if (previousSubject.isNotBlank() && subject != previousSubject) {
            throw ApiFailure("ChatGPT OAuth вернул другой аккаунт")
        }
        val nested = claims?.optJSONObject(AUTH_CLAIM)
        val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000L

        return Credentials(
            clientId = clientId,
            accessToken = access,
            refreshToken = refresh,
            idToken = idToken,
            tokenType = json.optString("token_type", previous?.tokenType ?: "Bearer"),
            expiresAtMs = expiresAt,
            scopes = scopes,
            subject = subject,
            email = claims?.optString("email").orEmpty().ifBlank { previous?.email.orEmpty() },
            name = claims?.optString("name").orEmpty().ifBlank { previous?.name.orEmpty() },
            plan = firstNonBlank(
                nested?.optString("chatgpt_plan_type").orEmpty(),
                claims?.optString("chatgpt_plan_type").orEmpty(),
                previous?.plan.orEmpty()
            ),
            accountId = firstNonBlank(
                nested?.optString("chatgpt_account_id").orEmpty(),
                claims?.optString("chatgpt_account_id").orEmpty(),
                previous?.accountId.orEmpty()
            ),
            transport = TRANSPORT_PUBLIC
        )
    }

    private fun validateIdToken(token: String, clientId: String, expectedNonce: String?): JSONObject {
        val parts = token.split('.')
        if (parts.size != 3) throw ApiFailure("ChatGPT ID token имеет неверный формат")
        val header = decodeJwtJson(parts[0])
        val claims = decodeJwtJson(parts[1])
        if (header.optString("alg") != "RS256") throw ApiFailure("ChatGPT ID token: неподдерживаемый alg")
        val kid = header.optString("kid")
        if (kid.isBlank()) throw ApiFailure("ChatGPT ID token не содержит kid")

        val jwks = executeJsonBlocking(
            Request.Builder().url(jwksUrl).header("Accept", "application/json").get().build(),
            "OpenAI JWKS"
        )
        val keys = jwks.optJSONArray("keys") ?: JSONArray()
        val jwk = (0 until keys.length()).mapNotNull { keys.optJSONObject(it) }
            .firstOrNull { it.optString("kid") == kid }
            ?: throw ApiFailure("OpenAI JWKS не содержит ключ ID token")

        val modulus = BigInteger(1, decodeBase64Url(jwk.optString("n")))
        val exponent = BigInteger(1, decodeBase64Url(jwk.optString("e")))
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        if (!signature.verify(decodeBase64Url(parts[2]))) {
            throw ApiFailure("ChatGPT ID token: подпись не прошла проверку")
        }

        if (claims.optString("iss") != ISSUER) throw ApiFailure("ChatGPT ID token: неверный issuer")
        val audienceOk = when (val aud = claims.opt("aud")) {
            is String -> aud == clientId
            is JSONArray -> (0 until aud.length()).any { aud.optString(it) == clientId }
            else -> false
        }
        if (!audienceOk) throw ApiFailure("ChatGPT ID token: неверный audience")
        val exp = claims.optLong("exp")
        if (exp <= System.currentTimeMillis() / 1000L - CLOCK_SKEW_SECONDS) {
            throw ApiFailure("ChatGPT ID token истёк")
        }
        if (claims.optString("sub").isBlank()) throw ApiFailure("ChatGPT ID token не содержит subject")
        if (expectedNonce != null && claims.optString("nonce") != expectedNonce) {
            throw ApiFailure("ChatGPT ID token: nonce не совпал")
        }
        return claims
    }

    private fun parseExpiry(json: JSONObject, accessClaims: JSONObject): Long {
        json.optLong("expires_at_ms").takeIf { it > 0L }?.let { return it }
        json.optLong("expires_at").takeIf { it > 0L }?.let {
            return if (it < 10_000_000_000L) it * 1000L else it
        }
        json.optLong("expires_in").takeIf { it > 0L }?.let {
            return System.currentTimeMillis() + it * 1000L
        }
        val expires = json.optString("expires")
        if (expires.isNotBlank()) parseIsoTime(expires)?.let { return it }
        accessClaims.optLong("exp").takeIf { it > 0L }?.let { return it * 1000L }
        return System.currentTimeMillis() + DEFAULT_ACCESS_TTL_MS
    }

    private fun parseIsoTime(value: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
        }
    }

    private fun parseScopes(vararg rawValues: Any?): Set<String> {
        val out = linkedSetOf<String>()
        rawValues.forEach { raw ->
            when (raw) {
                is String -> raw.split(' ', ',', '\n', '\t')
                    .map(String::trim).filter(String::isNotBlank).forEach(out::add)
                is JSONArray -> (0 until raw.length()).forEach { i ->
                    raw.optString(i).trim().takeIf(String::isNotBlank)?.let(out::add)
                }
            }
        }
        return out
    }

    private fun parseJwtScopes(claims: JSONObject): Set<String> =
        parseScopes(claims.opt("scope"), claims.opt("scp"))

    private fun jwtClaims(jwt: String): JSONObject {
        val payload = jwt.split('.').getOrNull(1).orEmpty()
        if (payload.isBlank()) return JSONObject()
        return runCatching { decodeJwtJson(payload) }.getOrElse { JSONObject() }
    }

    private fun decodeJwtJson(part: String): JSONObject =
        JSONObject(String(decodeBase64Url(part), Charsets.UTF_8))

    private fun executeJson(request: Request, label: String): JSONObject =
        executeJsonBlocking(request, label)

    private fun executeJsonBlocking(request: Request, label: String): JSONObject {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val json = JSONObject(raw)
                    when (val error = json.opt("error")) {
                        is JSONObject -> error.optString("message")
                            .ifBlank { error.optString("error_description") }
                            .ifBlank { error.optString("code") }
                        is String -> error
                        else -> json.optString("error_description").ifBlank { json.optString("message") }
                    }
                }.getOrDefault("").take(260)
                throw ApiFailure(
                    if (detail.isBlank()) "$label: HTTP ${response.code}"
                    else "$label: $detail (HTTP ${response.code})",
                    response.code
                )
            }
            return runCatching { JSONObject(raw) }.getOrElse {
                throw ApiFailure("$label: сервер вернул некорректный JSON")
            }
        }
    }

    private fun randomUrlToken(bytes: Int): String =
        ByteArray(bytes).also(SecureRandom()::nextBytes).let(::base64Url)

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeBase64Url(value: String): ByteArray {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull(String::isNotBlank).orEmpty()

    companion object {
        const val AUTH_BASE_URL = "https://auth.openai.com"
        const val API_BASE_URL = "https://api.openai.com/v1"
        const val JWKS_URL = "https://auth.openai.com/.well-known/jwks.json"
        const val WEB_BASE_URL = "https://chatgpt.com/backend-api"
        const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
        const val RESOURCE = "https://api.openai.com/v1"
        const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
        const val TRANSPORT_PUBLIC = "public-responses"
        const val TRANSPORT_WEB_SESSION = "chatgpt-web"
        const val AGENT_NAME = "GitHubble"
        const val CLIENT_VERSION = "1.0.2"
        private const val ISSUER = "https://auth.openai.com"
        private const val AUTH_CLAIM = "https://api.openai.com/auth"
        private const val USER_AGENT = "OmniChat-Android/1.0.2"
        private const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000L
        private const val REFRESH_WINDOW_MS = 5 * 60 * 1000L
        private const val DEFAULT_ACCESS_TTL_MS = 60 * 60 * 1000L
        private const val CLOCK_SKEW_SECONDS = 5L

        val REQUIRED_SCOPES = listOf(
            "openid", "profile", "email", "offline_access", "resource.invoke", DIRECT_SCOPE
        )

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(NetworkDnsPolicy.dns())
            .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

