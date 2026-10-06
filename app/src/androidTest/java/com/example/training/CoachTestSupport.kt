package com.example.training

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File

/** Every controller test uses its own database, synthetic account, and in-memory transport. */
internal class IsolatedCoachTestContext(base: Context, private val directory: File) : ContextWrapper(base) {
    init { check(directory.mkdirs()) }
    override fun getApplicationContext(): Context = this
    override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(directory, name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
}

internal class OfflineCoachSession(
    var signedInAccount: ChatGptAccount? = ChatGptAccount("synthetic@example.test", "synthetic-subject", "synthetic-client", "synthetic-host", true),
    var catalog: CoachModelCatalog = CoachModelCatalog(
        listOf(CoachModelOption("synthetic-model", "合成モデル", listOf("high", "low"), "high")), "synthetic-model", "high"),
) : CoachChatGptSession {
    var modelRequests = 0
    val requests = mutableListOf<JSONObject>()
    var modelError: Exception? = null
    var reply: (JSONObject) -> JSONObject = { JSONObject().put("reply", "合成テスト回答") }
    private var cancelled = false

    override fun account(): ChatGptAccount? = signedInAccount
    override suspend fun signIn(openBrowser: (String) -> Unit): ChatGptAccount {
        cancelled = false
        openBrowser("https://example.test/synthetic-sign-in")
        if (cancelled) throw ChatGptAuthException("cancelled", "合成テストのキャンセル")
        return requireNotNull(signedInAccount)
    }
    override fun cancelSignIn() { cancelled = true }
    override suspend fun signOutAndRevoke(): Boolean {
        signedInAccount = null
        return true
    }
    override suspend fun models(): CoachModelCatalog {
        modelRequests++
        modelError?.let { throw it }
        return catalog
    }
    override suspend fun chat(body: JSONObject): JSONObject {
        requests += JSONObject(body.toString())
        return reply(body)
    }
}
