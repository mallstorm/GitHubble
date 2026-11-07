package app.githubble.mobile.network

import kotlin.math.exp

data class DnsCandidateScore(
    val profile: DnsProfile,
    val stats: DnsRouteStats?,
    val score: Double
)

/**
 * Quality score: availability is strongest, then DNS/TLS latency, HTTP reachability
 * and jitter. Registry priority is intentionally only a small administrative bias.
 */
object DnsRouteScorer {
    const val LEASE_MS = 5 * 60_000L
    const val HYSTERESIS_POINTS = 8.0

    fun score(profile: DnsProfile, stats: DnsRouteStats?, now: Long = System.currentTimeMillis()): Double {
        if (stats != null && stats.cooldownUntil > now) return -1000.0

        val adminBias = 3.0 * (1.0 - (profile.priority.coerceIn(0, 1000) / 1000.0))
        if (stats == null || stats.successes + stats.failures == 0) {
            return 52.0 + adminBias
        }

        val attempts = (stats.successes + stats.failures).coerceAtLeast(1)
        val availability = stats.successes.toDouble() / attempts
        val availabilityScore = 35.0 * availability
        val dnsScore = 25.0 * latencyQuality(stats.dnsEwmaMs, 250.0)
        val tlsScore = 20.0 * latencyQuality(stats.tlsEwmaMs, 700.0)
        val httpScore = 10.0 * latencyQuality(stats.httpEwmaMs, 1200.0)
        val stabilityScore = 10.0 * latencyQuality(stats.jitterEwmaMs, 220.0)
        val failurePenalty = (stats.consecutiveFailures * 8.0).coerceAtMost(24.0)

        return (availabilityScore + dnsScore + tlsScore + httpScore + stabilityScore +
            adminBias - failurePenalty).coerceIn(0.0, 100.0)
    }

    fun rank(
        profiles: List<DnsProfile>,
        statsByProfile: Map<String, DnsRouteStats>,
        now: Long = System.currentTimeMillis()
    ): List<DnsCandidateScore> =
        profiles.map { DnsCandidateScore(it, statsByProfile[it.id], score(it, statsByProfile[it.id], now)) }
            .sortedWith(compareByDescending<DnsCandidateScore> { it.score }
                .thenBy { it.profile.priority }
                .thenBy { it.profile.id })

    fun chooseWithHysteresis(
        ranked: List<DnsCandidateScore>,
        lease: DnsRouteLease?,
        now: Long = System.currentTimeMillis()
    ): DnsCandidateScore? {
        val best = ranked.firstOrNull { it.score > -999.0 } ?: return null
        if (lease == null || lease.until <= now) return best
        val current = ranked.firstOrNull { it.profile.id == lease.profileId && it.score > -999.0 }
            ?: return best
        return if (best.score - current.score < HYSTERESIS_POINTS) current else best
    }

    private fun latencyQuality(value: Double?, scale: Double): Double {
        if (value == null) return 0.55
        return exp(-value.coerceAtLeast(0.0) / scale).coerceIn(0.0, 1.0)
    }
}

