package com.example.training

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

@RunWith(AndroidJUnit4::class)
class ChatGptJwtInstrumentedTest {
    private val now = 1_800_000_000L
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun verifiesSignedIdentityAndRejectsTampering() {
        val claims = claims()
        val token = sign(claims)
        assertEquals("account-subject", ChatGptJwt.verify(token, jwks(), "oaiapp_test", "expected-nonce", now).getString("sub"))
        val pieces = token.split('.')
        val tampered = "${pieces[0]}.${encode(claims.put("sub", "attacker").toString().toByteArray())}.${pieces[2]}"
        reject(tampered)
        val otherPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        reject(sign(claims(), otherPair))
    }

    @Test
    fun rejectsInvalidIssuerAudienceNonceAndTimesEvenWhenSigned() {
        listOf(
            claims().put("iss", "https://attacker.test"),
            claims().put("aud", "oaiapp_other"),
            claims().put("nonce", "wrong-nonce"),
            claims().put("exp", now - 10),
            claims().put("iat", now + 10),
            claims().put("nbf", now + 10),
            claims().put("sub", ""),
            claims().put("aud", JSONArray(listOf("oaiapp_test", "other"))),
            claims().put("azp", "oaiapp_other"),
            claims().apply { remove("exp") },
            claims().apply { remove("iat") },
            claims().apply { remove("nonce") }
        ).forEach { reject(sign(it)) }
    }

    @Test
    fun rejectsUnsignedAlgorithmAndUnknownKey() {
        val payload = encode(claims().toString().toByteArray())
        reject("${encode("{\"alg\":\"none\",\"kid\":\"test-key\"}".toByteArray())}.$payload.fake")
        val badKeys = jwks().apply { getJSONArray("keys").getJSONObject(0).put("kid", "different-key") }
        assertThrows(ChatGptAuthException::class.java) { ChatGptJwt.verify(sign(claims()), badKeys, "oaiapp_test", "expected-nonce", now) }
    }

    private fun reject(token: String) {
        assertThrows(ChatGptAuthException::class.java) { ChatGptJwt.verify(token, jwks(), "oaiapp_test", "expected-nonce", now) }
    }

    private fun claims() = JSONObject().put("iss", "https://auth.openai.com").put("sub", "account-subject")
        .put("aud", "oaiapp_test").put("nonce", "expected-nonce").put("iat", now - 60).put("exp", now + 600)

    private fun sign(claims: JSONObject, signer: KeyPair = pair): String {
        val header = encode("{\"alg\":\"RS256\",\"kid\":\"test-key\"}".toByteArray())
        val payload = "$header.${encode(claims.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(signer.private); update(payload.toByteArray()) }.sign()
        return "$payload.${encode(signature)}"
    }

    private fun jwks(): JSONObject {
        val publicKey = pair.public as RSAPublicKey
        val jwk = JSONObject().put("kty", "RSA").put("kid", "test-key").put("alg", "RS256").put("use", "sig")
            .put("n", encode(publicKey.modulus.toByteArray())).put("e", encode(publicKey.publicExponent.toByteArray()))
        return JSONObject().put("keys", JSONArray().put(jwk))
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
