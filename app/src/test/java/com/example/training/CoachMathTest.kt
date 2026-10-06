package com.example.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachMathTest {
    private fun workout(id: Long, date: String = "2026.9.21", exercise: String = "ベンチプレス",
                        weight: Double = 60.0, reps: Int = 10, sets: Int = 3) =
        WorkoutSet(id, date, exercise, "胸", weight, reps, sets, "")

    @Test fun volumeCountsAllSetsAndGroupsSessionsByCalendarDay() {
        val totals = CoachMath.totals(CoachMath.records(listOf(
            workout(1), workout(2, weight = 50.0, reps = 8, sets = 2),
            workout(3, date = "2026.9.20", exercise = "スクワット", weight = 100.0, reps = 5, sets = 4)
        )))
        assertEquals(4600.0, totals.volume, 0.0001)
        assertEquals(9L, totals.setCount)
        assertEquals(66L, totals.totalRepetitions)
        assertEquals(2, totals.sessionCount)
        assertEquals(100.0, totals.maxWeight!!, 0.0001)
    }

    @Test fun datesAreStrictAndIndependentOfFormatting() {
        assertEquals(CoachMath.parseDay("2026.9.2"), CoachMath.parseDay("2026-09-02"))
        assertEquals("2026-09-02", CoachMath.formatDay(CoachMath.parseDay("2026.9.2")!!))
        assertNull(CoachMath.parseDay("2026.2.30"))
        assertNull(CoachMath.parseDay("2026.13.1"))
        assertNull(CoachMath.parseDay("2026.9.2garbage"))
        assertNull(CoachMath.parseDay("2026.9-2"))
    }

    @Test fun invalidRecordsDoNotPoisonArithmeticAndBodyweightStaysValid() {
        val records = CoachMath.records(listOf(
            workout(1, weight = Double.NaN), workout(2, weight = -1.0), workout(3, reps = 0),
            workout(4, sets = -2), workout(5, date = "not a date"), workout(6, weight = 0.0)
        ))
        assertEquals(listOf(6L), records.map { it.workout.id })
        assertEquals(0.0, CoachMath.totals(records).volume, 0.0)
        assertEquals(3L, CoachMath.totals(records).setCount)
    }

    @Test fun rangeFilterIsInclusiveAndTruncationDoesNotChangeFullAggregate() {
        val records = CoachMath.records((1L..100L).map { workout(it) } +
            workout(101, date = "2026.9.20") + workout(102, date = "2026.9.22"))
        val date = CoachMath.parseDay("2026-09-21")!!
        val selection = CoachMath.select(records, date, date, "ベンチ", 5000)
        assertEquals(100, selection.matching.size)
        assertEquals(60, selection.included.size)
        assertEquals(100L, selection.included.first().workout.id)
        assertEquals(180000.0, CoachMath.totals(selection.matching).volume, 0.0001)
        assertEquals(1, CoachMath.select(records, date, date, null, -1).included.size)
    }

    @Test fun queriesRecognizeAliasesButExactHistoryFiltersKeepVariantsSeparate() {
        val names = listOf("ベンチプレス", "スクワット", "ブルガリアンスクワット", "懸垂", "ルーマニアンDL")
        assertEquals(listOf("ベンチプレス"), CoachMath.matchExercises("最近ベンチやりすぎ？", names))
        assertEquals(listOf("懸垂"), CoachMath.matchExercises("pull-upsの回数", names))
        assertEquals(listOf("ルーマニアンDL"), CoachMath.matchExercises("デッドの伸び", names))
        val records = CoachMath.records(listOf(workout(1, exercise = "スクワット"), workout(2, exercise = "ブルガリアンスクワット")))
        val day = records.first().day
        assertEquals(listOf("スクワット"), CoachMath.select(records, day, day, "スクワット", 20).exerciseNames)
        assertTrue(CoachMath.select(records, day, day, "存在しない種目", 20).matching.isEmpty())
    }

    @Test fun zeroBaselineIsUnknownRatherThanInfinitePercent() {
        assertNull(CoachMath.percentChange(100.0, 0.0))
        assertNull(CoachMath.percentChange(0.0, 0.0))
        assertEquals(25.0, CoachMath.percentChange(125.0, 100.0)!!, 0.0001)
        assertEquals(-100.0, CoachMath.percentChange(0.0, 100.0)!!, 0.0001)
    }
}
