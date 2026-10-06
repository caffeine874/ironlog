package com.example.training

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
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
    @Test fun directIsDefaultAndOldPendingCannotBeSentThroughTheNewProvider() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = ProviderTestContext(target, File(target.cacheDir, "coach-provider-test-${UUID.randomUUID()}"))
        val store = CoachStore(context)
        val settings = CoachProviderSettings(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val auth = ChatGptAuth(context)
        try {
            val legacyPreferences = CoachPreferences("legacy-only-model", "high", "以前のPCモデル")
            withContext(Dispatchers.IO) {
                store.writeConnection(CoachConnection("http://127.0.0.1:9", "synthetic-provider-token-1234567890"))
                store.writePreferences(legacyPreferences)
                store.enqueue(CoachMessage("legacy-pending", "user", "保存済みの質問", 1L, "pending"),
                    JSONObject().put("requestId", "legacy-pending").put("message", "保存済みの質問"))
            }
            val controller = CoachController(store, scope, auth, settings)
            withContext(Dispatchers.Main) { controller.load() }
            assertFalse(controller.useLegacy)
            assertFalse(controller.ready)
            assertEquals(CoachPreferences(), controller.preferences)
            assertEquals(legacyPreferences, withContext(Dispatchers.IO) { store.readPreferences() })
            withContext(Dispatchers.Main) { controller.retry(emptyList()) }
            awaitIdle(controller)
            assertTrue(controller.error.orEmpty().contains("別の接続方法"))
            assertNotNull(controller.conversation.pending)
            assertEquals(1, controller.conversation.messages.size)

            withContext(Dispatchers.Main) { controller.setLegacy(true) }
            awaitIdle(controller)
            assertTrue(controller.useLegacy)
            assertEquals(legacyPreferences, controller.preferences)
            assertTrue(withContext(Dispatchers.IO) { CoachProviderSettings(context).usesLegacy() })
            withContext(Dispatchers.Main) { controller.setLegacy(false) }
            awaitIdle(controller)
            assertEquals(CoachPreferences(), controller.preferences)
            assertFalse(withContext(Dispatchers.IO) { CoachProviderSettings(context).usesLegacy() })

            withContext(Dispatchers.Main) { controller.returnPendingToDraft {} }
            awaitIdle(controller)
            assertEquals("保存済みの質問", controller.draft)
            withContext(Dispatchers.Main) { controller.send(emptyList(), "2026.10.7", emptyList()) }
            assertEquals("保存済みの質問", controller.draft)
            assertTrue(controller.error.orEmpty().contains("ログイン"))
            assertFalse(controller.sending)
            assertEquals(legacyPreferences, withContext(Dispatchers.IO) { store.readPreferences() })
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test fun pendingDirectQuestionRequiresItsOriginalAccountAndProvider() {
        val question = JSONObject().put("_provider", "chatgpt").put("_account", "subject-a:client-a")
        checkCoachRequestOwner(question, false, "subject-a:client-a", true)
        checkCoachRequestOwner(JSONObject(), true, "", true) // Older pending relay questions remain retryable.
        rejects { checkCoachRequestOwner(question, true, "subject-a:client-a", true) }
        rejects { checkCoachRequestOwner(question, false, "subject-b:client-a", true) }
        rejects { checkCoachRequestOwner(question, false, "subject-a:client-b", true) }
        rejects { checkCoachRequestOwner(question, false, "subject-a:client-a", false) }
        rejects { checkCoachRequestOwner(JSONObject(), false, "subject-a:client-a", true) }
        rejects { checkCoachRequestOwner(JSONObject().put("_provider", "chatgpt"), false, "", true) }
    }

    @Test fun cancellingSignInDoesNotSendRecordsOrAllowProviderChangesDuringLogin() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = ProviderTestContext(target, File(target.cacheDir, "coach-cancel-test-${UUID.randomUUID()}"))
        val store = CoachStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = CoachController(store, scope, ChatGptAuth(context), CoachProviderSettings(context))
        try {
            withContext(Dispatchers.Main) {
                controller.load()
                controller.draft = "まだ送らない質問"
                controller.signIn { url ->
                    assertTrue(url.startsWith("https://auth.openai.com/"))
                    controller.setLegacy(true)
                    controller.refreshModels()
                    controller.send(emptyList(), "2026.10.7", emptyList())
                    controller.cancelSignIn()
                }
            }
            awaitIdle(controller)
            assertFalse(controller.useLegacy)
            assertFalse(controller.signingIn)
            assertFalse(controller.modelsLoading)
            assertNull(controller.account)
            assertNull(controller.error)
            assertEquals("ログインをキャンセルしました。", controller.connectionStatus)
            assertEquals("まだ送らない質問", controller.draft)
            assertTrue(controller.conversation.messages.isEmpty())
            assertNull(controller.conversation.pending)
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test fun providerPreferencesPersistSeparatelyFromTheMode() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = ProviderTestContext(target, File(target.cacheDir, "coach-provider-prefs-${UUID.randomUUID()}"))
        val settings = CoachProviderSettings(context)
        val selected = CoachPreferences("chatgpt-model", "low", "テストモデル")
        assertFalse(settings.usesLegacy())
        settings.setPreferences(selected)
        settings.setLegacy(true)
        assertEquals(selected, CoachProviderSettings(context).preferences())
        settings.setLegacy(false)
        assertEquals(selected, CoachProviderSettings(context).preferences())
    }

    private fun rejects(action: () -> Unit) {
        val problem = runCatching(action).exceptionOrNull()
        assertTrue("Changing provider or account must reject the pending question", problem is IllegalStateException)
    }

    private suspend fun awaitIdle(controller: CoachController) = withTimeout(5_000) {
        withContext(Dispatchers.Main) { /* Drain the preceding launch before inspecting state. */ }
        while (withContext(Dispatchers.Main) { controller.busy }) delay(20)
    }

    private class ProviderTestContext(base: Context, private val directory: File) : ContextWrapper(base) {
        init { check(directory.mkdirs()) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(directory, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
    }
}
