package app.githubble.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsRouteScorerTest {
    private fun profile(id: String, priority: Int) = DnsProfile(
        id = id,
        name = id,
        mode = NetworkDnsPolicy.MODE_DOH,
        endpoint = "https://$id.example/dns-query",
        capabilities = listOf(AiServiceResolver.GEMINI),
        priority = priority
    )

    @Test
    fun lowerLatencyCanBeatRegistryPriority() {
        val slowPriority = profile("priority", 10)
        val fast = profile("fast", 100)
        val now = 1_000_000L
        val stats = mapOf(
            slowPriority.id to DnsRouteStats(
                "wifi:a", AiServiceResolver.GEMINI, slowPriority.id,
                successes = 20, dnsEwmaMs = 180.0, tlsEwmaMs = 420.0,
                httpEwmaMs = 650.0, jitterEwmaMs = 80.0, lastCheckedAt = now
            ),
            fast.id to DnsRouteStats(
                "wifi:a", AiServiceResolver.GEMINI, fast.id,
                successes = 20, dnsEwmaMs = 28.0, tlsEwmaMs = 95.0,
                httpEwmaMs = 140.0, jitterEwmaMs = 10.0, lastCheckedAt = now
            )
        )
        val ranked = DnsRouteScorer.rank(listOf(slowPriority, fast), stats, now)
        assertEquals("fast", ranked.first().profile.id)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun hysteresisKeepsCurrentRouteForSmallDifference() {
        val a = profile("a", 100)
        val b = profile("b", 100)
        val ranked = listOf(
            DnsCandidateScore(a, null, 90.0),
            DnsCandidateScore(b, null, 85.0)
        )
        val lease = DnsRouteLease("wifi:a", AiServiceResolver.GEMINI, "b", 50_000L)
        assertEquals(
            "b",
            DnsRouteScorer.chooseWithHysteresis(ranked, lease, now = 10_000L)?.profile?.id
        )
    }

    @Test
    fun cooldownRemovesFailedResolverFromSelection() {
        val failed = profile("failed", 1)
        val healthy = profile("healthy", 500)
        val now = 10_000L
        val failedStats = DnsRouteStats(
            "wifi:a", AiServiceResolver.GEMINI, failed.id,
            successes = 10, failures = 1, consecutiveFailures = 1,
            dnsEwmaMs = 10.0, tlsEwmaMs = 30.0, httpEwmaMs = 40.0,
            cooldownUntil = now + 60_000L
        )
        val ranked = DnsRouteScorer.rank(
            listOf(failed, healthy),
            mapOf(failed.id to failedStats),
            now
        )
        assertEquals("healthy", DnsRouteScorer.chooseWithHysteresis(ranked, null, now)?.profile?.id)
    }

    @Test
    fun hostnamesResolveToStableAiCapabilities() {
        assertEquals(AiServiceResolver.GEMINI, AiServiceResolver.serviceForHost("daily-cloudcode-pa.googleapis.com"))
        assertEquals(AiServiceResolver.OPENAI, AiServiceResolver.serviceForHost("api.openai.com"))
        assertEquals(AiServiceResolver.GITHUB_COPILOT, AiServiceResolver.serviceForHost("api.githubcopilot.com"))
        assertEquals(AiServiceResolver.CLAUDE, AiServiceResolver.serviceForHost("api.anthropic.com"))
        assertEquals(AiServiceResolver.GROK, AiServiceResolver.serviceForHost("cli-chat-proxy.grok.com"))
    }
}

