package com.nuomisp.englishbook.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ReminderPlanTest {
    @Test fun randomizedDaysRespectSpacingQuietHoursAndRecap() {
        val start = LocalDate.of(2026, 1, 1)
        repeat(366) { day ->
            repeat(20) { seed ->
                val plan = ReminderPlan.forDate(start.plusDays(day.toLong()), seed)
                assertEquals(5, plan.size)
                val random = plan.filterNot { it.recap }
                assertEquals(4, random.size)
                assertTrue(random.all { it.minuteOfDay in 540..1260 })
                assertTrue(random.zipWithNext().all { (a, b) -> b.minuteOfDay - a.minuteOfDay >= 90 })
                assertEquals(ReminderSlot(1290, true), plan.last())
                assertTrue(plan.last().minuteOfDay - random.last().minuteOfDay >= 30)
            }
        }
    }

    @Test fun scheduleIsStablePerInstallationAndDateButChangesAcrossDays() {
        val date = LocalDate.of(2026, 9, 16)
        assertEquals(ReminderPlan.forDate(date, 42), ReminderPlan.forDate(date, 42))
        assertTrue(ReminderPlan.forDate(date, 42) != ReminderPlan.forDate(date.plusDays(1), 42))
    }

    @Test fun delayedWorkNeverLeaksIntoQuietHoursOrNextDay() {
        val date = LocalDate.of(2026, 9, 16)
        val random = ReminderSlot(10 * 60, false)
        assertTrue(ReminderPlan.canDeliver(random, date, date, LocalTime.of(10, 20)))
        assertFalse(ReminderPlan.canDeliver(random, date, date, LocalTime.of(10, 46)))
        assertFalse(ReminderPlan.canDeliver(random, date, date.plusDays(1), LocalTime.of(10, 0)))
        assertFalse(ReminderPlan.canDeliver(random, date, date, LocalTime.of(8, 59)))
        assertFalse(ReminderPlan.canDeliver(ReminderSlot(20 * 60 + 40, false), date, date, LocalTime.of(21, 0)))
        val recap = ReminderSlot(1290, true)
        assertFalse(ReminderPlan.canDeliver(recap, date, date, LocalTime.of(21, 29)))
        assertTrue(ReminderPlan.canDeliver(recap, date, date, LocalTime.of(21, 40)))
        assertFalse(ReminderPlan.canDeliver(recap, date, date, LocalTime.of(22, 0)))
    }
}
