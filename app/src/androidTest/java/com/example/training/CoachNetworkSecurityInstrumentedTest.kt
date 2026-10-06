package com.example.training

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoachNetworkSecurityInstrumentedTest {
    @Test fun platformBlocksRemoteCleartextAndKeepsOAuthLoopbackAvailable() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted("100.64.0.1"))
        assertFalse(policy.isCleartextTrafficPermitted("computer.tail123.ts.net"))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue(policy.isCleartextTrafficPermitted("localhost"))
    }
}
