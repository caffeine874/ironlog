package com.example.training

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Reads the four-row upgrade fixture; navigation must never modify existing training data. */
@RunWith(AndroidJUnit4::class)
class TrainingHistoryUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun historyFiltersKeepSetCountsAndOldRecordsWhileGraphKeepsItsControls() {
        compose.waitForIdle()
        val before = trainingRows()
        val workouts = before.getValue("workout_sets")
        assertEquals("Load the read-only ui19 upgrade fixture before this test", 4, workouts.size)
        val seed = workouts.single { it["id"] == "1900000000100" }
        assertEquals("multi-set-regression-seed", seed["memo"])
        assertEquals("3", seed["sets"])
        val todayTag = "history-day-${seed.getValue("date")}"

        try {
            compose.onNodeWithText("回復").assertDoesNotExist()
            compose.onNodeWithText("グラフ").performClick()
            compose.onNodeWithText("総ボリューム").assertIsDisplayed()
            listOf("日", "週", "月", "全体", "部位", "種目").forEach {
                compose.onNodeWithText(it).assertIsDisplayed()
            }
            // The only History label belongs to bottom navigation, not a graph sub-tab.
            compose.onAllNodesWithText("履歴").assertCountEquals(1)
            compose.onAllNodesWithText("グラフ").assertCountEquals(1)
            compose.onNodeWithText("全種目").assertDoesNotExist()
            compose.onNodeWithText("種目別").assertDoesNotExist()
            compose.onNodeWithText("全体の日別ボリューム").assertIsDisplayed()
            compose.onNodeWithText("週").performClick()
            compose.onNodeWithText("全体の週別ボリューム").assertIsDisplayed()
            compose.onNodeWithText("月").performClick()
            compose.onNodeWithText("全体の月別ボリューム").assertIsDisplayed()
            compose.onNodeWithText("日").performClick()
            compose.onNodeWithText("部位").performClick()
            compose.onNodeWithText("脚").assertIsDisplayed()
            compose.onNodeWithText("種目").performClick()
            compose.onNodeWithContentDescription("種目を選択").assertIsDisplayed()
            compose.onNodeWithText("全体").performClick()
            compose.onNodeWithContentDescription("種目を選択").assertDoesNotExist()
            saveScreenshot("training-history-graph-controls-test.png")

            compose.onNodeWithText("履歴").performClick()
            compose.onNodeWithText("トレーニング履歴").assertIsDisplayed()
            compose.onNodeWithText("全種目").assertIsDisplayed()
            compose.onNodeWithText("種目別").assertIsDisplayed()
            compose.onNodeWithContentDescription("種目を選択").assertDoesNotExist()
            compose.onNodeWithText("3日分の記録 ・ 新しい日付から表示").assertIsDisplayed()
            compose.onNodeWithTag(todayTag).assertIsDisplayed()
            assertInside(todayTag, "5セット") // Today's 3-set and 2-set records are summed.
            showRecord("1800000000001", "ベンチプレス", "80 kg × 8回 × 2セット")
            showRecord("1900000000100", "UI確認・複数セット", "67.5 kg × 11回 × 3セット")
            compose.onNodeWithTag("training-history-list").performScrollToIndex(0)
            saveScreenshot("training-history-all-test.png")

            compose.onNodeWithText("種目別").performClick()
            selectExercise("UI確認・複数セット")
            compose.onNodeWithText("1日分の記録 ・ 新しい日付から表示").assertIsDisplayed()
            showRecord("1900000000100", "UI確認・複数セット", "67.5 kg × 11回 × 3セット")
            assertInside(todayTag, "3セット")
            compose.onNodeWithTag("history-record-1800000000001").assertDoesNotExist()
            compose.onNodeWithTag("history-record-1800000000002").assertDoesNotExist()

            selectExercise("スクワット")
            compose.onNodeWithText("1日分の記録 ・ 新しい日付から表示").assertIsDisplayed()
            showRecord("1800000000002", "スクワット", "100.5 kg × 6回 × 5セット")
            assertInside("history-day-2026.8.23", "5セット")
            compose.onNodeWithTag("history-record-1900000000100").assertDoesNotExist()
            compose.onNodeWithTag("history-record-1800000000001").assertDoesNotExist()
            compose.onNodeWithTag("history-record-1800000000003").assertDoesNotExist()
            saveScreenshot("training-history-exercise-test.png")

            compose.onNodeWithText("全種目").performClick()
            compose.onNodeWithContentDescription("種目を選択").assertDoesNotExist()
            compose.onNodeWithText("3日分の記録 ・ 新しい日付から表示").assertIsDisplayed()
            compose.onNodeWithTag(todayTag).assertIsDisplayed()
            showRecord("1800000000001", "ベンチプレス", "80 kg × 8回 × 2セット")
            showRecord("1900000000100", "UI確認・複数セット", "67.5 kg × 11回 × 3セット")
            showRecord("1800000000002", "スクワット", "100.5 kg × 6回 × 5セット")
            showRecord("1800000000003", "検証用カスタム種目", "12.5 kg × 15回 × 1セット")
            compose.onNodeWithTag("history-day-2024.12.31").assertIsDisplayed()
            assertInside("history-record-1800000000003", "長期間前の記録")
            saveScreenshot("training-history-old-record-test.png")
        } finally {
            assertEquals("History and graph navigation must preserve every stored row", before, trainingRows())
        }
    }

    private fun selectExercise(name: String) {
        compose.onNodeWithContentDescription("種目を選択").performClick()
        compose.onNode(hasText(name) and hasAnyAncestor(isPopup())).performClick()
    }

    private fun showRecord(id: String, exercise: String, details: String) {
        val tag = "history-record-$id"
        compose.onNodeWithTag("training-history-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed()
        assertInside(tag, exercise)
        assertInside(tag, details)
    }

    private fun assertInside(tag: String, text: String) {
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun trainingRows(): Map<String, List<Map<String, String?>>> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = context.getDatabasePath("training.db")
        assertTrue("The existing app database must be available", path.exists())
        return SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            listOf("workout_sets", "custom_exercises", "hidden_exercises").associateWith { table ->
                db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.columnNames.withIndex().associate { (index, name) ->
                            name to if (cursor.isNull(index)) null else cursor.getString(index)
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
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UI screenshot unavailable" }
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }
}
