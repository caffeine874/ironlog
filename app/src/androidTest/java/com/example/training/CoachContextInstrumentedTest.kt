package com.example.training

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Exercises Android's actual JSONObject implementation using synthetic records only. */
@RunWith(AndroidJUnit4::class)
class CoachContextInstrumentedTest {
    private fun date(offset: Int = 0): String = SimpleDateFormat("yyyy.M.d", Locale.ROOT).format(
        Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, offset) }.time
    )

    private fun workout(id: Long, day: String, exercise: String = "ベンチプレス", memo: String = "") =
        WorkoutSet(id, day, exercise, "胸", 60.0, 10, 3, memo)

    @Test fun yearsOfRecordsStayWithinRelayBudgetAndAllRecordReferencesResolve() {
        val names = (1..100).map { "ベンチ種目$it" + "長".repeat(100) }
        val old = (1..5000).map { workout(it.toLong(), date(-it - 30), names[it % names.size], "メモ".repeat(200)) }
        val recent = (1..240).map { workout((10000 + it).toLong(), date(-(it % 20)), names[it % names.size], "メモ".repeat(200)) }
        val context = CoachContext.build(old + recent, "最近ベンチやりすぎ？", date(), names)
        assertTrue("Initial context must fit relay's 80,000-character contract", context.toString().length <= 80_000)
        val records = context.getJSONArray("records")
        assertTrue(records.length() <= 60)
        val ids = (0 until records.length()).map { records.getJSONObject(it).getLong("id") }.toSet()
        assertEquals(ids.size, records.length())
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            assertTrue(record.getString("exercise").length <= 80)
            assertTrue(record.getString("memo").length <= 120)
            assertTrue(record.getBoolean("memoTruncated"))
        }
        val catalog = context.getJSONObject("exerciseCatalog")
        assertEquals(60, catalog.getJSONArray("names").length())
        assertTrue(catalog.getBoolean("truncated"))
        val menu = context.getJSONObject("recordedMenu")
        assertFalse(menu.getBoolean("plannedMenuAvailable"))
        val slices = mutableListOf(menu.getJSONObject("selectedDay"), menu.getJSONObject("today"), context.getJSONObject("recentHistory"))
        val relevant = context.getJSONObject("relevantHistory").getJSONArray("exercises")
        for (index in 0 until relevant.length()) slices += relevant.getJSONObject(index)
        for (slice in slices) {
            val references = slice.getJSONArray("recordIds")
            for (index in 0 until references.length()) assertTrue(references.getLong(index) in ids)
            assertEquals(slice.getInt("includedRecordCount"), references.length())
        }
        assertEquals(5240, context.getJSONObject("availableHistory").getInt("validRecordCount"))
        assertEquals(240, context.getJSONObject("periodComparison").getJSONObject("current").getInt("matchingRecordCount"))
    }

    @Test fun selectedOldDayIsDistinctFromTodayAndRollingComparisonIsExplicit() {
        val entries = listOf(workout(1, date(-100)), workout(2, date()), workout(3, date(-29)), workout(4, date(-30)))
        val context = CoachContext.build(entries, "この1か月のベンチの伸びを見て", date(-100), listOf("ベンチプレス"))
        val menu = context.getJSONObject("recordedMenu")
        assertEquals(1L, menu.getJSONObject("selectedDay").getJSONArray("recordIds").getLong(0))
        assertEquals(2L, menu.getJSONObject("today").getJSONArray("recordIds").getLong(0))
        val comparison = context.getJSONObject("periodComparison")
        assertEquals(30, comparison.getInt("daysPerPeriod"))
        assertTrue(comparison.getString("definition").contains("not calendar months"))
        assertEquals(3600.0, comparison.getJSONObject("current").getJSONObject("totals").getDouble("volumeKgReps"), 0.001)
        assertEquals(1800.0, comparison.getJSONObject("previous").getJSONObject("totals").getDouble("volumeKgReps"), 0.001)
        assertEquals(100.0, comparison.getJSONObject("change").getDouble("volumePercent"), 0.001)
    }

    @Test fun requestedOldRangeIncludesBoundariesAndAggregatesEveryMatchingRecord() {
        val entries = (1..100).map { workout(it.toLong(), "2024.1.1") } +
            workout(101, "2024.1.31") + workout(102, "2023.12.31") + workout(103, "2024.2.1") +
            workout(104, "2024.1.1", "スクワット")
        val result = CoachContext.history(entries, JSONObject()
            .put("from", "2024-01-01").put("to", "2024-01-31").put("exercise", "ベンチ").put("limit", 1000))
        assertEquals(101, result.getInt("matchingRecordCount"))
        assertEquals(60, result.getInt("includedRecordCount"))
        assertTrue(result.getBoolean("recordsTruncated"))
        assertTrue(result.getBoolean("aggregateCompleteForValidRecords"))
        assertEquals(181800.0, result.getJSONObject("totals").getDouble("volumeKgReps"), 0.001)
        assertEquals(101L, result.getJSONArray("records").getJSONObject(0).getLong("id"))
        assertTrue(result.toString().length <= 60_000)
        val invalid = CoachContext.history(entries, JSONObject().put("from", "2024-02-30"))
        assertTrue(invalid.has("error"))
    }
}
