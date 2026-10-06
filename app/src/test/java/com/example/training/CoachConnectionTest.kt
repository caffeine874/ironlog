package com.example.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CoachConnectionTest {
    private val token = "test-connection-token-1234567890"

    @Test
    fun tailscaleAddressesAndLocalDevelopmentAreAllowed() {
        listOf("https://100.64.0.1:8765", "https://100.127.255.254:8765", "https://home.tail123.ts.net", "http://127.0.0.1:8765", "http://localhost:8765", "http://[::1]:8765").forEach { url ->
            assertEquals(url, CoachConnection("$url/", token).checked().url)
            assertTrue(CoachConnection(url, token).configured)
        }
    }

    @Test
    fun publicAndAmbiguousAddressesAreRejectedBeforeSendingData() {
        listOf(
            "http://example.com", "https://example.com", "http://100.63.255.255", "http://100.128.0.1",
            "http://100.64.0.1:8765", "http://100.127.255.254:8765",
            "http://192.168.1.20", "http://home.tail123.ts.net", "https://evilts.net", "http://100.64.0.1@evil.com",
            "http://100.64.0.1/private", "http://100.64.0.1?token=secret", "http://100.64.0.1#fragment",
            "http://100.64.999.1", "http://100.64.0.1:0", "file:///etc/passwd"
        ).forEach { url ->
            assertThrows("Unexpectedly accepted $url", IllegalArgumentException::class.java) { CoachConnection(url, token).checked() }
        }
    }

    @Test
    fun unusableKeysAreRejected() {
        listOf("", "short", "valid-token-123456789012345\nInjected: test").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { CoachConnection("https://home.tail123.ts.net:8765", invalid).checked() }
        }
    }

    @Test
    fun legacyHttpConnectionRetainsItsValuesButCannotBeUsed() {
        val connection = CoachConnection("http://100.64.0.1:8765", token)
        assertFalse(connection.configured)
        assertEquals("http://100.64.0.1:8765", connection.url)
        assertEquals(token, connection.token)
        assertThrows(IllegalArgumentException::class.java) { connection.checked() }
    }
}
