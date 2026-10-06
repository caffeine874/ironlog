package com.example.training

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.net.ssl.SSLHandshakeException

/** Real Android crypto/storage and loopback sockets; all identities and server responses are synthetic. */
@RunWith(AndroidJUnit4::class)
class ChatGptAuthInstrumentedTest {
    @Test
    fun acceptedCallbackPersistsEncryptedSessionAndReloadsWithoutNetwork() = runBlocking {
        Fixture().use { fixture ->
            val auth = fixture.auth()
            val account = fixture.signIn(auth, invalidCallbacksFirst = true)
            assertEquals(SUBJECT, account.subject)
            assertEquals(CLIENT_ID, account.clientId)
            assertTrue(account.planUsageEnabled)
            assertEquals(listOf(400, 400, 200), fixture.callbackStatuses)
            assertEquals(1, fixture.authorizationExchanges)
            assertTrue("Pending registration must be durable before token exchange", fixture.pendingWasSaved)
            assertEncrypted(fixture.accountFile)

            val requestsBeforeReload = fixture.requests
            val reloaded = fixture.auth()
            assertEquals(account, reloaded.account())
            assertEquals(listOf(account), reloaded.savedAccounts())
            assertEquals(ACCESS_TOKEN, reloaded.accessToken())
            assertEquals("A fresh saved token must not require network", requestsBeforeReload, fixture.requests)
        }
    }

    @Test
    fun pendingClientAndHostSurviveFailedExchangeAndAreReused() = runBlocking {
        Fixture().use { fixture ->
            fixture.tokenFailure = UnknownHostException(RAW_ERROR_SENTINEL)
            val first = fixture.auth()
            assertSafeFailure("AUTH-TOKEN-DNS", authFailure { fixture.signIn(first) })
            assertNull(first.account())
            assertTrue(fixture.pendingWasSaved)
            assertEncrypted(fixture.accountFile)
            val initialHost = fixture.authorizeParameters.getValue("ext_agent_host_id")
            assertEquals("dynamic_agent_client", fixture.authorizeParameters.getValue("client_id"))

            fixture.tokenFailure = null
            val reloaded = fixture.auth()
            assertNull(reloaded.account())
            val account = fixture.signIn(reloaded)
            assertEquals(CLIENT_ID, fixture.authorizeParameters.getValue("client_id"))
            assertEquals(initialHost, fixture.authorizeParameters.getValue("ext_agent_host_id"))
            assertEquals(initialHost, account.hostId)
            assertEquals(account, fixture.auth().account())
        }
    }

    @Test
    fun savedRefreshTokenRotatesAndPersistsAfterReload() = runBlocking {
        Fixture().use { fixture ->
            fixture.initialLifetime = 1L
            val original = fixture.signIn(fixture.auth())
            val reloaded = fixture.auth()
            assertEquals(original, reloaded.account())
            assertEquals(ROTATED_ACCESS_TOKEN, reloaded.accessToken())
            assertEquals(1, fixture.refreshExchanges)
            assertEncrypted(fixture.accountFile)

            val requestsBeforeReload = fixture.requests
            val afterRotation = fixture.auth()
            assertEquals(original, afterRotation.account())
            assertEquals(ROTATED_ACCESS_TOKEN, afterRotation.accessToken())
            assertEquals(requestsBeforeReload, fixture.requests)
        }
    }

    @Test
    fun transportFailuresExposeOnlySafeStageCodes() = runBlocking {
        listOf(
            UnknownHostException(RAW_ERROR_SENTINEL) to "AUTH-TOKEN-DNS",
            SSLHandshakeException(RAW_ERROR_SENTINEL) to "AUTH-TOKEN-TLS",
            SocketTimeoutException(RAW_ERROR_SENTINEL) to "AUTH-TOKEN-TIMEOUT",
            IOException(RAW_ERROR_SENTINEL) to "AUTH-TOKEN-IO"
        ).forEach { (failure, expectedCode) ->
            Fixture().use { fixture ->
                fixture.tokenFailure = failure
                val auth = fixture.auth()
                assertSafeFailure(expectedCode, authFailure { fixture.signIn(auth) })
                assertNull(auth.account())
                assertTrue(fixture.pendingWasSaved)
                assertEquals("A one-use authorization code must never be replayed", 1, fixture.authorizationExchanges)
            }
        }
    }

    @Test
    fun incompatibleAndroidKeystoreKeyIsReportedAsStorageFailure() = runBlocking {
        Fixture().use { fixture ->
            // This deliberately incompatible key exists only under this test's random alias.
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(fixture.keyAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKey()
            val auth = fixture.auth()
            assertSafeFailure("AUTH-STORAGE-KEYSTORE", authFailure { fixture.signIn(auth) })
            assertNull(auth.account())
            assertEquals("Only the public preflight may run when pending storage fails", 1, fixture.requests)
            assertEquals("No token request may occur when pending storage fails", 0, fixture.authorizationExchanges)
        }
    }

    @Test
    fun atomicWriteFailureIsReportedAsStorageInsteadOfNetwork() = runBlocking {
        Fixture().use { fixture ->
            val auth = fixture.auth()
            val error = authFailure {
                fixture.signIn(auth, beforeCallback = {
                    // Current AtomicFile writes .new; older Android used the base/.bak pair.
                    // Nonempty directories obstruct both variants without touching real files.
                    listOf("chatgpt_accounts.enc", "chatgpt_accounts.enc.bak", "chatgpt_accounts.enc.new").forEach { name ->
                        val obstruction = File(fixture.context.noBackupFilesDir, name)
                        assertTrue(obstruction.mkdir())
                        File(obstruction, "synthetic-obstruction").writeText("test only")
                    }
                })
            }
            assertSafeFailure("AUTH-STORAGE-IO", error)
            assertNull(auth.account())
            assertEquals("Only the public preflight may run when pending storage fails", 1, fixture.requests)
            assertEquals("No token request may occur when pending storage fails", 0, fixture.authorizationExchanges)
        }
    }

    @Test
    fun semanticOauthFailureKeepsItsExistingMeaning() = runBlocking {
        Fixture().use { fixture ->
            fixture.tokenFailure = ChatGptAuthException("invalid_grant", "認証期限が切れました。")
            val error = authFailure { fixture.signIn(fixture.auth()) }
            assertEquals("invalid_grant", error.code)
            assertEquals("認証期限が切れました。", error.message)
            assertEquals("An invalid code must never be replayed", 1, fixture.authorizationExchanges)
        }
    }

    @Test
    fun tokenExchangeWaitsForForegroundAfterAcceptingTheCallback() = runBlocking {
        Fixture().use { fixture ->
            val auth = fixture.auth()
            val gateEntered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val attempt = async {
                fixture.signIn(auth, awaitForeground = {
                    gateEntered.complete(Unit)
                    resume.await()
                })
            }
            try {
                withTimeout(5000L) { gateEntered.await() }
                assertEquals(1, fixture.browserOpens)
                assertTrue("Issued client ID must survive while waiting for foreground", fixture.accountFile.isFile)
                assertEquals("Background wait must not make another public request", 1, fixture.discoveryRequests)
                assertEquals("A callback alone must not start the code exchange", 0, fixture.authorizationExchanges)
                assertNull(auth.account())
                resume.complete(Unit)
                assertEquals(SUBJECT, attempt.await().subject)
                assertEquals(1, fixture.authorizationExchanges)
            } finally {
                attempt.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellationWhileAwaitingForegroundPreventsEveryFurtherRequest() = runBlocking {
        Fixture().use { fixture ->
            val auth = fixture.auth()
            val gateEntered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val attempt = async {
                authFailure {
                    fixture.signIn(auth, awaitForeground = {
                        gateEntered.complete(Unit)
                        resume.await()
                    })
                }
            }
            try {
                withTimeout(5000L) { gateEntered.await() }
                auth.cancelSignIn()
                resume.complete(Unit)
                assertEquals("cancelled", attempt.await().code)
                assertEquals(1, fixture.discoveryRequests)
                assertEquals(0, fixture.authorizationExchanges)
                assertNull(auth.account())
            } finally {
                attempt.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellationDuringInitialPreflightDoesNotOpenTheBrowserAfterTheGetCompletes() = runBlocking {
        Fixture().use { fixture ->
            val preflightEntered = CompletableDeferred<Unit>()
            val releasePreflight = CountDownLatch(1)
            fixture.discoveryFailure = { _, _ ->
                preflightEntered.complete(Unit)
                assertTrue("Synthetic public GET must be released by the test", releasePreflight.await(5, TimeUnit.SECONDS))
                null
            }
            val auth = fixture.auth()
            val attempt = async { authFailure { fixture.signIn(auth) } }
            try {
                withTimeout(5000L) { preflightEntered.await() }
                auth.cancelSignIn()
                releasePreflight.countDown()
                assertEquals("cancelled", attempt.await().code)
                assertEquals(1, fixture.discoveryRequests)
                assertEquals("A cancelled attempt must never launch a browser", 0, fixture.browserOpens)
                assertEquals(0, fixture.authorizationExchanges)
                assertFalse(fixture.accountFile.exists())
                assertNull(auth.account())
            } finally {
                releasePreflight.countDown()
                attempt.cancelAndJoin()
            }
        }
    }

    @Test
    fun persistentDnsPreflightFailureStopsBeforeOpeningTheBrowser() = runBlocking {
        Fixture().use { fixture ->
            fixture.discoveryFailure = { _, _ -> UnknownHostException(RAW_ERROR_SENTINEL) }
            val auth = fixture.auth()
            assertSafeFailure("AUTH-DISCOVERY-DNS", authFailure { fixture.signIn(auth) })
            assertEquals("Only three public DNS attempts are allowed", 3, fixture.discoveryRequests)
            assertEquals(0, fixture.browserOpens)
            assertEquals(0, fixture.authorizationExchanges)
            assertFalse(fixture.accountFile.exists())
            assertNull(auth.account())
        }
    }

    @Test
    fun transientDnsPreflightFailureCanRecoverWithoutRepeatingBrowserConsent() = runBlocking {
        Fixture().use { fixture ->
            fixture.discoveryFailure = { number, _ -> if (number == 1) UnknownHostException(RAW_ERROR_SENTINEL) else null }
            val account = fixture.signIn(fixture.auth())
            assertEquals(SUBJECT, account.subject)
            assertEquals(1, fixture.browserOpens)
            assertEquals(1, fixture.authorizationExchanges)
            assertEquals("One retry plus foreground and identity metadata requests", 4, fixture.discoveryRequests)
        }
    }

    @Test
    fun nonDnsPreflightFailuresAreNeverRetried() = runBlocking {
        listOf(
            SSLHandshakeException(RAW_ERROR_SENTINEL) to "AUTH-DISCOVERY-TLS",
            SocketTimeoutException(RAW_ERROR_SENTINEL) to "AUTH-DISCOVERY-TIMEOUT",
            IOException(RAW_ERROR_SENTINEL) to "AUTH-DISCOVERY-IO"
        ).forEach { (failure, expectedCode) ->
            Fixture().use { fixture ->
                fixture.discoveryFailure = { _, _ -> failure }
                assertSafeFailure(expectedCode, authFailure { fixture.signIn(fixture.auth()) })
                assertEquals(1, fixture.discoveryRequests)
                assertEquals(0, fixture.browserOpens)
                assertEquals(0, fixture.authorizationExchanges)
            }
        }
    }

    @Test
    fun dnsFailureAfterForegroundPreservesPendingClientButNeverExchangesCode() = runBlocking {
        Fixture().use { fixture ->
            fixture.discoveryFailure = { _, browserOpens -> if (browserOpens > 0) UnknownHostException(RAW_ERROR_SENTINEL) else null }
            val auth = fixture.auth()
            var foregroundChecks = 0
            assertSafeFailure("AUTH-DISCOVERY-DNS", authFailure {
                fixture.signIn(auth, awaitForeground = { foregroundChecks++ })
            })
            assertEquals(1, foregroundChecks)
            assertEquals(1, fixture.browserOpens)
            assertEquals("One initial preflight followed by three foreground DNS attempts", 4, fixture.discoveryRequests)
            assertEquals(0, fixture.authorizationExchanges)
            assertEquals(0, fixture.revocations)
            assertEncrypted(fixture.accountFile)
            assertNull(fixture.auth().account())
        }
    }

    @Test
    fun failedIdentityLookupRevokesIssuedGrantWithoutSavingAnAccount() = runBlocking {
        Fixture().use { fixture ->
            fixture.jwksFailure = IOException(RAW_ERROR_SENTINEL)
            val auth = fixture.auth()
            assertSafeFailure("AUTH-JWKS-IO", authFailure { fixture.signIn(auth) })
            assertNull(auth.account())
            assertNull(fixture.auth().account())
            assertEquals(1, fixture.revocations)
        }
    }

    @Test
    fun livePublicMetadataProbe() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAuthMetadata") == "true")
        Fixture().use { fixture ->
            val auth = ChatGptAuth(fixture.context, keyAlias = fixture.keyAlias)
            val request = ChatGptAuth::class.java.getDeclaredMethod("request", String::class.java, String::class.java)
                .apply { isAccessible = true }
            fun publicGet(url: String): JSONObject = try {
                request.invoke(auth, url, null) as JSONObject
            } catch (error: InvocationTargetException) {
                val failure = error.targetException
                // Even explicit diagnostic runs must not print raw transport exception contents.
                val detail = if (failure is ChatGptAuthException) failure.message else "unclassified metadata failure"
                throw AssertionError("Public authentication metadata unavailable: $detail")
            }
            val discovery = publicGet("$ISSUER/.well-known/openid-configuration")
            assertTrue("Public metadata must declare the expected issuer", discovery.optString("issuer") == ISSUER)
            val jwksUri = discovery.optString("jwks_uri")
            assertTrue("Public metadata must provide a JWKS URL", jwksUri.isNotBlank())
            // The private request method applies the same strict HTTPS/host allowlist as production.
            val keys = publicGet(jwksUri).optJSONArray("keys")
            assertTrue("Public JWKS must contain signing keys", keys != null && keys.length() > 0)
        }
    }

    private suspend fun authFailure(block: suspend () -> Unit): ChatGptAuthException {
        try {
            block()
        } catch (error: ChatGptAuthException) {
            return error
        }
        throw AssertionError("Expected a classified authentication failure")
    }

    private fun assertSafeFailure(expectedCode: String, error: ChatGptAuthException) {
        assertTrue("Failure must identify the safe stage code $expectedCode", error.message.orEmpty().contains(expectedCode))
        assertFalse("Raw exception contents must not be displayed", error.message.orEmpty().contains(RAW_ERROR_SENTINEL))
        assertFalse("Raw exception contents must not become an error code", error.code.contains(RAW_ERROR_SENTINEL))
        assertFalse("Callback codes must not be displayed", error.message.orEmpty().contains(AUTHORIZATION_CODE))
        assertFalse("Tokens must not be displayed", error.message.orEmpty().contains(REFRESH_TOKEN))
    }

    private fun assertEncrypted(file: File) {
        assertTrue(file.isFile)
        val contents = file.readBytes()
        assertTrue(contents.size > 30)
        assertEquals(1, contents[0].toInt())
        assertEquals(12, contents[1].toInt())
        val text = String(contents, Charsets.ISO_8859_1)
        listOf(CLIENT_ID, SUBJECT, ACCESS_TOKEN, REFRESH_TOKEN, ROTATED_ACCESS_TOKEN, ROTATED_REFRESH_TOKEN).forEach {
            assertFalse("Synthetic account/token data must not be stored in plaintext", text.contains(it))
        }
    }

    private class Fixture : AutoCloseable {
        private val target = InstrumentationRegistry.getInstrumentation().targetContext
        private val id = UUID.randomUUID().toString()
        private val directory = File(target.cacheDir, "chatgpt-auth-instrumented-$id").apply { check(mkdir()) }
        val keyAlias = "ironlog.chatgpt.test.$id"
        val context: Context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { check(isDirectory || mkdir()) }
        }
        val accountFile: File get() = File(context.noBackupFilesDir, "chatgpt_accounts.enc")
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        @Volatile var authorizeParameters = emptyMap<String, String>()
        @Volatile var browserOpens = 0
        @Volatile var discoveryRequests = 0
        @Volatile var discoveryFailure: ((Int, Int) -> Exception?)? = null
        @Volatile var tokenFailure: Exception? = null
        @Volatile var jwksFailure: Exception? = null
        @Volatile var initialLifetime = 3600L
        @Volatile var requests = 0
        @Volatile var authorizationExchanges = 0
        @Volatile var refreshExchanges = 0
        @Volatile var pendingWasSaved = false
        @Volatile var revocations = 0
        val callbackStatuses = mutableListOf<Int>()

        fun auth() = ChatGptAuth(context, ChatGptAuthTransport { url, body -> respond(url, body) }, keyAlias)

        suspend fun signIn(
            auth: ChatGptAuth,
            invalidCallbacksFirst: Boolean = false,
            beforeCallback: () -> Unit = {},
            awaitForeground: suspend () -> Unit = {}
        ): ChatGptAccount = withTimeout(20_000L) {
            coroutineScope {
                var callbackJob: Job? = null
                val account = auth.signIn(openBrowser = { authorizationUrl ->
                    browserOpens++
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    val uri = URI(authorizationUrl)
                    assertEquals("https", uri.scheme)
                    assertEquals("auth.openai.com", uri.host)
                    authorizeParameters = parameters(uri.rawQuery)
                    callbackJob = launch(Dispatchers.IO) {
                        beforeCallback()
                        val values = authorizeParameters
                        val redirect = URI(values.getValue("redirect_uri"))
                        assertEquals("http", redirect.scheme)
                        assertEquals("127.0.0.1", redirect.host)
                        assertEquals("/auth/callback", redirect.path)
                        val state = values.getValue("state")
                        if (invalidCallbacksFirst) {
                            callbackStatuses += sendCallback(redirect, "state=wrong-state&code=$AUTHORIZATION_CODE&client_id=$CLIENT_ID")
                            callbackStatuses += sendCallback(redirect, "state=${encodeQuery(state)}&state=${encodeQuery(state)}&code=$AUTHORIZATION_CODE&client_id=$CLIENT_ID")
                        }
                        val issuedClient = if (values.getValue("client_id") == "dynamic_agent_client") "&client_id=$CLIENT_ID" else ""
                        callbackStatuses += sendCallback(redirect, "state=${encodeQuery(state)}&code=$AUTHORIZATION_CODE$issuedClient")
                    }
                }, awaitForeground = awaitForeground)
                assertNotNull(callbackJob)
                callbackJob?.join()
                account
            }
        }

        private fun sendCallback(redirect: URI, query: String): Int = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", redirect.port), 5000)
            socket.soTimeout = 5000
            val request = "GET ${redirect.rawPath}?$query HTTP/1.1\r\nHost: 127.0.0.1:${redirect.port}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
            val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            response.lineSequence().first().split(' ')[1].toInt()
        }

        private fun respond(url: String, body: String?): JSONObject {
            assertFalse("Auth requests must not execute on the main thread", Looper.myLooper() == Looper.getMainLooper())
            requests++
            return when (url) {
                "$ISSUER/api/accounts/oauth/token" -> {
                    val values = parameters(requireNotNull(body))
                    assertEquals(CLIENT_ID, values["client_id"])
                    assertEquals("https://api.openai.com/v1", values["resource"])
                    when (values.getValue("grant_type")) {
                        "authorization_code" -> {
                            authorizationExchanges++
                            pendingWasSaved = accountFile.isFile
                            assertEquals(AUTHORIZATION_CODE, values["code"])
                            assertEquals(authorizeParameters["redirect_uri"], values["redirect_uri"])
                            val challenge = base64(MessageDigest.getInstance("SHA-256")
                                .digest(values.getValue("code_verifier").toByteArray(Charsets.US_ASCII)))
                            assertEquals(authorizeParameters["code_challenge"], challenge)
                            tokenFailure?.let { throw it }
                            tokenResponse(ACCESS_TOKEN, REFRESH_TOKEN, initialLifetime)
                                .put("id_token", identity())
                                .put("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                        }
                        "refresh_token" -> {
                            refreshExchanges++
                            assertEquals(REFRESH_TOKEN, values["refresh_token"])
                            tokenResponse(ROTATED_ACCESS_TOKEN, ROTATED_REFRESH_TOKEN, 3600L)
                        }
                        else -> throw AssertionError("Unexpected synthetic grant type")
                    }
                }
                "$ISSUER/.well-known/openid-configuration" -> {
                    assertNull(body)
                    discoveryRequests++
                    discoveryFailure?.invoke(discoveryRequests, browserOpens)?.let { throw it }
                    JSONObject().put("issuer", ISSUER).put("jwks_uri", "$ISSUER/.well-known/jwks.json")
                        .put("revocation_endpoint", "$ISSUER/test-revoke")
                }
                "$ISSUER/.well-known/jwks.json" -> {
                    assertNull(body)
                    jwksFailure?.let { throw it }
                    val key = pair.public as RSAPublicKey
                    JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "synthetic-key")
                        .put("kty", "RSA").put("alg", "RS256").put("use", "sig")
                        .put("n", base64(key.modulus.toByteArray())).put("e", base64(key.publicExponent.toByteArray()))))
                }
                "$ISSUER/test-revoke" -> {
                    val values = parameters(requireNotNull(body))
                    assertEquals(REFRESH_TOKEN, values["token"])
                    assertEquals("refresh_token", values["token_type_hint"])
                    assertEquals(CLIENT_ID, values["client_id"])
                    revocations++
                    JSONObject()
                }
                else -> throw AssertionError("Unexpected auth endpoint; no real network transport is allowed")
            }
        }

        private fun tokenResponse(access: String, refresh: String, lifetime: Long) = JSONObject()
            .put("access_token", access).put("refresh_token", refresh).put("token_type", "Bearer").put("expires_in", lifetime)

        private fun identity(): String {
            val now = System.currentTimeMillis() / 1000L
            val claims = JSONObject().put("iss", ISSUER).put("sub", SUBJECT).put("email", "synthetic@example.test")
                .put("aud", CLIENT_ID).put("nonce", authorizeParameters.getValue("nonce"))
                .put("iat", now - 30).put("exp", now + 3600)
            val header = base64("{\"alg\":\"RS256\",\"kid\":\"synthetic-key\"}".toByteArray(Charsets.UTF_8))
            val content = "$header.${base64(claims.toString().toByteArray(Charsets.UTF_8))}"
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(pair.private)
                update(content.toByteArray(Charsets.US_ASCII))
            }.sign()
            return "$content.${base64(signature)}"
        }

        override fun close() {
            // Both deletion targets are unique to this fixture; production files/keys are never selected.
            check(keyAlias.startsWith("ironlog.chatgpt.test.") && keyAlias.endsWith(id))
            check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
            check(directory.name == "chatgpt-auth-instrumented-$id")
            try {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
            } finally {
                check(directory.deleteRecursively())
            }
        }
    }

    companion object {
        private const val ISSUER = "https://auth.openai.com"
        private const val CLIENT_ID = "oaiapp_synthetic_instrumented"
        private const val SUBJECT = "synthetic-auth-subject"
        private const val AUTHORIZATION_CODE = "synthetic-one-use-code"
        private const val ACCESS_TOKEN = "synthetic-access-token-not-a-credential"
        private const val REFRESH_TOKEN = "synthetic-refresh-token-not-a-credential"
        private const val ROTATED_ACCESS_TOKEN = "synthetic-rotated-access-not-a-credential"
        private const val ROTATED_REFRESH_TOKEN = "synthetic-rotated-refresh-not-a-credential"
        private const val RAW_ERROR_SENTINEL = "PRIVATE_DIAGNOSTIC_SENTINEL_do_not_show"

        private fun base64(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")
        private fun parameters(encoded: String): Map<String, String> = encoded.split('&').associate { item ->
            val pair = item.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
    }
}
