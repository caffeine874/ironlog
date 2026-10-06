package com.example.training

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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

internal fun recentCoachMessages(messages: List<CoachMessage>): JSONArray {
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
