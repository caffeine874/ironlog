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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses a private test database and a synthetic loopback relay; no account or workout database is touched. */
@RunWith(AndroidJUnit4::class)
class CoachChatInstrumentedTest {
    @Test fun requestFetchesOldHistoryAndPersistsTheFinalReplyWithoutDuplicatingTheQuestion() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedChatContext(target, File(target.cacheDir, "coach-http-test-${UUID.randomUUID()}"))
        val store = CoachStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = CoachController(store, scope)
        val token = "synthetic-connection-token-1234567890"
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 20_000 }
        val executor = Executors.newSingleThreadExecutor()
        val requests = mutableListOf<JSONObject>()
        val worker = executor.submit {
            repeat(3) { index ->
                server.accept().use { socket ->
                    socket.soTimeout = 15_000
                    val input = socket.getInputStream()
                    val headerBytes = ByteArrayOutputStream()
                    while (!headerBytes.toString("US-ASCII").endsWith("\r\n\r\n")) {
                        val next = input.read()
                        check(next >= 0 && headerBytes.size() < 16_384) { "Incomplete request headers" }
                        headerBytes.write(next)
                    }
                    val headers = headerBytes.toString("US-ASCII").split("\r\n")
                    check(headers.first() == "POST /v1/chat HTTP/1.1")
                    check(headers.any { it.equals("Authorization: Bearer $token", ignoreCase = true) })
                    val length = headers.first { it.startsWith("Content-Length:", ignoreCase = true) }
                        .substringAfter(':').trim().toInt()
                    check(length in 1..200_000)
                    val bytes = ByteArray(length)
                    var position = 0
                    while (position < bytes.size) {
                        val count = input.read(bytes, position, bytes.size - position)
                        check(count > 0) { "Incomplete JSON body" }
                        position += count
                    }
                    requests += JSONObject(String(bytes, Charsets.UTF_8))
                    val response = if (index == 0) {
                        JSONObject().put("historyRequest", JSONObject().put("exercise", "ベンチ")
                            .put("from", "2024-01-01").put("to", "2024-01-31").put("limit", 20))
                            .put("continuation", "synthetic-continuation")
                    } else if (index == 1) {
                        JSONObject().put("error", JSONObject().put("code", "codex_unavailable").put("message", "テスト用の一時エラー"))
                    } else {
                        JSONObject().put("reply", "テスト回答：2024年1月の記録ボリュームは1,800 kgです。")
                            .put("summary", "ユーザーは2024年1月のベンチ記録を確認した。")
                    }
                    val payload = response.toString().toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        val status = if (index == 1) "503 Service Unavailable" else "200 OK"
                        output.write("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(payload)
                        output.flush()
                    }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) {
                store.writeConnection(CoachConnection("http://127.0.0.1:${server.localPort}", token))
                assertEquals(CoachPreferences(), store.readPreferences())
                store.writePreferences(CoachPreferences("synthetic-model", "high", "合成モデル"))
            }
            withContext(Dispatchers.Main) { controller.load() }
            assertTrue(controller.loaded)
            assertEquals("synthetic-model", controller.preferences.model)
            assertEquals("high", controller.preferences.effort)
            val records = listOf(WorkoutSet(1, "2024.1.15", "ベンチプレス", "胸", 60.0, 10, 3, "合成テスト記録"))
            withContext(Dispatchers.Main) {
                controller.draft = "2024年1月のベンチの記録を見て"
                controller.send(records, "2024.1.15", listOf("ベンチプレス"))
            }
            withTimeout(25_000) {
                while (!withContext(Dispatchers.Main) { controller.error != null && !controller.sending }) delay(50)
            }
            assertTrue(controller.conversation.pending != null)
            withContext(Dispatchers.IO) { store.writePreferences(CoachPreferences("different-model", "low", "別のモデル")) }
            withContext(Dispatchers.Main) {
                controller.load()
                assertEquals("different-model", controller.preferences.model)
                controller.retry(records)
            }
            withTimeout(25_000) {
                while (!withContext(Dispatchers.Main) { controller.conversation.messages.size == 2 || controller.error != null }) delay(50)
            }
            worker.get(1, TimeUnit.SECONDS)
            assertNull(controller.error)
            assertFalse(controller.sending)
            assertEquals(3, requests.size)
            assertEquals(requests[0].getString("requestId"), requests[1].getString("requestId"))
            assertEquals(requests[0].getString("requestId"), requests[2].getString("requestId"))
            requests.forEach { request ->
                assertEquals("synthetic-model", request.getString("model"))
                assertEquals("high", request.getString("effort"))
                assertFalse("Local provider metadata must not be sent to the relay", request.has("_provider"))
                assertFalse("Local account metadata must not be sent to the relay", request.has("_account"))
            }
            assertFalse(requests[0].has("historyResults"))
            assertEquals("synthetic-continuation", requests[1].getString("continuation"))
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
            server.close()
            executor.shutdownNow()
            store.close()
        }
    }

    private class IsolatedChatContext(base: Context, private val directory: File) : ContextWrapper(base) {
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
