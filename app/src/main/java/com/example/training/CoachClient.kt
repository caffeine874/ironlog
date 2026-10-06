package com.example.training

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

internal data class CoachConnection(val url: String = "", val token: String = "") {
    val configured: Boolean get() = runCatching { checked() }.isSuccess

    fun checked(): CoachConnection {
        val normalized = url.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: throw IllegalArgumentException("接続先のURLを確認してください。")
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val loopback = host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
        val octets = host.split('.').map { it.toIntOrNull() }
        val tailscaleIp = octets.size == 4 && octets[0] == 100 &&
            (octets[1] ?: -1) in 64..127 && octets.all { it != null && it in 0..255 }
        val tailscaleName = host.endsWith(".ts.net") && host.length > 7
        require(uri.scheme == "https" || uri.scheme == "http") { "http または https のURLを指定してください。" }
        require(loopback || uri.scheme == "https") {
            "PCへの接続には https://…ts.net のURLが必要です。PCでセットアップを更新し、接続先を変更してください。保存済みの接続キーは保持しています。"
        }
        require(loopback || tailscaleIp || (tailscaleName && uri.scheme == "https")) {
            "Tailscaleの https://…ts.net の接続先を使ってください。"
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrBlank() || uri.rawPath == "/") && (uri.port == -1 || uri.port in 1..65535)) {
            "接続先はPCのURLとポート番号までを指定してください。"
        }
        val cleanedToken = token.trim()
        require(cleanedToken.length in 24..512 && cleanedToken.all { it.code in 33..126 }) {
            "PC側で発行された接続キーをそのまま入力してください。"
        }
        return CoachConnection(normalized, cleanedToken)
    }
}

internal data class CoachMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val status: String = "sent"
)

internal data class CoachConversation(
    val messages: List<CoachMessage> = emptyList(),
    val summary: String = "",
    val pending: String? = null
)

internal class CoachServerException(val code: String, message: String) : IOException(message)

/** A different database and file namespace: this class never opens the workout database. */
internal class CoachStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "ai_coach_chat.db", null, 1) {
    private val connectionFile = AtomicFile(File(context.noBackupFilesDir, "ai_coach_connection.json"))
    private val preferencesFile = AtomicFile(File(context.noBackupFilesDir, "ai_coach_preferences.json"))

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun readConnection(): CoachConnection {
        if (!connectionFile.baseFile.exists()) return CoachConnection()
        return runCatching {
            val json = connectionFile.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            // Preserve legacy values for migration and display; configured/checked prevent
            // sending credentials to an unsafe stored HTTP endpoint.
            CoachConnection(json.optString("url"), json.optString("token"))
        }.getOrDefault(CoachConnection())
    }

    fun writeConnection(connection: CoachConnection) {
        val checked = connection.checked()
        val stream = connectionFile.startWrite()
        try {
            stream.write(JSONObject().put("url", checked.url).put("token", checked.token).toString().toByteArray(Charsets.UTF_8))
            connectionFile.finishWrite(stream)
        } catch (error: Exception) {
            connectionFile.failWrite(stream)
            throw error
        }
    }

    fun readPreferences(): CoachPreferences {
        if (!preferencesFile.baseFile.exists()) return CoachPreferences()
        return runCatching {
            val json = preferencesFile.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            CoachPreferences(json.optString("model").takeIf { it.isNotBlank() },
                json.optString("effort").takeIf { it.isNotBlank() }, json.optString("modelName").takeIf { it.isNotBlank() })
        }.getOrDefault(CoachPreferences())
    }

    fun writePreferences(preferences: CoachPreferences) {
        val stream = preferencesFile.startWrite()
        try {
            stream.write(JSONObject().put("model", preferences.model.orEmpty()).put("effort", preferences.effort.orEmpty())
                .put("modelName", preferences.modelName.orEmpty()).toString().toByteArray(Charsets.UTF_8))
            preferencesFile.finishWrite(stream)
        } catch (error: Exception) {
            preferencesFile.failWrite(stream)
            throw error
        }
    }

    fun readConversation(): CoachConversation {
        val db = readableDatabase
        val messages = mutableListOf<CoachMessage>()
        db.rawQuery("SELECT id, role, content, created_at, status FROM messages ORDER BY created_at, rowid", null).use { cursor ->
            while (cursor.moveToNext()) {
                messages += CoachMessage(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getString(4))
            }
        }
        return CoachConversation(messages, metadata(db, "summary").orEmpty(), metadata(db, "pending"))
    }

    fun enqueue(message: CoachMessage, body: JSONObject): CoachConversation = transaction { db ->
        check(metadata(db, "pending") == null) { "前の質問がまだ送信待ちです。" }
        insertMessage(db, message)
        putMetadata(db, "pending", body.toString())
    }

    fun updatePending(body: JSONObject): CoachConversation = transaction { db ->
        putMetadata(db, "pending", body.toString())
    }

    fun complete(requestId: String, reply: String, summary: String): CoachConversation = transaction { db ->
        val pending = metadata(db, "pending")?.let(::JSONObject)
        check(pending?.optString("requestId") == requestId) { "送信中の会話が変更されました。" }
        db.update("messages", ContentValues().apply { put("status", "sent") }, "id = ?", arrayOf(requestId))
        insertMessage(db, CoachMessage("$requestId-answer", "assistant", reply, System.currentTimeMillis()))
        putMetadata(db, "summary", summary.take(6000))
        db.delete("metadata", "name = ?", arrayOf("pending"))
    }

    fun cancelPending(): CoachConversation = transaction { db ->
        val pending = metadata(db, "pending")?.let(::JSONObject)
        pending?.optString("requestId")?.let { id ->
            db.update("messages", ContentValues().apply { put("status", "cancelled") }, "id = ?", arrayOf(id))
        }
        db.delete("metadata", "name = ?", arrayOf("pending"))
    }

    fun resetConversation(): CoachConversation = transaction { db ->
        db.delete("messages", null, null)
        db.delete("metadata", null, null)
    }

    private fun transaction(change: (SQLiteDatabase) -> Unit): CoachConversation {
        val db = writableDatabase
        db.beginTransaction()
        try {
            change(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return readConversation()
    }

    private fun insertMessage(db: SQLiteDatabase, message: CoachMessage) {
        db.insertOrThrow("messages", null, ContentValues().apply {
            put("id", message.id)
            put("role", message.role)
            put("content", message.content)
            put("created_at", message.createdAt)
            put("status", message.status)
        })
    }

    private fun metadata(db: SQLiteDatabase, name: String): String? =
        db.rawQuery("SELECT value FROM metadata WHERE name = ?", arrayOf(name)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun putMetadata(db: SQLiteDatabase, name: String, value: String) {
        db.insertWithOnConflict("metadata", null, ContentValues().apply {
            put("name", name)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

internal object CoachClient {
    suspend fun health(connection: CoachConnection): JSONObject = request(connection, "/health", null)

    suspend fun models(connection: CoachConnection): CoachModelCatalog = CoachModelCatalog.parse(request(connection, "/v1/models", null))

    suspend fun chat(connection: CoachConnection, body: JSONObject): JSONObject = request(connection, "/v1/chat", body)

    private suspend fun request(connection: CoachConnection, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val checked = connection.checked()
        val http = URL(checked.url + path).openConnection() as HttpURLConnection
        try {
            http.instanceFollowRedirects = false
            http.connectTimeout = 15_000
            http.readTimeout = if (body == null) 45_000 else 660_000
            http.requestMethod = if (body == null) "GET" else "POST"
            http.setRequestProperty("Authorization", "Bearer ${checked.token}")
            http.setRequestProperty("Accept", "application/json")
            if (body != null) {
                http.doOutput = true
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val data = body.toString().toByteArray(Charsets.UTF_8)
                http.setFixedLengthStreamingMode(data.size)
                http.outputStream.use { it.write(data) }
            }
            val status = http.responseCode
            val stream = if (status in 200..299) http.inputStream else http.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count == -1) break
                    if (output.length + count > 1_048_576) throw IOException("PCからの応答が大きすぎます。")
                    output.append(buffer, 0, count)
                }
                output.toString()
            }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (status !in 200..299) {
                val serverMessage = json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("error")?.takeIf { it.isNotBlank() }
                throw CoachServerException(json?.optJSONObject("error")?.optString("code").orEmpty(), when (status) {
                    401, 403 -> "接続キーが一致しません。PC側の接続情報を確認してください。"
                    429 -> serverMessage ?: "Codexの利用枠または同時実行の上限に達しました。時間をおいて再試行してください。"
                    502, 503, 504 -> serverMessage ?: "PCのCodexが応答できません。PCの起動とChatGPTへのログインを確認してください。"
                    in 300..399 -> "接続先が転送を要求しました。PC側に表示された正しいURLを指定してください。"
                    else -> serverMessage ?: "PCからエラーが返りました（$status）。"
                }.take(500))
            }
            json ?: throw IOException("PCからの応答を読み取れませんでした。接続先を確認してください。")
        } catch (error: java.net.SocketTimeoutException) {
            throw IOException("応答を待つ時間を超えました。PCとTailscaleを確認して「再試行」を押してください。", error)
        } catch (error: java.net.ConnectException) {
            throw IOException("家のPCに接続できません。PC・中継プログラム・Tailscaleの起動を確認してください。", error)
        } catch (error: java.net.UnknownHostException) {
            throw IOException("接続先が見つかりません。スマホの通信とTailscaleの接続を確認してください。", error)
        } finally {
            http.disconnect()
        }
    }

    fun recentMessages(messages: List<CoachMessage>): JSONArray {
        var remaining = 18_000
        val recent = messages.asReversed().asSequence().filter { it.status == "sent" }.take(12).mapNotNull { message ->
            if (remaining <= 0) null else {
                val content = message.content.take(minOf(6000, remaining))
                remaining -= content.length
                JSONObject().put("role", message.role).put("content", content)
            }
        }.toList().asReversed()
        return JSONArray(recent)
    }
}
