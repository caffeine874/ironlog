package com.example.training

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class CoachModelSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun liveChoicesResetUnsupportedEffortAndPersistAcrossControllerRestart() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = ModelTestContext(target, File(target.cacheDir, "coach-model-test-${UUID.randomUUID()}"))
        val store = CoachStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = CoachController(store, scope)
        val saved = AtomicBoolean(false)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 20_000 }
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 15_000
                val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                check(reader.readLine() == "GET /v1/models HTTP/1.1")
                val headers = mutableListOf<String>()
                while (true) {
                    val line = reader.readLine() ?: error("Incomplete headers")
                    if (line.isEmpty()) break
                    headers += line
                }
                check(headers.any { it == "Authorization: Bearer synthetic-model-token-1234567890" })
                val catalog = JSONObject().put("models", JSONArray()
                    .put(JSONObject().put("id", "test-a").put("displayName", "テストモデル A")
                        .put("supportedReasoningEfforts", JSONArray(listOf("high", "ultra"))).put("defaultReasoningEffort", "high").put("isDefault", true))
                    .put(JSONObject().put("id", "test-b").put("displayName", "テストモデル B")
                        .put("supportedReasoningEfforts", JSONArray(listOf("low", "medium"))).put("defaultReasoningEffort", "low").put("isDefault", false)))
                    .put("defaultModel", "test-a").put("defaultReasoningEffort", "high")
                val bytes = catalog.toString().toByteArray(Charsets.UTF_8)
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(bytes)
                output.flush()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                store.writeConnection(CoachConnection("http://127.0.0.1:${server.localPort}", "synthetic-model-token-1234567890"))
                store.writePreferences(CoachPreferences("test-a", "high", "テストモデル A"))
            }
            withContext(Dispatchers.Main) { controller.load() }
            compose.runOnUiThread {
                compose.activity.setContent { MaterialTheme { CoachModelDialog(controller) { saved.set(true) } } }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasText("テストモデル A") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            worker.get(1, TimeUnit.SECONDS)
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
            assertEquals(CoachPreferences("test-b", "low", "テストモデル B"), withContext(Dispatchers.IO) { store.readPreferences() })
            val restarted = CoachController(store, scope)
            withContext(Dispatchers.Main) { restarted.load() }
            assertEquals("test-b", restarted.preferences.model)
            assertEquals("low", restarted.preferences.effort)
            withContext(Dispatchers.IO) { store.resetConversation() }
            assertEquals("test-b", withContext(Dispatchers.IO) { store.readPreferences().model })
            val automaticRequest = CoachPreferences().applyTo(JSONObject())
            assertFalse(automaticRequest.has("model"))
            assertFalse(automaticRequest.has("effort"))
            assertTrue(controller.modelCatalog?.supports(CoachPreferences("test-b", "ultra")) == false)
        } finally {
            scope.cancel()
            server.close()
            executor.shutdownNow()
            store.close()
        }
    }

    private class ModelTestContext(base: Context, private val directory: File) : ContextWrapper(base) {
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
