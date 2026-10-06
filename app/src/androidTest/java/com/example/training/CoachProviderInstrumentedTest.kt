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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** All data is synthetic and isolated. No browser, account, or external server is contacted. */
@RunWith(AndroidJUnit4::class)
class CoachProviderInstrumentedTest {
    @Test fun oldSettingsRemainIntactAndOldPendingNeverCrossesToTheDirectAccount() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedCoachTestContext(target, File(target.cacheDir, "coach-provider-test-${UUID.randomUUID()}"))
        val store = CoachStore(context)
        val settings = CoachProviderSettings(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = OfflineCoachSession()
        val oldFiles = mapOf(
            "ai_coach_connection.json" to """{"url":"http://100.64.0.1:8765","token":"synthetic-old-token"}""",
            "ai_coach_preferences.json" to """{"model":"legacy-only-model","effort":"high","modelName":"以前のPCモデル"}""",
            "coach-provider.json" to """{"legacy":true,"preferences":{"model":"synthetic-model","effort":"low","modelName":"合成モデル"}}""",
        )
        try {
            withContext(Dispatchers.IO) {
                oldFiles.forEach { (name, raw) -> File(context.noBackupFilesDir, name).writeText(raw) }
                store.enqueue(CoachMessage("legacy-pending", "user", "保存済みの質問", 1L, "pending"),
                    JSONObject().put("requestId", "legacy-pending").put("message", "保存済みの質問"))
            }
            val controller = CoachController(store, scope, session, settings)
            withContext(Dispatchers.Main) { controller.load() }
            assertTrue(controller.ready)
            assertTrue(controller.pendingRequiresDraft)
            assertEquals(CoachPreferences("synthetic-model", "low", "合成モデル"), controller.preferences)
            assertEquals(0, session.modelRequests)
            assertTrue(session.requests.isEmpty())
            withContext(Dispatchers.Main) { controller.retry(emptyList()) }
            awaitIdle(controller)
            assertTrue(controller.error.orEmpty().contains("以前のPC接続"))
            assertNotNull(controller.conversation.pending)
            assertEquals(1, controller.conversation.messages.size)
            assertTrue(session.requests.isEmpty())

            withContext(Dispatchers.Main) { controller.returnPendingToDraft {} }
            awaitIdle(controller)
            assertEquals("保存済みの質問", controller.draft)
            assertNull(controller.conversation.pending)
            assertEquals("cancelled", controller.conversation.messages.single().status)
            assertTrue(session.requests.isEmpty())
            withContext(Dispatchers.IO) {
                oldFiles.forEach { (name, raw) -> assertEquals(raw, File(context.noBackupFilesDir, name).readText()) }
            }
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test fun pendingDirectQuestionRequiresItsOriginalAccountAndLegacyIsAlwaysRejected() {
        val question = JSONObject().put("_provider", "chatgpt").put("_account", "subject-a:client-a")
        checkCoachRequestOwner(question, "subject-a:client-a", true)
        rejects { checkCoachRequestOwner(question, "subject-b:client-a", true) }
        rejects { checkCoachRequestOwner(question, "subject-a:client-b", true) }
        rejects { checkCoachRequestOwner(question, "subject-a:client-a", false) }
        rejects { checkCoachRequestOwner(JSONObject(), "subject-a:client-a", true) }
        rejects { checkCoachRequestOwner(JSONObject().put("_provider", "relay"), "subject-a:client-a", true) }
        rejects { checkCoachRequestOwner(JSONObject().put("_provider", "chatgpt"), "", true) }
    }

    @Test fun cancellingSignInDoesNotFetchModelsOrSendRecords() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedCoachTestContext(target, File(target.cacheDir, "coach-cancel-test-${UUID.randomUUID()}"))
        val store = CoachStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = OfflineCoachSession(signedInAccount = null)
        val controller = CoachController(store, scope, session, CoachProviderSettings(context))
        try {
            withContext(Dispatchers.Main) {
                controller.load()
                controller.draft = "まだ送らない質問"
                controller.signIn {
                    assertTrue(controller.signingIn)
                    controller.refreshModels()
                    controller.send(emptyList(), "2026.10.7", emptyList())
                    controller.cancelSignIn()
                }
            }
            awaitIdle(controller)
            assertFalse(controller.signingIn)
            assertFalse(controller.modelsLoading)
            assertNull(controller.account)
            assertNull(controller.error)
            assertEquals("ログインをキャンセルしました。", controller.connectionStatus)
            assertEquals("まだ送らない質問", controller.draft)
            assertTrue(controller.conversation.messages.isEmpty())
            assertNull(controller.conversation.pending)
            assertTrue(session.requests.isEmpty())
            assertEquals(0, session.modelRequests)
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test fun signedOutAccountCannotSendEvenWhenOldConnectionSettingsExist() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedCoachTestContext(target, File(target.cacheDir, "coach-signed-out-test-${UUID.randomUUID()}"))
        File(context.noBackupFilesDir, "ai_coach_connection.json").writeText("""{"url":"https://old.tail123.ts.net","token":"synthetic-old-token"}""")
        val store = CoachStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = OfflineCoachSession(signedInAccount = null)
        val controller = CoachController(store, scope, session, CoachProviderSettings(context))
        try {
            withContext(Dispatchers.Main) {
                controller.load()
                controller.draft = "まだ送れない質問"
                controller.send(emptyList(), "2026.10.7", emptyList())
                controller.refreshModels()
            }
            assertFalse(controller.ready)
            assertTrue(controller.error.orEmpty().contains("ログイン"))
            assertTrue(controller.modelsError.orEmpty().contains("ログイン"))
            assertEquals("まだ送れない質問", controller.draft)
            assertTrue(controller.conversation.messages.isEmpty())
            assertTrue(session.requests.isEmpty())
            assertEquals(0, session.modelRequests)
        } finally {
            scope.cancel()
            store.close()
        }
    }

    private fun rejects(action: () -> Unit) {
        assertTrue("Changing provider or account must reject the pending question", runCatching(action).exceptionOrNull() is IllegalStateException)
    }

    private suspend fun awaitIdle(controller: CoachController) = withTimeout(5_000) {
        withContext(Dispatchers.Main) { /* Drain the preceding launch before inspecting state. */ }
        while (withContext(Dispatchers.Main) { controller.busy }) delay(20)
    }
}
