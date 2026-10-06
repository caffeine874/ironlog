package com.example.training

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Deliberately gated to the isolated emulator fixture. Cleanup can touch only the seeded row and our UUID-tagged row. */
@RunWith(AndroidJUnit4::class)
class TrainingRecordUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val seedId = "1900000000100"
    private val seedMarker = "multi-set-regression-seed"

    @Test fun editKeepsExistingSetCountAndCreateDeleteCancelPreserveEveryOtherRecord() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "This mutation test runs only on the isolated Android emulator." }
        compose.waitForIdle()
        val originalSeed = workoutWhere("id = ?", arrayOf(seedId)).single()
        check(originalSeed["memo"] == seedMarker && originalSeed["exercise"] == "UI確認・複数セット") {
            "Required synthetic upgrade fixture is missing; do not modify another row."
        }
        assertEquals("3", originalSeed["sets"])
        assertEquals(67.5, originalSeed.getValue("weight")!!.toDouble(), 0.001)
        val before = allTrainingRows()
        val marker = "ui-roundtrip-${UUID.randomUUID()}"

        try {
            compose.onNodeWithText("記録").performClick()
            compose.onNodeWithText("67.5×11 ×3").performScrollTo().performClick()
            compose.onNodeWithText("セットを編集中").assertIsDisplayed()
            compose.onNodeWithContentDescription("重量を増やす").performClick()
            compose.onNodeWithText("変更を保存").performScrollTo().performClick()
            compose.waitForIdle()
            val edited = workoutWhere("id = ?", arrayOf(seedId)).single()
            assertEquals(70.0, edited.getValue("weight")!!.toDouble(), 0.001)
            assertEquals("3", edited["sets"])
            assertEquals(originalSeed.filterKeys { it != "weight" }, edited.filterKeys { it != "weight" })

            compose.onNodeWithText("70×11 ×3").performScrollTo().performClick()
            compose.onNodeWithContentDescription("重量を減らす").performClick()
            compose.onNodeWithText("変更を保存").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(originalSeed, workoutWhere("id = ?", arrayOf(seedId)).single())

            compose.onNodeWithContentDescription("種目を選択").performScrollTo().performClick()
            compose.onNode(hasText("ベンチプレス") and hasAnyAncestor(isPopup())).performClick()
            enterNumber("重量を入力", listOf("8", "2", ".", "5"))
            enterNumber("回数を入力", listOf("7"))
            compose.onNodeWithText("詳細").performScrollTo().performClick()
            compose.onNodeWithText("メモ").performScrollTo().performTextInput(marker)
            compose.runOnUiThread {
                val keyboard = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                keyboard.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
            }
            compose.onNodeWithText("セットを記録").performScrollTo().performClick()
            compose.waitForIdle()
            val created = workoutWhere("memo = ?", arrayOf(marker)).single()
            assertEquals("ベンチプレス", created["exercise"])
            assertEquals("7", created["reps"])
            assertEquals("1", created["sets"])
            assertEquals(82.5, created.getValue("weight")!!.toDouble(), 0.001)
            assertEquals(before.getValue("workout_sets").size + 1, allTrainingRows().getValue("workout_sets").size)
            compose.onNodeWithText("休憩").assertDoesNotExist()
            compose.onNodeWithText("スキップ").assertDoesNotExist()
            compose.onNode(hasText("タイマー") and hasClickAction()).performClick()
            compose.onNodeWithTag("workout-timer-remaining").assertTextEquals("02:00")
            compose.onNodeWithTag("workout-timer-status").assertTextEquals("準備完了")
            compose.onNodeWithText("開始").assertIsDisplayed()
            compose.onNodeWithText("一時停止").assertDoesNotExist()
            compose.onNodeWithText("記録").performClick()
            compose.onNodeWithText("82.5×7").performScrollTo().assertIsDisplayed()
            saveScreenshot("training-created-record-test.png")

            compose.onNodeWithText("82.5×7").performTouchInput { longClick() }
            compose.onNodeWithText("このセットを削除しますか？").assertIsDisplayed()
            compose.onNodeWithText("キャンセル").performClick()
            assertEquals(created, workoutWhere("memo = ?", arrayOf(marker)).single())

            compose.onNodeWithText("82.5×7").performTouchInput { longClick() }
            compose.onNodeWithText("削除").performClick()
            compose.waitForIdle()
            assertTrue(workoutWhere("memo = ?", arrayOf(marker)).isEmpty())
            assertEquals(before, allTrainingRows())
        } finally {
            // A failed UI assertion must never leave test data or alter any unrelated baseline row.
            database(SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.beginTransaction()
                try {
                    db.delete("workout_sets", "memo = ?", arrayOf(marker))
                    val seedNow = readRows(db, "SELECT * FROM workout_sets WHERE id = ?", arrayOf(seedId)).singleOrNull()
                    if (seedNow != originalSeed) {
                        val values = ContentValues().apply {
                            originalSeed.filterKeys { it != "id" }.forEach { (name, value) ->
                                if (value == null) putNull(name) else put(name, value)
                            }
                        }
                        assertEquals(1, db.update("workout_sets", values, "id = ?", arrayOf(seedId)))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            assertEquals("All pre-existing training and exercise rows must survive the UI exercise", before, allTrainingRows())
        }
    }

    private fun enterNumber(description: String, digits: List<String>) {
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
        digits.forEach { digit -> compose.onNode(hasText(digit) and hasAnyAncestor(androidx.compose.ui.test.isDialog())).performClick() }
        compose.onNodeWithText("決定").performClick()
    }

    private fun database(flags: Int): SQLiteDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return SQLiteDatabase.openDatabase(context.getDatabasePath("training.db").absolutePath, null, flags)
    }

    private fun workoutWhere(where: String, args: Array<String>): List<Map<String, String?>> =
        database(SQLiteDatabase.OPEN_READONLY).use { db -> readRows(db, "SELECT * FROM workout_sets WHERE $where ORDER BY id", args) }

    private fun allTrainingRows(): Map<String, List<Map<String, String?>>> = database(SQLiteDatabase.OPEN_READONLY).use { db ->
        listOf("workout_sets", "custom_exercises", "hidden_exercises").associateWith { table ->
            readRows(db, "SELECT * FROM $table ORDER BY rowid", null)
        }
    }

    private fun readRows(db: SQLiteDatabase, sql: String, args: Array<String>?): List<Map<String, String?>> =
        db.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.withIndex().associate { (index, name) ->
                    name to if (cursor.isNull(index)) null else cursor.getString(index)
                })
            }
        }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { output ->
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        screenshot.recycle()
    }
}
