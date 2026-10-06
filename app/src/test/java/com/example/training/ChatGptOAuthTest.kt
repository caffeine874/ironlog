package com.example.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatGptOAuthTest {
    private val state = "unguessable-state"
    private val dynamic = "dynamic_agent_client"

    @Test
    fun newRegistrationRequiresIssuedClientId() {
        val result = ChatGptOAuth.validateCallback("/auth/callback?state=$state&code=one-use-code&client_id=oaiapp_new", state, dynamic)
        assertEquals("oaiapp_new", result.clientId)
        assertEquals("one-use-code", result.code)
        listOf("", "&client_id=", "&client_id=dynamic_agent_client").forEach { suffix ->
            rejected("/auth/callback?state=$state&code=code$suffix", dynamic)
        }
    }

    @Test
    fun returningRegistrationCannotBeReplacedByCallback() {
        val result = ChatGptOAuth.validateCallback("/auth/callback?state=$state&code=code", state, "oaiapp_saved")
        assertEquals("oaiapp_saved", result.clientId)
        rejected("/auth/callback?state=$state&code=code&client_id=oaiapp_other", "oaiapp_saved")
    }

    @Test
    fun forgedAmbiguousAndWrongDestinationCallbacksAreRejected() {
        listOf(
            "/auth/callback?state=wrong&code=code",
            "/auth/callback?code=code",
            "/auth/callback?state=$state&code=code&state=$state",
            "/auth/callback?state=$state&code=first&code=second",
            "/auth/callback?state=$state&code=first&%63ode=second",
            "/auth/callback?state=$state&code=code#fragment",
            "/callback?state=$state&code=code",
            "http://127.0.0.1/auth/callback?state=$state&code=code",
            "//attacker.test/auth/callback?state=$state&code=code",
            "/auth/callback?state=$state&code=%ZZ",
            "/auth/callback?state=$state&code=",
            "/auth/callback?state=$state&code=code&client_id=oaiapp_saved%0AInjected"
        ).forEach { rejected(it, "oaiapp_saved") }
    }

    @Test
    fun declinedConsentNeverRedeemsProvidedCode() {
        val error = assertThrows(ChatGptAuthException::class.java) {
            ChatGptOAuth.validateCallback("/auth/callback?state=$state&error=access_denied&code=must-not-use", state, "oaiapp_saved")
        }
        assertEquals("access_denied", error.code)
        val forged = assertThrows(ChatGptAuthException::class.java) {
            ChatGptOAuth.validateCallback("/auth/callback?state=forged&error=access_denied", state, "oaiapp_saved")
        }
        assertEquals("invalid_state", forged.code)
    }

    private fun rejected(value: String, clientId: String) {
        assertThrows("Unexpectedly accepted callback", ChatGptAuthException::class.java) {
            ChatGptOAuth.validateCallback(value, state, clientId)
        }
    }
}
