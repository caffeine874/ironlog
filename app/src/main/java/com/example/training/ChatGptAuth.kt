package com.example.training

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLException

internal data class ChatGptAccount(
    val email: String,
    val subject: String,
    val clientId: String,
    val hostId: String,
    val planUsageEnabled: Boolean = false
)

internal class ChatGptAuthException(val code: String, message: String) : IOException(message)

internal fun interface ChatGptAuthTransport {
    fun request(url: String, body: String?): JSONObject
}

/** Public-client OAuth. Tokens never leave this installation except for requests to OpenAI. */
internal class ChatGptAuth(
    context: Context,
    private val transport: ChatGptAuthTransport? = null,
    private val keyAlias: String = KEY_ALIAS
) {
    private val lock = Any()
    private val directory = context.applicationContext.noBackupFilesDir
    private val file = AtomicFile(File(directory, "chatgpt_accounts.enc"))
    private val hostFile = AtomicFile(File(directory, "chatgpt_host_id"))
    private val refreshMutex = Mutex()
    private val signInMutex = Mutex()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var records = emptyList<Record>()
    private var selectedId = ""
    private var pendingClientId = ""
    private var epoch = 0L
    private var listener: ServerSocket? = null
    private var storageFailure = false

    private data class Record(
        val account: ChatGptAccount,
        val accessToken: String = "",
        val refreshToken: String = "",
        val idToken: String = "",
        val scopes: Set<String> = emptySet(),
        val expiresAt: Long = 0L
    ) {
        fun withoutTokens() = copy(accessToken = "", refreshToken = "", idToken = "", scopes = emptySet(), expiresAt = 0L,
            account = account.copy(planUsageEnabled = false))
    }

    init {
        try {
            if (atomicExists(file)) {
                val json = JSONObject(String(decrypt(file.openRead().use { it.readBytes() }), Charsets.UTF_8))
                selectedId = json.optString("selected_id")
                pendingClientId = json.optString("pending_client_id")
                val profiles = json.optJSONArray("profiles") ?: JSONArray()
                records = (0 until profiles.length()).map { index ->
                    val p = profiles.getJSONObject(index)
                    val scopes = p.optString("scope").split(' ').filter { it.isNotBlank() }.toSet()
                    Record(
                        ChatGptAccount(p.getString("email"), p.getString("subject"), p.getString("client_id"),
                            p.getString("host_id"), DIRECT_SCOPE in scopes),
                        p.optString("access_token"), p.optString("refresh_token"), p.optString("id_token"), scopes,
                        p.optLong("expires_at")
                    )
                }
            }
        } catch (_: Exception) {
            // Keep an unreadable file intact; never silently replace existing registrations.
            storageFailure = true
        }
    }

    fun account(): ChatGptAccount? = synchronized(lock) {
        current()?.takeIf { it.idToken.isNotEmpty() }?.account
    }

    fun savedAccounts(): List<ChatGptAccount> = synchronized(lock) { records.map { it.account } }

    fun selectAccount(clientId: String) = synchronized(lock) {
        checkStorage()
        require(records.any { it.account.clientId == clientId }) { "保存されたアカウントが見つかりません。" }
        cancelLocked()
        save(records, clientId, pendingClientId)
        selectedId = clientId
    }

    suspend fun signIn(openBrowser: (String) -> Unit): ChatGptAccount = authorize(false, openBrowser) {}

    suspend fun signIn(openBrowser: (String) -> Unit, awaitForeground: suspend () -> Unit): ChatGptAccount =
        authorize(false, openBrowser, awaitForeground)

    suspend fun signInNew(openBrowser: (String) -> Unit): ChatGptAccount = authorize(true, openBrowser) {}

    suspend fun signInNew(openBrowser: (String) -> Unit, awaitForeground: suspend () -> Unit): ChatGptAccount =
        authorize(true, openBrowser, awaitForeground)

    private suspend fun authorize(
        newAccount: Boolean,
        openBrowser: (String) -> Unit,
        awaitForeground: suspend () -> Unit
    ): ChatGptAccount =
        signInMutex.withLock {
            withContext(Dispatchers.IO) {
                val server = ServerSocket().apply {
                    reuseAddress = false
                    bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                    soTimeout = 1000
                }
                val previous: Record?
                val requestEpoch: Long
                val clientId: String
                val hostId: String
                var issuedRefreshToken = ""
                var issuedClientId = ""
                var credentialsSaved = false
                try {
                    synchronized(lock) {
                        checkStorage()
                        cancelLocked()
                        listener = server
                        requestEpoch = epoch
                        previous = if (newAccount) null else current()
                        clientId = previous?.account?.clientId
                            ?: pendingClientId.takeIf { !newAccount && it.isNotBlank() } ?: DYNAMIC_CLIENT
                        hostId = loadHostId()
                    }
                    // Check public connectivity while our activity is visible, before
                    // asking the user to repeat browser consent on a broken connection.
                    checkAuthConnectivity(requestEpoch)
                    val state = randomValue()
                    val nonce = randomValue()
                    val verifier = randomValue(64)
                    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
                    val parameters = linkedMapOf(
                        "client_id" to clientId, "ext_agent_host_id" to hostId,
                        "response_type" to "code", "redirect_uri" to redirectUri,
                        "scope" to SCOPES, "resource" to RESOURCE, "state" to state, "nonce" to nonce,
                        "code_challenge_method" to "S256",
                        "code_challenge" to base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
                    )
                    if (clientId == DYNAMIC_CLIENT) parameters["agent_name_hint"] = "IronLog"
                    previous?.idToken?.takeIf { it.isNotBlank() }?.let { parameters["id_token_hint"] = it }
                    previous?.account?.email?.takeIf { it.isNotBlank() }?.let { parameters["login_hint"] = it }
                    // A second explicit click after a permissions-only sign-in requests consent.
                    if (previous?.idToken?.isNotEmpty() == true && !previous.account.planUsageEnabled) {
                        parameters["prompt"] = "consent"
                    }
                    withContext(Dispatchers.Main) {
                        currentCoroutineContext().ensureActive()
                        synchronized(lock) { checkEpoch(requestEpoch) }
                        openBrowser("$AUTHORIZE?${form(parameters)}")
                    }
                    val callback = waitForCallback(server, state, clientId)
                    synchronized(lock) {
                        checkEpoch(requestEpoch)
                        if (clientId == DYNAMIC_CLIENT) {
                            save(records, selectedId, callback.clientId)
                            pendingClientId = callback.clientId
                        }
                    }
                    // Some Android devices deny background network access while the
                    // system browser is in front. Resume the activity before exchanging
                    // a one-use code; never retry the code POST after an ambiguous failure.
                    val resumed = withTimeoutOrNull(120_000L) { awaitForeground(); true } == true
                    if (!resumed) throw ChatGptAuthException("return_to_app", "IronLogに戻って、もう一度ログインしてください。")
                    checkAuthConnectivity(requestEpoch)
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) { checkEpoch(requestEpoch) }
                    val tokens = postForm(TOKEN, mapOf(
                        "grant_type" to "authorization_code", "client_id" to callback.clientId,
                        "code" to callback.code, "code_verifier" to verifier,
                        "redirect_uri" to redirectUri, "resource" to RESOURCE
                    ))
                    issuedRefreshToken = tokens.optString("refresh_token")
                    issuedClientId = callback.clientId
                    val idToken = tokens.optString("id_token")
                    val claims = verifyIdentity(idToken, callback.clientId, nonce)
                    if (previous != null && previous.account.subject != claims.getString("sub")) {
                        throw ChatGptAuthException("identity_mismatch", "選択したアカウントと異なるため、接続を保存しませんでした。")
                    }
                    val scopes = tokenScopes(tokens)
                    val account = ChatGptAccount(claims.optString("email"), claims.getString("sub"),
                        callback.clientId, hostId, DIRECT_SCOPE in scopes)
                    val record = recordFromTokens(account, tokens, idToken, scopes)
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        checkEpoch(requestEpoch)
                        val updated = records.filterNot { it.account.clientId == account.clientId } + record
                        save(updated, account.clientId, "")
                        records = updated
                        selectedId = account.clientId
                        pendingClientId = ""
                        credentialsSaved = true
                    }
                    account
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ChatGptAuthException) {
                    throw error
                } catch (error: Exception) {
                    if (server.isClosed) throw ChatGptAuthException("cancelled", "ログインを中止しました。")
                    throw safeAuthFailure("LOGIN", error)
                } finally {
                    runCatching { server.close() }
                    synchronized(lock) { if (listener === server) listener = null }
                    // Also cover validation/network failures after code exchange, not
                    // only failures during the final save. Never adopt an unverified identity.
                    if (!credentialsSaved && issuedRefreshToken.isNotBlank()) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            revokeToken(issuedRefreshToken, issuedClientId, attempts = 1)
                        }
                    }
                }
            }
        }

    private suspend fun checkAuthConnectivity(requestEpoch: Long) {
        for (attempt in 0..2) {
            currentCoroutineContext().ensureActive()
            synchronized(lock) { checkEpoch(requestEpoch) }
            try {
                val metadata = getJson(DISCOVERY)
                if (metadata.optString("issuer") != ISSUER) throw invalidIdentity()
                return
            } catch (error: ChatGptAuthException) {
                if (error.code != "AUTH-DISCOVERY-DNS" || attempt == 2) throw error
                // Only this public GET may be retried. No code, token, host ID,
                // email or workout data is part of the connectivity check.
                delay(if (attempt == 0) 1_000L else 3_000L)
            }
        }
    }

    suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val original = synchronized(lock) { checkStorage(); current() }
                ?: throw signedOut()
            if (!original.account.planUsageEnabled) {
                throw ChatGptAuthException("plan_usage_disabled", "ChatGPTプランの利用が許可されていません。設定からもう一度ログインしてください。")
            }
            if (original.accessToken.isNotEmpty() && original.expiresAt > System.currentTimeMillis() + 60_000L) {
                return@withLock original.accessToken
            }
            if (original.refreshToken.isEmpty()) throw signedOut()
            val response = try {
                postForm(TOKEN, mapOf("grant_type" to "refresh_token", "client_id" to original.account.clientId,
                    "refresh_token" to original.refreshToken, "resource" to RESOURCE))
            } catch (error: ChatGptAuthException) {
                if (error.code in TERMINAL_REFRESH_ERRORS) synchronized(lock) {
                    if (current() === original) replace(original.withoutTokens())
                }
                if (error.code in TERMINAL_REFRESH_ERRORS) throw signedOut()
                throw error
            }
            val scopes = if (response.has("scope")) tokenScopes(response) else original.scopes
            val newIdToken = response.optString("id_token").ifBlank { original.idToken }
            if (newIdToken != original.idToken) {
                val claims = verifyIdentity(newIdToken, original.account.clientId, null)
                if (claims.getString("sub") != original.account.subject) {
                    throw ChatGptAuthException("identity_mismatch", "更新されたアカウントを確認できませんでした。もう一度ログインしてください。")
                }
            }
            val renewed = recordFromTokens(original.account.copy(planUsageEnabled = DIRECT_SCOPE in scopes),
                response, newIdToken, scopes)
            val retained = synchronized(lock) {
                if (current() !== original) false else {
                    replace(renewed)
                    true
                }
            }
            if (!retained) {
                // A synchronous sign-out may race the network response. Do not leave its
                // freshly rotated renewable session active after discarding the response.
                revoke(renewed)
                throw signedOut()
            }
            if (!renewed.account.planUsageEnabled) {
                throw ChatGptAuthException("plan_usage_disabled", "ChatGPTプランの利用が許可されていません。もう一度ログインしてください。")
            }
            renewed.accessToken
        }
    }

    fun cancelSignIn() = synchronized(lock) { cancelLocked() }

    /** Prefer signOutAndRevoke in UI so an unconfirmed remote revocation can be shown. */
    fun signOut() {
        val detached = detachSession()
        background.launch { revoke(detached) }
    }

    suspend fun signOutAndRevoke(): Boolean = withContext(Dispatchers.IO) {
        refreshMutex.withLock { revoke(detachSession()) }
    }

    private fun detachSession(): Record? = synchronized(lock) {
        checkStorage()
        cancelLocked()
        val old = current()
        val updated = records.map { if (it === old) it.withoutTokens() else it }
        save(updated, selectedId, "")
        records = updated
        pendingClientId = ""
        old
    }

    private suspend fun revoke(record: Record?, attempts: Int = 2): Boolean {
        if (record == null || record.refreshToken.isEmpty()) return true
        return revokeToken(record.refreshToken, record.account.clientId, attempts)
    }

    private suspend fun revokeToken(refreshToken: String, clientId: String, attempts: Int): Boolean {
        repeat(attempts) { attempt ->
            try {
                val discovery = getJson(DISCOVERY)
                val endpoint = checkedAuthUrl(discovery.getString("revocation_endpoint"))
                postForm(endpoint, mapOf("token" to refreshToken,
                    "token_type_hint" to "refresh_token", "client_id" to clientId))
                return true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (attempt + 1 < attempts) delay(500L)
            }
        }
        return false
    }

    private fun recordFromTokens(account: ChatGptAccount, tokens: JSONObject, idToken: String, scopes: Set<String>): Record {
        val access = tokens.optString("access_token")
        val refresh = tokens.optString("refresh_token")
        if (DIRECT_SCOPE in scopes && (access.isBlank() || !tokens.optString("token_type").equals("Bearer", true))) {
            throw ChatGptAuthException("invalid_token_response", "ChatGPTの認証情報を確認できませんでした。")
        }
        if ("offline_access" in scopes && refresh.isBlank()) {
            throw ChatGptAuthException("invalid_token_response", "ChatGPTの更新用認証情報を確認できませんでした。もう一度ログインしてください。")
        }
        val lifetime = tokens.optLong("expires_in", 0L)
        if (access.isNotBlank() && lifetime !in 1..86400) {
            throw ChatGptAuthException("invalid_token_response", "ChatGPTの認証期限を確認できませんでした。")
        }
        return Record(account, access, refresh, idToken, scopes, System.currentTimeMillis() + lifetime * 1000L)
    }

    private fun verifyIdentity(idToken: String, clientId: String, nonce: String?): JSONObject = authStep("IDENTITY") {
        val discovery = getJson(DISCOVERY)
        if (discovery.optString("issuer") != ISSUER) throw invalidIdentity()
        val jwks = getJson(checkedAuthUrl(discovery.getString("jwks_uri")))
        ChatGptJwt.verify(idToken, jwks, clientId, nonce)
    }

    private suspend fun waitForCallback(server: ServerSocket, state: String, clientId: String): ChatGptOAuth.Callback {
        val deadline = System.nanoTime() + 10L * 60L * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            val result = socket.use {
                it.soTimeout = 2000
                val request = try { readRequest(it) } catch (_: IOException) { return@use null }
                val path = request.first.split(' ')
                if (path.size != 3 || path[0] != "GET" || !path[1].startsWith("/auth/callback?")) {
                    sendPage(it, 404, "このページは使用できません。")
                    return@use null
                }
                if (request.second["host"] != "127.0.0.1:${server.localPort}") {
                    sendPage(it, 400, "ログインの接続先を確認できませんでした。")
                    return@use null
                }
                val callback = try { ChatGptOAuth.validateCallback(path[1], state, clientId) }
                catch (error: ChatGptAuthException) {
                    sendPage(it, 400, "ログインを完了できませんでした。IronLogに戻ってください。")
                    if (error.code == "invalid_state" || error.code == "invalid_callback") return@use null
                    throw error
                }
                sendPage(it, 200, "認証結果を受け取りました。この画面を閉じてIronLogに戻ってください。")
                callback
            }
            if (result != null) return result
        }
        throw ChatGptAuthException("timeout", "ログインの待ち時間が過ぎました。もう一度お試しください。")
    }

    private fun readRequest(socket: Socket): Pair<String, Map<String, String>> {
        val input = socket.getInputStream()
        val bytes = ByteArrayOutputStream()
        val deadline = System.nanoTime() + 2_000_000_000L
        var end = 0
        while (bytes.size() < 16_384) {
            if (System.nanoTime() >= deadline) throw IOException()
            val byte = input.read()
            if (byte < 0) throw IOException()
            bytes.write(byte)
            end = (end shl 8) or byte
            if (end == 0x0d0a0d0a) break
        }
        if (end != 0x0d0a0d0a) throw IOException()
        val lines = bytes.toString("US-ASCII").split("\r\n")
        val headers = mutableMapOf<String, String>()
        lines.drop(1).filter { it.isNotEmpty() }.forEach {
            val parts = it.split(':', limit = 2)
            if (parts.size != 2) throw IOException()
            val name = parts[0].lowercase(java.util.Locale.ROOT)
            if (headers.put(name, parts[1].trim()) != null) throw IOException()
        }
        return lines.first() to headers
    }

    private fun sendPage(socket: Socket, status: Int, message: String) {
        val body = "<!doctype html><html lang=\"ja\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>IronLog</title><p>$message</p></html>".toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Bad Request"
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n"
        runCatching { socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() } }
    }

    private fun current(): Record? = records.firstOrNull { it.account.clientId == selectedId }

    private fun replace(record: Record) {
        val updated = records.map { if (it.account.clientId == record.account.clientId) record else it }
        save(updated, selectedId, pendingClientId)
        records = updated
    }

    private fun cancelLocked() {
        epoch++
        runCatching { listener?.close() }
        listener = null
    }

    private fun checkEpoch(value: Long) {
        if (value != epoch) throw ChatGptAuthException("cancelled", "ログインを中止しました。")
    }

    private fun checkStorage() {
        if (storageFailure) throw ChatGptAuthException("storage_unavailable", "保存済みのChatGPT接続を読み取れませんでした。既存の接続情報は保持しています。")
    }

    private fun loadHostId(): String = authStep("STORAGE") {
        if (atomicExists(hostFile)) {
            val id = hostFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            if (!id.startsWith("urn:uuid:") || runCatching { UUID.fromString(id.removePrefix("urn:uuid:")) }.isFailure) {
                throw ChatGptAuthException("storage_unavailable", "端末のChatGPT接続情報を読み取れませんでした。")
            }
            return@authStep id
        }
        val value = "urn:uuid:${UUID.randomUUID()}"
        atomicWrite(hostFile, value.toByteArray(Charsets.UTF_8))
        value
    }

    private fun save(profiles: List<Record>, selected: String, pending: String) = authStep("STORAGE") {
        val array = JSONArray()
        profiles.forEach { record ->
            array.put(JSONObject().put("email", record.account.email).put("subject", record.account.subject)
                .put("client_id", record.account.clientId).put("host_id", record.account.hostId)
                .put("access_token", record.accessToken).put("refresh_token", record.refreshToken)
                .put("id_token", record.idToken).put("scope", record.scopes.joinToString(" "))
                .put("expires_at", record.expiresAt))
        }
        val json = JSONObject().put("selected_id", selected).put("pending_client_id", pending).put("profiles", array)
        atomicWrite(file, encrypt(json.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("IronLog ChatGPT v1".toByteArray(Charsets.UTF_8))
        return byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size > 30 && data[0] == 1.toByte() && data[1] == 12.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(2, 14)))
        cipher.updateAAD("IronLog ChatGPT v1".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(data.copyOfRange(14, data.size))
    }

    private fun atomicWrite(target: AtomicFile, bytes: ByteArray) {
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
    }

    private fun atomicExists(target: AtomicFile): Boolean =
        target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()

    private fun getJson(url: String): JSONObject = request(url, null)
    private fun postForm(url: String, parameters: Map<String, String>): JSONObject = request(url, form(parameters))

    private fun request(url: String, body: String?): JSONObject {
        val stage = when (url) {
            TOKEN -> "TOKEN"
            DISCOVERY -> "DISCOVERY"
            "$ISSUER/.well-known/jwks.json" -> "JWKS"
            else -> "AUTH"
        }
        return authStep(stage) {
            val checkedUrl = checkedAuthUrl(url)
            transport?.request(checkedUrl, body) ?: httpRequest(checkedUrl, body)
        }
    }

    private fun httpRequest(url: String, body: String?): JSONObject {
        val connection = URL(checkedAuthUrl(url)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "IronLog-Android")
            if (body != null) {
                val requestBytes = body.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                // Disable HttpURLConnection's buffered-body replay for one-use codes
                // and rotating refresh tokens after an ambiguous transport failure.
                connection.setFixedLengthStreamingMode(requestBytes.size)
                connection.outputStream.use { it.write(requestBytes) }
            }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = input?.use { stream ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (result.size() + n > 1_048_576) throw IOException("認証応答が大きすぎます。")
                    result.write(buffer, 0, n)
                }
                result.toByteArray()
            } ?: byteArrayOf()
            val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                val raw = if (json.opt("error") is String) json.optString("error") else json.optJSONObject("error")?.optString("code").orEmpty()
                val code = raw.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,100}")) } ?: "http_$status"
                val message = if (code in TERMINAL_REFRESH_ERRORS || code == "invalid_grant") {
                    "ChatGPTの認証期限が切れました。もう一度ログインしてください。"
                } else "ChatGPTの認証に失敗しました（$code）。もう一度お試しください。"
                throw ChatGptAuthException(code, message)
            }
            return json
        } finally { connection.disconnect() }
    }

    private inline fun <T> authStep(stage: String, action: () -> T): T = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ChatGptAuthException) {
        throw error
    } catch (error: Exception) {
        throw safeAuthFailure(stage, error)
    }

    private fun safeAuthFailure(stage: String, error: Exception): ChatGptAuthException {
        // Only fixed categories are shown. Exception text can contain URLs,
        // authorization codes or credentials, so never display or log it.
        val category = when (error) {
            is UnknownHostException -> "DNS"
            is SSLException -> "TLS"
            is SocketTimeoutException -> "TIMEOUT"
            is GeneralSecurityException, is ProviderException -> "KEYSTORE"
            is SecurityException -> "PERMISSION"
            is IOException -> "IO"
            is org.json.JSONException -> "RESPONSE"
            else -> "INTERNAL"
        }
        val code = "AUTH-$stage-$category"
        val message = if (category == "DNS") {
            "認証サーバーのアドレスを確認できませんでした。Wi-Fiとモバイル通信を切り替えてお試しください。続く場合は端末のIRON LOGの通信許可を確認してください。"
        } else when (stage) {
            "STORAGE" -> "ChatGPTの接続情報を端末に保存できませんでした。記録は消さず、アプリを開き直してお試しください。"
            "DISCOVERY", "JWKS", "IDENTITY" -> "ChatGPTの本人確認に必要な情報を取得できませんでした。もう一度ログインしてください。"
            else -> "ChatGPTのログイン処理を完了できませんでした。通信を確認して、もう一度ログインしてください。"
        }
        return ChatGptAuthException(code, "$message（$code）")
    }

    companion object {
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
        private const val ISSUER = "https://auth.openai.com"
        private const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
        private const val TOKEN = "$ISSUER/api/accounts/oauth/token"
        private const val DISCOVERY = "$ISSUER/.well-known/openid-configuration"
        private const val RESOURCE = "https://api.openai.com/v1"
        private const val DYNAMIC_CLIENT = "dynamic_agent_client"
        private const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
        private const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        private const val KEY_ALIAS = "ironlog.chatgpt.accounts.v1"
        private val TERMINAL_REFRESH_ERRORS = setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
        @Volatile private var instance: ChatGptAuth? = null
        fun get(context: Context): ChatGptAuth = instance ?: synchronized(this) {
            instance ?: ChatGptAuth(context.applicationContext).also { instance = it }
        }

        private fun signedOut() = ChatGptAuthException("sign_in_required", "ChatGPTにログインしてください。")
        private fun invalidIdentity() = ChatGptAuthException("invalid_identity", "ChatGPTのアカウントを安全に確認できませんでした。")
        private fun tokenScopes(tokens: JSONObject): Set<String> = tokens.optString("scope").split(' ').filter { it.isNotBlank() }.toSet()
        private fun randomValue(size: Int = 32): String = base64Url(ByteArray(size).also { SecureRandom().nextBytes(it) })
        private fun base64Url(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun form(values: Map<String, String>): String = values.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }
        private fun checkedAuthUrl(value: String): String {
            val uri = URI(value)
            require(uri.scheme == "https" && uri.host == "auth.openai.com" && uri.port in setOf(-1, 443) && uri.rawUserInfo == null && uri.rawFragment == null)
            return value
        }
    }
}

/** Kept separate for adversarial callback tests that do not need Android or network access. */
internal object ChatGptOAuth {
    data class Callback(val code: String, val clientId: String)

    fun validateCallback(target: String, expectedState: String, expectedClientId: String): Callback {
        fun rejected() = ChatGptAuthException("invalid_callback", "ログインの応答を確認できませんでした。もう一度ログインしてください。")
        val uri = try { URI(target) } catch (_: Exception) { throw rejected() }
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null || uri.rawPath != "/auth/callback") throw rejected()
        val params = mutableMapOf<String, String>()
        try {
            uri.rawQuery.orEmpty().split('&').forEach { part ->
                val pair = part.split('=', limit = 2)
                val key = URLDecoder.decode(pair[0], "UTF-8")
                val value = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                if (params.put(key, value) != null) throw rejected()
            }
        } catch (_: Exception) { throw rejected() }
        if (!MessageDigest.isEqual(params["state"].orEmpty().toByteArray(Charsets.UTF_8), expectedState.toByteArray(Charsets.UTF_8))) {
            throw ChatGptAuthException("invalid_state", "ログインの応答を確認できませんでした。")
        }
        if (params.containsKey("error")) {
            throw ChatGptAuthException("access_denied", "ログインが許可されませんでした。必要なときにもう一度お試しください。")
        }
        val code = params["code"]?.takeIf { it.isNotBlank() && it.length <= 8192 } ?: throw rejected()
        val returnedId = params["client_id"]
        val clientId = if (expectedClientId == "dynamic_agent_client") {
            returnedId?.takeIf { it.isNotBlank() && it != "dynamic_agent_client" } ?: throw rejected()
        } else {
            if (returnedId != null && returnedId != expectedClientId) throw rejected()
            expectedClientId
        }
        if (clientId.length > 512 || clientId.any { it.code !in 33..126 }) throw rejected()
        return Callback(code, clientId)
    }
}

/** Verifies RS256 with platform cryptography; rejects unsupported algorithms and untrusted keys. */
internal object ChatGptJwt {
    fun verify(token: String, jwks: JSONObject, clientId: String, nonce: String?, nowSeconds: Long = System.currentTimeMillis() / 1000L): JSONObject {
        fun reject(): Nothing = throw ChatGptAuthException("invalid_identity", "ChatGPTのアカウントを安全に確認できませんでした。")
        try {
            val parts = token.split('.')
            if (parts.size != 3 || token.length > 65_536) reject()
            fun decode(value: String): ByteArray {
                if (!value.matches(Regex("[A-Za-z0-9_-]+"))) reject()
                return Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            }
            val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
            if (header.optString("alg") != "RS256" || header.has("crit")) reject()
            val kid = header.optString("kid").takeIf { it.isNotBlank() } ?: reject()
            val keys = jwks.getJSONArray("keys")
            val matches = (0 until keys.length()).map { keys.getJSONObject(it) }.filter {
                it.optString("kid") == kid && it.optString("kty") == "RSA" &&
                    (!it.has("alg") || it.optString("alg") == "RS256") && (!it.has("use") || it.optString("use") == "sig")
            }
            if (matches.size != 1) reject()
            val key = matches.single()
            val modulus = BigInteger(1, decode(key.getString("n")))
            val exponent = BigInteger(1, decode(key.getString("e")))
            if (modulus.bitLength() < 2048 || modulus.bitLength() > 8192 || exponent < BigInteger.valueOf(3)) reject()
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            if (!verifier.verify(decode(parts[2]))) reject()
            val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
            if (claims.optString("iss") != "https://auth.openai.com") reject()
            val audience = claims.opt("aud")
            val audiences = when (audience) {
                is String -> listOf(audience)
                is JSONArray -> (0 until audience.length()).map { audience.getString(it) }
                else -> emptyList()
            }
            if (clientId !in audiences || (audiences.size > 1 && claims.optString("azp") != clientId)) reject()
            if (claims.has("azp") && claims.optString("azp") != clientId) reject()
            if (claims.opt("exp") !is Number || claims.optLong("exp") <= nowSeconds - 5L) reject()
            if (claims.opt("iat") !is Number || claims.optLong("iat") > nowSeconds + 5L) reject()
            if (claims.has("nbf") && (claims.opt("nbf") !is Number || claims.optLong("nbf") > nowSeconds + 5L)) reject()
            if (claims.optString("sub").isBlank()) reject()
            if (nonce != null && !MessageDigest.isEqual(claims.optString("nonce").toByteArray(Charsets.UTF_8), nonce.toByteArray(Charsets.UTF_8))) reject()
            return claims
        } catch (error: ChatGptAuthException) { throw error }
        catch (_: Exception) { reject() }
    }
}
