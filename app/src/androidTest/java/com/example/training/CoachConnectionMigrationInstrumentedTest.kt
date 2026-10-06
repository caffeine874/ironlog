package com.example.training

import android.content.Context
import android.content.ContextWrapper
import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CoachConnectionMigrationInstrumentedTest {
    @Test fun oldHttpSettingsArePreservedButNeverConsideredReady() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "relay-migration-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val stored = File(directory, "ai_coach_connection.json")
        val oldUrl = "http://100.64.0.1:8765"
        val token = "synthetic-migration-token-1234567890"
        val raw = JSONObject().put("url", oldUrl).put("token", token).toString()
        stored.writeText(raw)
        val preferences = CoachPreferences("saved-model", "high", "保存されたモデル")
        val store = CoachStore(context)
        try {
            store.writePreferences(preferences)
            val connection = store.readConnection()
            assertEquals(oldUrl, connection.url)
            assertEquals(token, connection.token)
            assertFalse(connection.configured)
            assertEquals(raw, stored.readText())
            assertEquals(preferences, store.readPreferences())
        } finally { store.close() }
    }

    @Test fun platformBlocksRemoteCleartextAndKeepsLoopbackAvailable() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted("100.64.0.1"))
        assertFalse(policy.isCleartextTrafficPermitted("computer.tail123.ts.net"))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue(policy.isCleartextTrafficPermitted("localhost"))
    }
}
