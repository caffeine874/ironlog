package com.example.training

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Navigation through the real activity. Never creates, changes, or removes workout rows. */
@RunWith(AndroidJUnit4::class)
class CoachUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun coachAndConnectionSettingsOpenAndExistingRecordScreenRemainsAvailable() {
        compose.waitForIdle()
        val before = workoutRows()
        compose.onNodeWithText("記録").performClick()
        compose.onNodeWithText("今日の記録").assertIsDisplayed()
        compose.onNodeWithText("重量").assertIsDisplayed()
        compose.onNodeWithText("回数").assertIsDisplayed()
        saveScreenshot("training-record-test.png")
        compose.onNodeWithContentDescription("カレンダー").performClick()
        compose.onNodeWithText("閉じる").assertIsDisplayed()
        saveScreenshot("training-calendar-test.png")
        compose.onNodeWithText("閉じる").performClick()

        compose.onNodeWithText("グラフ").performClick()
        compose.onNodeWithText("総ボリューム").assertIsDisplayed()
        compose.onNodeWithText("全体の日別ボリューム").assertIsDisplayed()
        saveScreenshot("training-volume-graph-test.png")
        compose.onNodeWithText("履歴").performClick()
        compose.onNodeWithText("トレーニング履歴").assertIsDisplayed()
        compose.onNodeWithText("全種目").assertIsDisplayed()
        compose.onNodeWithText("種目別").assertIsDisplayed()
        compose.onNodeWithText("回復").assertDoesNotExist()
        saveScreenshot("training-history-tab-test.png")
        compose.onNodeWithText("コーチ").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("接続設定") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("筋トレの記録を見ながら相談").assertIsDisplayed()
        compose.onNodeWithText("質問を書く").assertIsDisplayed()
        compose.onNodeWithText("接続設定").assertIsEnabled()
        compose.onNodeWithText("モデル・推論").assertIsDisplayed()
        compose.onNodeWithText("Continue with ChatGPT").assertIsDisplayed()
        compose.onNodeWithText("毎回の使い方").performClick()
        compose.onNodeWithText("始めるとき").assertIsDisplayed()
        compose.onNodeWithText("わかりました").performClick()
        saveScreenshot("coach-chat-test.png")

        compose.onNodeWithText("接続設定").performClick()
        compose.onNodeWithText("接続方法：ChatGPT").assertIsDisplayed()
        compose.onNodeWithText("ChatGPTの利用状況を開く").assertIsDisplayed()
        saveScreenshot("coach-chatgpt-settings-test.png")
        compose.onNodeWithText("詳細設定").assertDoesNotExist()
        compose.onNodeWithText("家のPCへの接続を使う").assertDoesNotExist()
        compose.onNodeWithText("PCの接続設定").assertDoesNotExist()
        compose.onNodeWithText("閉じる").performClick()

        compose.onNodeWithText("記録").performClick()
        compose.onNodeWithText("今日の記録").assertIsDisplayed()
        compose.onNodeWithText("セットを記録").performScrollTo().assertIsDisplayed()
        assertEquals("Opening the coach and settings must not change any workout or exercise row", before, workoutRows())
    }

    private fun workoutRows(): Map<String, List<List<String?>>> {
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
        android.os.SystemClock.sleep(350) // Let native dialog window fade-out finish before taking the image.
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UI screenshot unavailable" }
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }
}
