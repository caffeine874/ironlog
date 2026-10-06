package com.example.training

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class CoachModelSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun accountChoicesResetUnsupportedEffortAndPersistAcrossControllerRestart() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedCoachTestContext(target, File(target.cacheDir, "coach-model-test-${UUID.randomUUID()}"))
        val store = CoachStore(context)
        val settings = CoachProviderSettings(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = OfflineCoachSession(catalog = CoachModelCatalog(listOf(
            CoachModelOption("test-a", "テストモデル A", listOf("high", "ultra"), "high"),
            CoachModelOption("test-b", "テストモデル B", listOf("low", "medium"), "low")), "test-a", "high"))
        val controller = CoachController(store, scope, session, settings)
        val saved = AtomicBoolean(false)
        try {
            withContext(Dispatchers.IO) { settings.setPreferences(CoachPreferences("test-a", "high", "テストモデル A")) }
            withContext(Dispatchers.Main) { controller.load() }
            compose.runOnUiThread {
                compose.activity.setContent { MaterialTheme { CoachModelDialog(controller) { saved.set(true) } } }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("テストモデル A") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("じっくり (high)").assertIsDisplayed()
            compose.onNodeWithText("テストモデル A").performClick()
            compose.onNodeWithText("テストモデル B").performClick()
            compose.onNodeWithText("自動").assertIsDisplayed().performClick()
            compose.onNodeWithText("軽め (low)").performClick()
            compose.waitForIdle()
            val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(target.cacheDir, "coach-models-test.png").outputStream().use { output ->
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            screenshot.recycle()
            compose.onNodeWithText("この設定を保存").performClick()
            compose.waitUntil(10_000) { saved.get() }
            assertEquals(CoachPreferences("test-b", "low", "テストモデル B"), withContext(Dispatchers.IO) { settings.preferences() })
            val restarted = CoachController(store, scope, session, CoachProviderSettings(context))
            withContext(Dispatchers.Main) { restarted.load() }
            assertEquals("test-b", restarted.preferences.model)
            assertEquals("low", restarted.preferences.effort)
            withContext(Dispatchers.IO) { store.resetConversation() }
            assertEquals("test-b", withContext(Dispatchers.IO) { settings.preferences().model })
            val automaticRequest = CoachPreferences().applyTo(JSONObject())
            assertFalse(automaticRequest.has("model"))
            assertFalse(automaticRequest.has("effort"))
            assertTrue(controller.modelCatalog?.supports(CoachPreferences("test-b", "ultra")) == false)
            assertTrue(session.requests.isEmpty())
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test fun modelDialogExplainsLoginAndLoadsChoicesAfterSignInWithoutSendingRecords() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedCoachTestContext(target, File(target.cacheDir, "coach-model-login-${UUID.randomUUID()}"))
        val store = CoachStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = OfflineCoachSession(signedInAccount = null)
        val controller = CoachController(store, scope, session, CoachProviderSettings(context))
        try {
            withContext(Dispatchers.Main) { controller.load() }
            compose.runOnUiThread {
                compose.activity.setContent { MaterialTheme { CoachModelDialog(controller) {} } }
            }
            compose.onNodeWithText("Continue with ChatGPT").assertIsDisplayed()
            compose.onNodeWithText("モデル一覧は未取得").assertIsNotEnabled()
            compose.onNodeWithText("この設定を保存").assertIsNotEnabled()
            compose.onNodeWithText("自動（ChatGPTの既定）").assertDoesNotExist()
            assertEquals(0, session.modelRequests)
            assertTrue(session.requests.isEmpty())
            compose.runOnIdle {
                session.signedInAccount = OfflineCoachSession().account()
                controller.signIn { /* Synthetic sign-in does not open a browser. */ }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("自動（ChatGPTの既定）") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, session.modelRequests)
            assertTrue(session.requests.isEmpty())
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test fun failedCatalogCanBeRetriedWithoutLosingSavedPreferences() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedCoachTestContext(target, File(target.cacheDir, "coach-model-retry-${UUID.randomUUID()}"))
        val store = CoachStore(context)
        val settings = CoachProviderSettings(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = OfflineCoachSession().apply { modelError = IOException("合成テストの一覧取得エラー") }
        val selected = CoachPreferences("synthetic-model", "high", "合成モデル")
        val controller = CoachController(store, scope, session, settings)
        try {
            withContext(Dispatchers.IO) { settings.setPreferences(selected) }
            withContext(Dispatchers.Main) { controller.load() }
            compose.runOnUiThread {
                compose.activity.setContent { MaterialTheme { CoachModelDialog(controller) {} } }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("合成テストの一覧取得エラー")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("モデル一覧は未取得").assertIsNotEnabled()
            compose.onNodeWithText("この設定を保存").assertIsNotEnabled()
            assertEquals(selected, withContext(Dispatchers.IO) { settings.preferences() })
            compose.runOnIdle { session.modelError = null }
            compose.onNodeWithText("一覧を更新").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("合成モデル") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(2, session.modelRequests)
            assertEquals(selected, controller.preferences)
            assertTrue(session.requests.isEmpty())
        } finally {
            scope.cancel()
            store.close()
        }
    }
}
