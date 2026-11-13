package app.githubble.mobile.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiAgentStreamTest {
    @Test fun fragmentedChatCallsRetainIndexesIdsNamesAndArguments() {
        val parser = OpenAiAgentStream(ApiGateway.CHAT)
        parser.accept("""{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"read_","arguments":"{\"pa"}},{"index":0,"id":"a","function":{"name":"list_files","arguments":"{"}}]}}]}""")
        parser.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}},{"index":1,"function":{"name":"file","arguments":"th\":\"README.md\"}"}}]},"finish_reason":"tool_calls"}]}""")
        parser.accept("[DONE]")
        val turn = parser.result()
        assertEquals(listOf("a", "b"), turn.calls.map { it.id })
        assertEquals(listOf("list_files", "read_file"), turn.calls.map { it.name })
        assertEquals("README.md", turn.calls[1].arguments.getString("path"))
        assertEquals(2, turn.assistantMessage.getJSONArray("tool_calls").length())
    }

    @Test fun completedResponseArgumentsOverridePartialItemsAndPreserveNativeConversation() {
        val parser = OpenAiAgentStream(ApiGateway.RESPONSES)
        parser.accept("""{"type":"response.output_item.added","item":{"type":"function_call","id":"item","call_id":"call","name":"read_file","arguments":""}}""")
        parser.accept("""{"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call","id":"item","call_id":"call","name":"read_file","arguments":"{\"path\":\"README.md\"}"}],"usage":{"input_tokens":7,"output_tokens":4}}}""")
        val turn = parser.result()
        assertEquals("README.md", turn.calls.single().arguments.getString("path"))
        assertEquals(7L, turn.usage.inputTokens);assertEquals("response_items", turn.assistantMessage.getString("type"))
    }

    @Test fun plainDoneWithoutCompletedResponseIsNotSuccess() {
        val parser = OpenAiAgentStream(ApiGateway.RESPONSES);parser.accept("[DONE]")
        rejected { parser.result() }
    }

    @Test fun truncatedAndInvalidToolArgumentsAreNeverConvertedToEmptyObject() {
        for (args in listOf("", "{", "[1]")) {
            val parser = OpenAiAgentStream(ApiGateway.CHAT)
            val fn = JSONObject().put("name", "read_file").put("arguments", args)
            parser.accept(JSONObject("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"id"}]},"finish_reason":"tool_calls"}]}""").also {
                it.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0).put("function", fn)
            }.toString())
            parser.accept("[DONE]");rejected { parser.result() }
        }
    }

    @Test fun tokenLimitOrMissingTerminalEventCannotCompleteAToolTurn() {
        rejected { OpenAiAgentStream(ApiGateway.CHAT).accept("""{"choices":[{"delta":{},"finish_reason":"length"}]}""") }
        val parser = OpenAiAgentStream(ApiGateway.CHAT);parser.accept("""{"choices":[{"delta":{"content":"partial"}}]}""")
        rejected { parser.result() }
    }

    @Test fun serverErrorInsideAStreamIsNotTreatedAsUnsupportedProtocol() {
        try { OpenAiAgentStream(ApiGateway.RESPONSES).accept("""{"type":"error","error":{"message":"quota"}}""");fail("Stream error ignored") }
        catch (_: ApiFailure) {}
    }

    @Test fun switchingBetweenFullEndpointUrlsKeepsTheSameOriginAndApiPrefix() {
        assertEquals("https://fixture.test/v1/chat/completions", ApiGateway.endpoint("https://fixture.test/v1/responses", ApiGateway.CHAT, "chat", "completions").toString())
        assertEquals("https://fixture.test/v1/responses", ApiGateway.endpoint("https://fixture.test/v1/chat/completions/", ApiGateway.RESPONSES, "responses").toString())
    }

    private fun rejected(block: () -> Unit) {
        try { block();fail("Invalid stream accepted") } catch (_: AgentCapabilityUnconfirmed) {}
    }
}
