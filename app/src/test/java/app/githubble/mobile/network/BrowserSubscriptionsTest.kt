package app.githubble.mobile.network
import org.junit.Test
import org.junit.Assert.*
class BrowserSubscriptionsTest {
    @Test fun claudeRequiresTerminalReasonAndExtractsTextBlocks() {
        assertEquals("Hello",BrowserSubscriptions.parse(BrowserSubscriptions.CLAUDE,"""{"stop_reason":"end_turn","content":[{"type":"text","text":"Hello"}]}"""))
        assertTrue(runCatching { BrowserSubscriptions.parse(BrowserSubscriptions.CLAUDE,"""{"content":[{"type":"text","text":"partial"}]}""") }.isFailure)
    }
    @Test fun geminiSkipsThoughtBlocksAndRejectsTruncation() {
        val frame="""data: {"response":{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"internal"},{"text":"answer"}]}}]}}

"""
        assertEquals("answer",BrowserSubscriptions.parse(BrowserSubscriptions.GEMINI,frame))
        assertTrue(runCatching { BrowserSubscriptions.parse(BrowserSubscriptions.GEMINI,frame.replace("\"finishReason\":\"STOP\",","")) }.isFailure)
    }
}
