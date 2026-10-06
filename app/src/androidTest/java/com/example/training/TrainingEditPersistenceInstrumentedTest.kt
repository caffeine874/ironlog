package com.example.training

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/** Calls the real persistence helper against a separate UUID-scoped database and preferences. */
@RunWith(AndroidJUnit4::class)
class TrainingEditPersistenceInstrumentedTest {
    private fun record(id: Long) = WorkoutSet(
        id, "2026.9.23", "保存確認$id", "背中", 62.5, 8, 3, "改行\nメモ$id",
        rpe = 8, soreness = 2, jointPain = 1, sleep = "GOOD", condition = "OK"
    )

    @Test fun changesTouchOnlyRequestedIdsAndPreserveUntouchedRawLegacyValues() = withDatabase { context ->
        val untouched = record(1)
        val editedBefore = record(2)
        val removed = record(3)
        val previous = listOf(untouched, editedBefore, removed)
        persist(context, emptyList(), previous)
        val beforeUntouched = context.database().use { db ->
            // Model loading normalizes these labels; an unchanged row must not be rewritten.
            db.execSQL("UPDATE workout_sets SET sleep = '良い', condition = '普通' WHERE id = 1")
            db.execSQL("INSERT INTO custom_exercises(name) VALUES ('保存するカスタム種目')")
            db.execSQL("INSERT INTO hidden_exercises(name) VALUES ('保存する非表示種目')")
            // A row absent from the in-memory snapshot also must survive a targeted edit.
            db.execSQL("INSERT INTO workout_sets SELECT 99, date, exercise, body_part, weight, reps, sets, memo, rpe, soreness, joint_pain, sleep, condition FROM workout_sets WHERE id = 1")
            db.execSQL("CREATE TABLE write_audit (record_id INTEGER NOT NULL)")
            db.execSQL("CREATE TRIGGER audit_insert AFTER INSERT ON workout_sets BEGIN INSERT INTO write_audit VALUES (NEW.id); END")
            db.execSQL("CREATE TRIGGER audit_update AFTER UPDATE ON workout_sets BEGIN INSERT INTO write_audit VALUES (NEW.id); END")
            db.execSQL("CREATE TRIGGER audit_delete AFTER DELETE ON workout_sets BEGIN INSERT INTO write_audit VALUES (OLD.id); END")
            rows(db).filterKeys { it == 1L || it == 99L }
        }
        val edited = editedBefore.copy(
            date = "2026.9.22", exercise = "編集後の種目", bodyPart = "脚", weight = 77.5,
            reps = 11, sets = 4, memo = "編集したメモ\n全項目を確認", rpe = 9, soreness = 4,
            jointPain = 3, sleep = "BAD", condition = "GOOD"
        )
        val added = record(4).copy(weight = 20.25, reps = 7, sets = 2)
        val next = listOf(untouched, edited, added)
        persist(context, previous, next)

        context.database().use { db ->
            val after = rows(db)
            assertEquals(setOf(1L, 2L, 4L, 99L), after.keys)
            assertEquals(beforeUntouched, after.filterKeys { it == 1L || it == 99L })
            assertRecord(edited, after.getValue(2))
            assertRecord(added, after.getValue(4))
            assertEquals(listOf("保存するカスタム種目"), strings(db, "SELECT name FROM custom_exercises"))
            assertEquals(listOf("保存する非表示種目"), strings(db, "SELECT name FROM hidden_exercises"))
            assertEquals(setOf("2", "3", "4"), strings(db, "SELECT DISTINCT record_id FROM write_audit").toSet())
            db.execSQL("DELETE FROM write_audit")
        }
        persist(context, next, next)
        context.database().use { db ->
            assertEquals(emptyList<String>(), strings(db, "SELECT record_id FROM write_audit"))
            assertEquals(beforeUntouched, rows(db).filterKeys { it == 1L || it == 99L })
        }
    }

    @Test fun failedInsertRollsBackEarlierDeletionAndEdits() = withDatabase { context ->
        val previous = listOf(record(10), record(11))
        persist(context, emptyList(), previous)
        val before = context.database().use { db ->
            db.execSQL("CREATE TRIGGER reject_test_insert BEFORE INSERT ON workout_sets WHEN NEW.id = 500 BEGIN SELECT RAISE(ABORT, 'deliberate isolated test failure'); END")
            rows(db)
        }
        try {
            persist(context, previous, listOf(previous[1].copy(weight = 90.0), record(500)))
            fail("The failing insert must escape the helper so the caller does not replace its saved UI snapshot.")
        } catch (error: SQLException) {
            assertTrue(error.message.orEmpty().contains("deliberate isolated test failure"))
        }
        context.database().use { db -> assertEquals(before, rows(db)) }
    }

    private fun persist(context: Context, previous: List<WorkoutSet>, next: List<WorkoutSet>) {
        val method = Class.forName("com.example.training.MainActivityKt").getDeclaredMethod(
            "saveWorkoutChanges", Context::class.java, List::class.java, List::class.java
        ).apply { isAccessible = true }
        try {
            method.invoke(null, context, previous, next)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun rows(db: SQLiteDatabase): Map<Long, Map<String, String?>> =
        db.rawQuery("SELECT * FROM workout_sets ORDER BY id", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getLong(cursor.getColumnIndexOrThrow("id")), cursor.columnNames.associateWith { column ->
                        val index = cursor.getColumnIndexOrThrow(column)
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    })
                }
            }
        }

    private fun strings(db: SQLiteDatabase, sql: String): List<String> = db.rawQuery(sql, null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun assertRecord(expected: WorkoutSet, row: Map<String, String?>) {
        assertEquals(expected.id.toString(), row["id"])
        assertEquals(expected.date, row["date"])
        assertEquals(expected.exercise, row["exercise"])
        assertEquals(expected.bodyPart, row["body_part"])
        assertEquals(expected.weight, row.getValue("weight")!!.toDouble(), 0.0)
        assertEquals(expected.reps.toString(), row["reps"])
        assertEquals(expected.sets.toString(), row["sets"])
        assertEquals(expected.memo, row["memo"])
        assertEquals(expected.rpe.toString(), row["rpe"])
        assertEquals(expected.soreness.toString(), row["soreness"])
        assertEquals(expected.jointPain.toString(), row["joint_pain"])
        assertEquals(expected.sleep, row["sleep"])
        assertEquals(expected.condition, row["condition"])
    }

    private fun withDatabase(block: (IsolatedTrainingContext) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val directory = File(base.cacheDir, "isolated-training-edit-$suffix").apply { check(mkdirs()) }
        val context = IsolatedTrainingContext(base, directory, "isolated_training_edit_${suffix}_")
        check(context.getDatabasePath("training.db").canonicalPath != base.getDatabasePath("training.db").canonicalPath)
        try {
            block(context)
        } finally {
            context.cleanupPreferences()
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    private class IsolatedTrainingContext(base: Context, private val directory: File, private val preferencesPrefix: String) : ContextWrapper(base) {
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File {
            check(name == "training.db") { "This test permits access only to its isolated training database." }
            return File(directory, name)
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = preferencesPrefix + name
            preferenceNames += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun cleanupPreferences() { preferenceNames.forEach { baseContext.deleteSharedPreferences(it) } }
        fun database(): SQLiteDatabase = SQLiteDatabase.openDatabase(getDatabasePath("training.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    }
}
