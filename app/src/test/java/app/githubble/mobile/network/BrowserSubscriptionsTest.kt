package app.githubble.mobile.network
import org.junit.Test
import org.junit.Assert.*
class BrowserSubscriptionsTest {
    @Test fun claudeRequiresTerminalReasonAndExtractsTextBlocks() {
        assertEquals("Hello",BrowserSubscriptions.parse(BrowserSubscriptions.CLAUDE,"""{"stop_reason":"end_turn","content":[{"type":"text","text":"Hello"}]}"""))
        assertTrue(runCatching { BrowserSubscriptions.parse(BrowserSubscriptions.CLAUDE,"""{"content":[{"type":"text","text":"partial"}]}""") }.isFailure)
    }
}
