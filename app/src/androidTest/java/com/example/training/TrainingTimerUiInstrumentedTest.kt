package com.example.training

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises only the manual timer and navigation; every workout table is opened read-only. */
@RunWith(AndroidJUnit4::class)
class TrainingTimerUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun manualTimerPausesResumesResetsAndKeepsRunningAcrossTabs() {
        compose.waitForIdle()
        val before = trainingRows()
        try {
            navigate("タイマー")
            assertReady("02:00")
            clickTimerControl("1分")
            assertReady("01:00")
            clickTimerControl("3分")
            assertReady("03:00")
            setDuration(minutes = "0", seconds = "45")
            assertReady("00:45")

            clickTimerControl("開始")
            assertStatus("カウント中")
            compose.onNodeWithTag("workout-timer-minutes").assertIsNotEnabled()
            compose.onNodeWithTag("workout-timer-seconds").assertIsNotEnabled()
            waitUntilRemainingBelow(45)
            clickTimerControl("一時停止")
            assertStatus("一時停止中")
            compose.onNodeWithText("再開").assertIsEnabled()
            val pausedSeconds = remainingSeconds()
            assertTrue("Pause must retain some remaining time", pausedSeconds in 1..44)
            waitForRealTime(1_800)
            assertEquals("A paused timer must not advance", pausedSeconds, remainingSeconds())
            compose.onNodeWithTag("workout-timer-minutes").assertIsNotEnabled()
            compose.onNodeWithTag("workout-timer-seconds").assertIsNotEnabled()

            clickTimerControl("再開")
            assertStatus("カウント中")
            waitUntilRemainingBelow(pausedSeconds)
            val beforeNavigation = remainingSeconds()
            navigate("履歴")
            compose.onNodeWithText("トレーニング履歴").assertIsDisplayed()
            waitForRealTime(1_800)
            navigate("記録")
            compose.onNodeWithText("今日の記録").assertIsDisplayed()
            navigate("タイマー")
            assertStatus("カウント中")
            val afterNavigation = remainingSeconds()
            assertTrue("Countdown must continue while other tabs are open", afterNavigation < beforeNavigation)
            assertTrue("Tab navigation must not reset or finish this timer", afterNavigation > 0)
            saveScreenshot("training-manual-timer-running-test.png")

            clickTimerControl("リセット")
            assertReady("00:45")
            waitForRealTime(1_800)
            assertReady("00:45")
            compose.onNodeWithTag("workout-timer-minutes").assertIsEnabled()
            compose.onNodeWithTag("workout-timer-seconds").assertIsEnabled()
            clickTimerControl("2分")
            assertReady("02:00")
            saveScreenshot("training-manual-timer-ready-test.png")
        } finally {
            assertEquals("Timer operations must preserve every workout and exercise row", before, trainingRows())
        }
    }

    @Test fun shortManualTimerFinishesAndCanBeResetWithoutChangingTrainingData() {
        compose.waitForIdle()
        val before = trainingRows()
        try {
            navigate("タイマー")
            setDuration(minutes = "0", seconds = "2")
            assertReady("00:02")
            clickTimerControl("開始")
            compose.waitUntil(timeoutMillis = 15_000) { nodeText("workout-timer-status") == "終了" }
            compose.onNodeWithTag("workout-timer-remaining").assertTextEquals("00:00")
            compose.onNodeWithText("開始").assertIsEnabled()
            compose.onNodeWithText("一時停止").assertDoesNotExist()
            clickTimerControl("リセット")
            assertReady("00:02")
            clickTimerControl("2分")
            assertReady("02:00")
        } finally {
            assertEquals("Timer completion must preserve every workout and exercise row", before, trainingRows())
        }
    }

    private fun navigate(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
    }

    private fun clickTimerControl(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun setDuration(minutes: String, seconds: String) {
        // Change seconds first so editing 02:00 to 00:02 never temporarily produces an invalid 00:00.
        compose.onNodeWithTag("workout-timer-seconds").performScrollTo().performTextReplacement(seconds)
        compose.onNodeWithTag("workout-timer-minutes").performScrollTo().performTextReplacement(minutes)
        compose.runOnUiThread {
            val keyboard = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    private fun assertReady(time: String) {
        compose.onNodeWithTag("workout-timer-remaining").assertTextEquals(time)
        assertStatus("準備完了")
        compose.onNodeWithText("開始").assertIsEnabled()
    }

    private fun assertStatus(value: String) {
        compose.onNodeWithTag("workout-timer-status").assertTextEquals(value)
    }

    private fun nodeText(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun remainingSeconds(): Int {
        val parts = nodeText("workout-timer-remaining").split(":")
        assertEquals("Timer display must use MM:SS", 2, parts.size)
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    private fun waitUntilRemainingBelow(seconds: Int) {
        // Assert progress, not an exact one-second boundary, so a busy emulator is tolerated.
        compose.waitUntil(timeoutMillis = 10_000) { remainingSeconds() < seconds }
    }

    private fun waitForRealTime(durationMillis: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMillis
        compose.waitUntil(timeoutMillis = 10_000) { SystemClock.elapsedRealtime() >= deadline }
    }

    private fun trainingRows(): Map<String, List<List<String?>>> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = context.getDatabasePath("training.db")
        assertTrue("The existing app database must be available", path.exists())
        return SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            listOf("workout_sets", "custom_exercises", "hidden_exercises").associateWith { table ->
                db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                            if (cursor.isNull(column)) null else cursor.getString(column)
                        })
                    }
                }
            }
        }
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { output ->
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        screenshot.recycle()
    }
}
