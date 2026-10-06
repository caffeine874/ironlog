package com.example.training

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Offline direct-account transport: no account, browser, server, or workout database is touched. */
@RunWith(AndroidJUnit4::class)
class CoachChatInstrumentedTest {
    @Test fun requestFetchesOldHistoryAndPersistsTheFinalReplyWithoutDuplicatingTheQuestion() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedCoachTestContext(target, File(target.cacheDir, "coach-chat-test-${UUID.randomUUID()}"))
        val store = CoachStore(context)
        val settings = CoachProviderSettings(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = OfflineCoachSession()
        val controller = CoachController(store, scope, session, settings)
        session.reply = {
            when (session.requests.size) {
                1 -> JSONObject().put("historyRequest", JSONObject().put("exercise", "ベンチ")
                    .put("from", "2024-01-01").put("to", "2024-01-31").put("limit", 20))
                    .put("continuation", "synthetic-continuation").put("webResearch", "合成検索結果")
                2 -> throw CoachServerException("temporarily_unavailable", "テスト用の一時エラー")
                else -> JSONObject().put("reply", "テスト回答：2024年1月の記録ボリュームは1,800 kgです。")
                    .put("summary", "ユーザーは2024年1月のベンチ記録を確認した。")
            }
        }
        try {
            withContext(Dispatchers.IO) {
                settings.setPreferences(CoachPreferences("synthetic-model", "high", "合成モデル"))
            }
            withContext(Dispatchers.Main) { controller.load() }
            assertTrue(controller.loaded)
            assertTrue(controller.ready)
            assertEquals("synthetic-model", controller.preferences.model)
            val records = listOf(WorkoutSet(1, "2024.1.15", "ベンチプレス", "胸", 60.0, 10, 3, "合成テスト記録"))
            withContext(Dispatchers.Main) {
                controller.draft = "2024年1月のベンチの記録を見て"
                controller.updateWebSearch(true)
                controller.send(records, "2024.1.15", listOf("ベンチプレス"))
            }
            awaitIdle(controller)
            assertTrue(controller.error.orEmpty().contains("一時エラー"))
            assertTrue(controller.conversation.pending != null)
            withContext(Dispatchers.IO) { settings.setPreferences(CoachPreferences("different-model", "low", "別のモデル")) }
            withContext(Dispatchers.Main) {
                controller.load()
                assertEquals("different-model", controller.preferences.model)
                controller.retry(records)
            }
            awaitIdle(controller)
            assertNull(controller.error)
            assertFalse(controller.sending)
            val requests = session.requests
            assertEquals(3, requests.size)
            assertEquals(1, session.modelRequests)
            requests.forEach { request ->
                assertEquals(requests[0].getString("requestId"), request.getString("requestId"))
                assertEquals("synthetic-model", request.getString("model"))
                assertEquals("high", request.getString("effort"))
                assertTrue(request.getBoolean("webSearch"))
                assertFalse("Local provider metadata must not be sent to OpenAI", request.has("_provider"))
                assertFalse("Local account metadata must not be sent to OpenAI", request.has("_account"))
            }
            assertFalse(requests[0].has("historyResults"))
            assertEquals("synthetic-continuation", requests[1].getString("continuation"))
            assertEquals("合成検索結果", requests[2].getString("webResearch"))
            val history = requests[1].getJSONArray("historyResults").getJSONObject(0)
            assertEquals(1, history.getInt("matchingRecordCount"))
            assertEquals(1800.0, history.getJSONObject("totals").getDouble("volumeKgReps"), 0.001)
            val saved = withContext(Dispatchers.IO) { store.readConversation() }
            assertEquals(listOf("user", "assistant"), saved.messages.map { it.role })
            assertTrue(saved.messages.all { it.status == "sent" })
            assertNull(saved.pending)
            assertEquals("ユーザーは2024年1月のベンチ記録を確認した。", saved.summary)
            assertTrue(saved.messages.last().content.contains("1,800 kg"))
        } finally {
            scope.cancel()
            store.close()
        }
    }

    private suspend fun awaitIdle(controller: CoachController) = withTimeout(5_000) {
        withContext(Dispatchers.Main) { /* Drain the launch before inspecting state. */ }
        while (withContext(Dispatchers.Main) { controller.busy }) delay(20)
    }
}
