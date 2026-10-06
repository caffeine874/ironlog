package com.example.training

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production foreground gate without opening a browser or contacting an account. */
@RunWith(AndroidJUnit4::class)
class CoachForegroundInstrumentedTest {
    @Test fun waitingForBrowserReturnContinuesOnlyAfterTheActivityResumes() = runBlocking {
        withTimeout(5_000) {
            withContext(Dispatchers.Main.immediate) {
                val owner = TestLifecycleOwner()
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                var continued = false
                val waiting = launch {
                    awaitCoachForeground(owner.lifecycle)
                    continued = true
                }
                yield()
                assertFalse("A merely visible or background activity must not pass the gate", continued)
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
                waiting.join()
                assertTrue(continued)
                owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    @Test fun destroyedActivityCancelsTheGateWithoutContinuingSignIn() = runBlocking {
        withTimeout(5_000) {
            withContext(Dispatchers.Main.immediate) {
                val owner = TestLifecycleOwner()
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                var continued = false
                val waiting = launch {
                    awaitCoachForeground(owner.lifecycle)
                    continued = true
                }
                yield()
                owner.lifecycle.currentState = Lifecycle.State.DESTROYED
                waiting.join()
                assertTrue(waiting.isCancelled)
                assertFalse(continued)
            }
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
}
