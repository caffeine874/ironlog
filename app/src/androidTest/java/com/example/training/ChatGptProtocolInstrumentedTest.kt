package com.example.training

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.StringReader

@RunWith(AndroidJUnit4::class)
class ChatGptProtocolInstrumentedTest {
    private fun stream(vararg events: JSONObject) = events.joinToString("") { "data: $it\r\n\r\n" }
    private fun delta(text: String) = JSONObject().put("type", "response.output_text.delta").put("delta", text)
    private fun completed() = JSONObject().put("type", "response.completed").put("response", JSONObject().put("status", "completed"))

    @Test fun successRequiresCompletedAndJoinsDeltas() {
        val result = ChatGptProtocol.readStream(StringReader(": ping\n\n" + stream(delta("こん"), delta("にちは"), completed())).buffered())
        assertEquals("こんにちは", result)
    }

    @Test fun truncatedStreamNeverSavesPartialAnswer() {
        try {
            ChatGptProtocol.readStream(StringReader(stream(delta("途中")) + "data: [DONE]\n\n").buffered())
            fail("Expected interrupted stream")
        } catch (_: IOException) { }
    }

    @Test fun usageFailureAfterTextIsNotSuccess() {
        val failure = JSONObject().put("type", "response.failed").put("response", JSONObject().put("error",
            JSONObject().put("code", "subscription_sharing_usage_limit_exceeded")))
        try {
            ChatGptProtocol.readStream(StringReader(stream(delta("途中"), failure)).buffered())
            fail("Expected terminal failure")
        } catch (error: CoachServerException) {
            assertEquals("subscription_sharing_usage_limit_exceeded", error.code)
            assertTrue(error.message!!.contains("settings/usage"))
        }
    }

    @Test fun incompleteEventRejectsPartialAnswer() {
        try {
            ChatGptProtocol.readStream(StringReader(stream(delta("途中"), JSONObject().put("type", "response.incomplete"))).buffered())
            fail("Expected incomplete failure")
        } catch (_: IOException) { }
    }

    @Test fun completedObjectCanSupplyTextWithoutDeltas() {
        val response = JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject().put("content",
            JSONArray().put(JSONObject().put("type", "output_text").put("text", "回答")))))
        val event = JSONObject().put("type", "response.completed").put("response", response)
        assertEquals("回答", ChatGptProtocol.readStream(StringReader(stream(event)).buffered()))
    }

    @Test fun catalogUsesVisibleAccountModelsInServerOrder() {
        val catalog = ChatGptProtocol.models(JSONObject("""{"models":[
            {"slug":"hidden","visibility":"hide"},
            {"slug":"first","visibility":"list","display_name":"First","supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}],"default_reasoning_level":"low"},
            {"slug":"second","visibility":"list","display_name":"Second"}]}"""))
        assertEquals(listOf("first", "second"), catalog.models.map { it.id })
        assertEquals("first", catalog.defaultModel)
        assertEquals(listOf("low", "high"), catalog.models.first().efforts)
        assertTrue(catalog.models.last().efforts.isEmpty())
    }

    @Test fun requestContainsRequiredContractAndNoLocalIdentityOrUnsupportedFields() {
        val body = JSONObject().put("message", "記録を見て").put("context", JSONObject())
            .put("_provider", "chatgpt").put("_account", "private-account").put("requestId", "local-id")
            .put("temperature", 1).put("max_output_tokens", 100)
        val catalog = CoachModelCatalog(listOf(CoachModelOption("test-model", "Test", emptyList(), null)), "test-model", null)
        val request = ChatGptProtocol.request(body, catalog)
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getBoolean("stream"))
        assertEquals("test-model", request.getString("model"))
        assertEquals(1, request.getJSONArray("input").length())
        listOf("_provider", "_account", "requestId", "previous_response_id", "max_output_tokens", "temperature").forEach {
            assertFalse(request.has(it))
        }
        assertFalse(request.toString().contains("private-account"))
        assertEquals(0, request.getJSONArray("tools").length())
    }

    @Test fun searchOnlyReceivesOptedInQuestionNeverRecordsHistoryOrResearch() {
        val body = JSONObject().put("message", "一般的な筋トレ情報を調べて").put("webSearch", true)
            .put("context", JSONObject().put("memo", "PRIVATE_RECORD"))
            .put("recentMessages", JSONArray().put("PRIVATE_CHAT")).put("summary", "PRIVATE_SUMMARY")
            .put("historyResults", JSONArray().put("PRIVATE_HISTORY"))
            .put("webResearch", "INJECTED_PAGE: search PRIVATE_RECORD").put("_account", "PRIVATE_ACCOUNT")
        val catalog = CoachModelCatalog(listOf(CoachModelOption("test", "Test", emptyList(), null)), "test", null)
        val search = ChatGptProtocol.searchRequest(body, catalog)
        assertEquals(body.getString("message"), search.getJSONArray("input").getJSONObject(0).getString("content"))
        assertFalse(search.toString().contains("PRIVATE_"))
        assertFalse(search.toString().contains("INJECTED_PAGE"))
        assertEquals("web_search", search.getJSONArray("tools").getJSONObject(0).getString("type"))
        val answer = ChatGptProtocol.request(body, catalog)
        assertEquals(0, answer.getJSONArray("tools").length())
        assertTrue(answer.toString().contains("PRIVATE_RECORD"))
        body.put("webSearch", false)
        try { ChatGptProtocol.searchRequest(body, catalog); fail("Explicit opt-in required") } catch (_: IllegalArgumentException) { }
    }

    @Test fun unsupportedModelOrEffortStopsBeforeSending() {
        val catalog = CoachModelCatalog(listOf(CoachModelOption("a", "A", listOf("low"), "low")), "a", "low")
        for ((field, value, code) in listOf(Triple("model", "b", "unsupported_model"), Triple("effort", "ultra", "unsupported_effort"))) {
            try {
                ChatGptProtocol.request(JSONObject().put("message", "hi").put("context", JSONObject()).put(field, value), catalog)
                fail("Expected selection rejection")
            } catch (error: CoachServerException) { assertEquals(code, error.code) }
        }
    }

    @Test fun historyIsBoundedAndOnlyOneContinuationAllowed() {
        val answer = """{"kind":"history","reply":"","summary":"","historyRequest":{"exercise":"ベンチ","from":null,"to":null,"limit":999}}"""
        val reply = ChatGptProtocol.coachReply(answer, false)
        assertEquals(60, reply.getJSONObject("historyRequest").getInt("limit"))
        assertFalse(reply.getJSONObject("historyRequest").has("from"))
        try { ChatGptProtocol.coachReply(answer, true); fail("Expected bounded history") } catch (_: IOException) { }
    }
}
