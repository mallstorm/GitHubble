package app.githubble.mobile.network

/** Pure RFC 8628-style polling policy used by OAuth device flows. */
object DeviceFlowPollBackoff {
    fun nextPenaltySeconds(error: String, currentPenaltySeconds: Long): Long = when (error) {
        "slow_down" -> (currentPenaltySeconds + 5L).coerceAtMost(30L)
        else -> currentPenaltySeconds
    }
}

